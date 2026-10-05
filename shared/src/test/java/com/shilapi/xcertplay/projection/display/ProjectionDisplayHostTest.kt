package com.shilapi.xcertplay.projection.display

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Surface lifecycle: attach/detach/re-attach must reach renderers so a
 * destroyed surface never keeps receiving MediaCodec output and an Activity
 * recreate re-attaches without dropping the session. Surface events are
 * owner-tagged: a late destroy from an old owner must not clear a newer
 * surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ProjectionDisplayHostTest {

    private class RecordingListener : ProjectionDisplayHost.Listener {
        val available = mutableListOf<ProjectionSurfaceHandle>()
        val changed = mutableListOf<ProjectionSurfaceHandle>()
        var destroyed = 0
        /** What a renderer would do: attach on available, detach on destroyed. */
        val attachLog = mutableListOf<String>()

        override fun onSurfaceAvailable(handle: ProjectionSurfaceHandle) {
            available.add(handle)
            attachLog.add("attach")
        }

        override fun onSurfaceChanged(handle: ProjectionSurfaceHandle) {
            changed.add(handle)
            attachLog.add("reattach")
        }

        override fun onSurfaceDestroyed() {
            destroyed++
            attachLog.add("detach")
        }
    }

    private fun surface(): Surface = Surface(SurfaceTexture(0))

    @Test
    fun surfaceAttachReachesRenderer() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        val surface = surface()
        host.surfaceAvailable(this, surface, 1920, 1080)
        assertEquals(1, listener.available.size)
        assertSame(surface, listener.available.single().surface)
        assertEquals(1920, listener.available.single().width)
        assertEquals(1080, listener.available.single().height)
    }

    @Test
    fun surfaceDestroyedDetachesRenderer() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        host.surfaceAvailable(this, surface(), 640, 360)
        host.surfaceDestroyed(this)
        assertEquals(1, listener.destroyed)
        assertNull(host.currentSurface)
        // Renderer detached before any re-attach.
        assertEquals(listOf("attach", "detach"), listener.attachLog)
    }

    @Test
    fun surfaceRecreatedReattachesRenderer() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        host.surfaceAvailable(this, surface(), 640, 360)
        host.surfaceDestroyed(this)
        // Activity recreate: a new surface appears from a new owner instance.
        val second = surface()
        host.surfaceAvailable(Any(), second, 1280, 720)
        assertEquals(listOf("attach", "detach", "attach"), listener.attachLog)
        assertSame(second, listener.available.last().surface)
    }

    @Test
    fun lateListenerImmediatelyReceivesTheCurrentSurface() {
        val host = ProjectionSurfaceHost()
        host.surfaceAvailable(this, surface(), 800, 480)
        val late = RecordingListener()
        // A renderer created mid-session (session start after the surface
        // exists) must attach without waiting for the next surface event.
        host.addListener(late)
        assertEquals(1, late.available.size)
    }

    @Test
    fun surfaceChangedReportsNewGeometry() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        val surface = surface()
        host.surfaceAvailable(this, surface, 640, 360)
        host.surfaceChanged(this, surface, 1280, 720)
        assertEquals(1, listener.changed.size)
        assertEquals(1280, listener.changed.single().width)
    }

    @Test
    fun removedListenerStopsReceivingEvents() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        host.removeListener(listener)
        host.surfaceAvailable(this, surface(), 640, 360)
        assertEquals(0, listener.available.size)
    }

    // ---- Owner/token race: a stale destroy must not clear a newer surface ----

    @Test
    fun staleDestroyFromOldOwnerKeepsTheNewSurface() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        val oldActivity = Any()
        val newActivity = Any()
        val surfaceA = surface()
        val surfaceB = surface()

        host.surfaceAvailable(oldActivity, surfaceA, 640, 360)
        // Activity recreate: the new Activity pushes its surface first…
        host.surfaceAvailable(newActivity, surfaceB, 1280, 720)
        // …then the old Activity's late callback arrives.
        host.surfaceDestroyed(oldActivity)

        // The old destroy must NOT clear the newer surface.
        assertSame(surfaceB, host.currentSurface?.surface)
        // Only the initial attach + the B attach; no spurious detach.
        assertEquals(listOf("attach", "attach"), listener.attachLog)
    }

    @Test
    fun currentOwnerCanStillDestroyItsSurface() {
        val host = ProjectionSurfaceHost()
        val owner = Any()
        host.surfaceAvailable(owner, surface(), 640, 360)
        host.surfaceDestroyed(owner)
        assertNull(host.currentSurface)
    }

    @Test
    fun staleChangedFromOldOwnerIsIgnored() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        val oldActivity = Any()
        val newActivity = Any()
        val surfaceB = surface()
        host.surfaceAvailable(oldActivity, surface(), 640, 360)
        host.surfaceAvailable(newActivity, surfaceB, 1280, 720)
        // Old Activity's surfaceChanged must not rewrite the new geometry.
        host.surfaceChanged(oldActivity, surface(), 10, 10)
        assertEquals(0, listener.changed.size)
        assertSame(surfaceB, host.currentSurface?.surface)
    }
}
