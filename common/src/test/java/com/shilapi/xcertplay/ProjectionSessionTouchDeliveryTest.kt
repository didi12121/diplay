// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionHost
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchAction
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 9.2b UI seam: SurfaceView MotionEvent → [ProjectionTouchEvents]
 * conversion → BOUND backend — EXACTLY ONCE per Android event.
 *
 * The projection surface owns its gestures: its OnTouchListener forwards and
 * consumes, so the same MotionEvent can never ALSO fall through to
 * Activity.onTouchEvent and be delivered twice (no double DOWN/MOVE/UP).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionSessionTouchDeliveryTest {

    /** Records the unified touch events the bound backend receives. */
    private class RecordingBackend(override val id: String) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        private val store = ProjectionStateStore(id)
        val touchLog = mutableListOf<ProjectionTouchEvent>()

        override val state: ProjectionState get() = store.state
        override val isSessionActive: Boolean = true
        override fun addStateListener(listener: ProjectionStateListener) = store.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = store.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}
        override fun connect(device: ProjectionDevice?) {}
        override fun disconnect(): Boolean = true
        override fun onTouchEvent(event: ProjectionTouchEvent): Boolean {
            touchLog.add(event)
            return true
        }

        override fun onKeyEvent(event: ProjectionKeyEvent): Boolean = false
        override fun close() {}
    }

    private lateinit var backend: RecordingBackend
    private lateinit var other: RecordingBackend
    private lateinit var activity: ProjectionSessionActivity

    @Before
    fun setUp() {
        backend = RecordingBackend("carlife")
        other = RecordingBackend("carplay")
        ProjectionHost.manager.register(backend)
        ProjectionHost.manager.register(other)
        val intent = ProjectionSessionActivity.createIntent(
            RuntimeEnvironment.getApplication(),
            backend.id,
        )
        activity = Robolectric.buildActivity(ProjectionSessionActivity::class.java, intent)
            .create().get()
        layOut(activity, SCREEN_WIDTH, SCREEN_HEIGHT)
    }

    @After
    fun tearDown() {
        ProjectionHost.manager.unregister(backend.id)
        ProjectionHost.manager.unregister(other.id)
    }

    // ---- Delivery: exactly one ProjectionTouchEvent per MotionEvent ----

    @Test
    fun surfaceViewMotionEventReachesBoundBackendExactlyOnce() {
        val surface = requireNotNull(surfaceView())
        // Down lands on the projection surface; the surface consumes it.
        assertTrue(surface.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 500f, 500f)))
        assertEquals(1, backend.touchLog.size)
    }

    @Test
    fun fullGestureViaActivityDispatchDeliversEachEventExactlyOnce() {
        // Real path: Activity -> decor -> FrameLayout -> surfaceView listener.
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 500f, 500f))
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, 510f, 510f))
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, 520f, 520f))
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, 520f, 520f))
        // Exactly one ProjectionTouchEvent per MotionEvent: if both the
        // surfaceView listener AND Activity.onTouchEvent forwarded, this
        // would be 8 instead of 4.
        assertEquals(4, backend.touchLog.size)
        assertEquals(
            listOf(
                ProjectionTouchAction.DOWN,
                ProjectionTouchAction.MOVE,
                ProjectionTouchAction.MOVE,
                ProjectionTouchAction.UP,
            ),
            backend.touchLog.map { it.action },
        )
    }

    @Test
    fun touchesAreRoutedToTheBoundBackendOnly() {
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f))
        assertEquals(1, backend.touchLog.size)
        assertEquals(0, other.touchLog.size)
    }

    @Test
    fun touchEventCarriesRawViewCoordinatesAndGeometry() {
        activity.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 500f, 500f))
        val touch = backend.touchLog.single()
        val pointer = touch.pointers.single()
        assertEquals(500f, pointer.x, 0f)
        assertEquals(500f, pointer.y, 0f)
        assertEquals(ProjectionTouchAction.DOWN, touch.action)
        // Content rect covers the whole surface (current full-screen case).
        assertEquals(0f, touch.geometry.contentRect.left, 0f)
        assertEquals(0f, touch.geometry.contentRect.top, 0f)
        assertEquals(SCREEN_WIDTH.toFloat(), touch.geometry.contentRect.width, 0f)
        assertEquals(SCREEN_HEIGHT.toFloat(), touch.geometry.contentRect.height, 0f)
    }

    // ---- Fallback still works, without double delivery ----

    @Test
    fun activityFallbackForwardsUnconsumedTouchesOnce() {
        // A touch that reaches the Activity (nothing consumed it) is forwarded
        // exactly once by the fallback seam.
        assertTrue(activity.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 300f, 300f)))
        assertEquals(1, backend.touchLog.size)
    }

    @Test
    fun surfaceViewIsTheActualTouchCapturePoint() {
        val surface = surfaceView()
        assertNotNull("projection page must own a SurfaceView", surface)
        assertTrue(surface!!.isShown || surface.visibility == View.VISIBLE)
        // The surface consumes touches itself (its listener returns true).
        assertTrue(surface.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 10f, 10f)))
    }

    // ---- Helpers ----

    private fun motion(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private fun surfaceView(): SurfaceView? = findSurfaceView(activity.window.decorView)

    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun layOut(activity: Activity, width: Int, height: Int) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    companion object {
        private const val SCREEN_WIDTH = 1920
        private const val SCREEN_HEIGHT = 1080
    }
}
