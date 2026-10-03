package com.shilapi.xcertplay.projection.display

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
 * recreate re-attaches without dropping the session.
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

    private fun surface(): Surface = Surface(android.graphics.SurfaceTexture(0))

    @Test
    fun surfaceAttachReachesRenderer() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        val surface = surface()
        host.surfaceAvailable(surface, 1920, 1080)
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
        host.surfaceAvailable(surface(), 640, 360)
        host.surfaceDestroyed()
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
        host.surfaceAvailable(surface(), 640, 360)
        host.surfaceDestroyed()
        // Activity recreate: a new surface appears.
        val second = surface()
        host.surfaceAvailable(second, 1280, 720)
        assertEquals(listOf("attach", "detach", "attach"), listener.attachLog)
        assertSame(second, listener.available.last().surface)
    }

    @Test
    fun lateListenerImmediatelyReceivesTheCurrentSurface() {
        val host = ProjectionSurfaceHost()
        host.surfaceAvailable(surface(), 800, 480)
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
        host.surfaceAvailable(surface, 640, 360)
        host.surfaceChanged(surface, 1280, 720)
        assertEquals(1, listener.changed.size)
        assertEquals(1280, listener.changed.single().width)
    }

    @Test
    fun removedListenerStopsReceivingEvents() {
        val host = ProjectionSurfaceHost()
        val listener = RecordingListener()
        host.addListener(listener)
        host.removeListener(listener)
        host.surfaceAvailable(surface(), 640, 360)
        assertEquals(0, listener.available.size)
    }
}
