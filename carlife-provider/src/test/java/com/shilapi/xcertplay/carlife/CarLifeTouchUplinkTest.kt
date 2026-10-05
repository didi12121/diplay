// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.content.Context
import android.view.MotionEvent
import com.shilapi.xcertplay.projection.ProjectionDisplayGeometry
import com.shilapi.xcertplay.projection.ProjectionRect
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionTouchAction
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTouchPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 9.2b REAL CarLife touch uplink — backend regression suite.
 *
 * Pipeline under test:
 * `ProjectionTouchEvent` → [CarLifeProjectionBackend] → [CarLifeProvider] →
 * `CarLifeReceiver.onTouchEvent(MotionEvent)` (high-level API only; the
 * RemoteControlManager in the upstream SDK performs the final surface → video
 * mapping).
 *
 * Invariants:
 *  - exactly ONE CarLife MotionEvent per unified touch event (DOWN/MOVE/UP/CANCEL)
 *  - content-LOCAL coordinates only — NEVER double-scaled to video size
 *  - surface size declared before/with the first touch, never resent unchanged
 *  - session-scoped touch: a stale gesture of session A can never reach B
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeTouchUplinkTest {

    /** Records what the backend sends towards the CarLife SDK. */
    private class RecordingProvider : CarLifeProvider {
        override val isAvailable = true
        val started = mutableListOf<CarLifeSessionToken>()
        val stopped = mutableListOf<CarLifeSessionToken>()
        val log = mutableListOf<String>()
        val surfaces = mutableListOf<Triple<CarLifeSessionToken, Int, Int>>()
        val touches = mutableListOf<SentTouch>()
        var rejectSends = false
        private var listener: ((CarLifeConnectionEvent) -> Unit)? = null

        /** Snapshot of one synchronous MotionEvent send. */
        data class SentTouch(
            val token: CarLifeSessionToken,
            val action: Int,
            val downTime: Long,
            val eventTime: Long,
            val x: Float,
            val y: Float,
        )

        override fun initialize(context: Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
            started.add(token)
            this.listener = listener
        }

        override fun stopConnection(token: CarLifeSessionToken) {
            stopped.add(token)
        }

        override fun updateTouchSurface(token: CarLifeSessionToken, width: Int, height: Int) {
            log.add("surface:${width}x$height")
            surfaces.add(Triple(token, width, height))
        }

        override fun sendTouch(token: CarLifeSessionToken, event: MotionEvent): Boolean {
            if (rejectSends) return false
            log.add("touch")
            touches.add(SentTouch(token, event.action, event.downTime, event.eventTime, event.x, event.y))
            return true
        }

        override fun dispose() {}

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }

    /** Deterministic event clock (SystemClock.uptimeMillis on device). */
    private class TestClock {
        var now = 1_000L
        fun advance(by: Long) {
            now += by
        }
    }

    private class Harness(val provider: RecordingProvider = RecordingProvider()) {
        val clock = TestClock()
        val backend = CarLifeProjectionBackend(
            provider,
            { _, _ -> AutoCloseable { } },
            60_000,
            null,
        ) { clock.now }

        fun connectAndEstablish(): CarLifeSessionToken {
            backend.connect(null)
            val token = provider.started.last()
            provider.emit(CarLifeConnectionEvent.Established(token))
            return token
        }
    }

    private fun touch(
        action: ProjectionTouchAction,
        x: Float,
        y: Float,
        contentRect: ProjectionRect = FULL_SCREEN,
        screenWidth: Int = 1920,
        screenHeight: Int = 1080,
    ): ProjectionTouchEvent = ProjectionTouchEvent(
        action = action,
        pointers = listOf(
            ProjectionTouchPointer(
                id = 0,
                x = x,
                y = y,
                down = action == ProjectionTouchAction.DOWN || action == ProjectionTouchAction.MOVE,
            ),
        ),
        geometry = ProjectionDisplayGeometry(screenWidth, screenHeight, contentRect),
    )

    // ---- Exactly one CarLife MotionEvent per action ----

    @Test
    fun downProducesExactlyOneCarLifeMotionEvent() {
        val h = Harness()
        h.connectAndEstablish()
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 100f, 200f)))
        assertEquals(1, h.provider.touches.size)
        val sent = h.provider.touches.single()
        assertEquals(MotionEvent.ACTION_DOWN, sent.action)
        assertEquals(100f, sent.x, 0f)
        assertEquals(200f, sent.y, 0f)
    }

    @Test
    fun moveProducesExactlyOneCarLifeMotionEvent() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 100f, 200f))
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 110f, 210f)))
        assertEquals(2, h.provider.touches.size)
        val sent = h.provider.touches.last()
        assertEquals(MotionEvent.ACTION_MOVE, sent.action)
        assertEquals(110f, sent.x, 0f)
        assertEquals(210f, sent.y, 0f)
    }

    @Test
    fun upProducesExactlyOneCarLifeMotionEvent() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 100f, 200f))
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.UP, 100f, 200f)))
        assertEquals(2, h.provider.touches.size)
        assertEquals(MotionEvent.ACTION_UP, h.provider.touches.last().action)
    }

    @Test
    fun cancelProducesExactlyOneCarLifeMotionEvent() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 100f, 200f))
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.CANCEL, 100f, 200f)))
        assertEquals(2, h.provider.touches.size)
        assertEquals(MotionEvent.ACTION_CANCEL, h.provider.touches.last().action)
    }

    // ---- Session fencing ----

    @Test
    fun touchWhenDisconnectedIsRejected() {
        val h = Harness()
        assertFalse(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f)))
        assertTrue(h.provider.touches.isEmpty())
        assertEquals(1, h.backend.probeReport().touchDropped)
        assertEquals("stale-touch-ignored", h.backend.probeReport().lastTouchError)
    }

    @Test
    fun touchAfterDetachIsRejected() {
        val h = Harness()
        val token = h.connectAndEstablish()
        h.provider.emit(CarLifeConnectionEvent.Detached(token))
        assertFalse(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f)))
        assertTrue(h.provider.touches.isEmpty())
        assertEquals("stale-touch-ignored", h.backend.probeReport().lastTouchError)
    }

    @Test
    fun lateTouchOfSessionACannotReachSessionB() {
        val h = Harness()
        val tokenA = h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 50f, 50f))
        assertEquals(1, h.provider.touches.size)

        // A dies; B starts but is NOT yet connected.
        h.provider.emit(CarLifeConnectionEvent.Detached(tokenA))
        h.backend.connect(null)
        val tokenB = h.provider.started.last()
        assertNotEquals(tokenA, tokenB)

        // Late event of A's Activity arriving now must NOT be sent into B.
        assertFalse(h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 60f, 60f)))
        assertEquals(1, h.provider.touches.size)
        assertEquals("stale-touch-ignored", h.backend.probeReport().lastTouchError)

        // B's own gesture works once B is connected.
        h.provider.emit(CarLifeConnectionEvent.Established(tokenB))
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 70f, 70f)))
        assertEquals(2, h.provider.touches.size)
        assertEquals(tokenB, h.provider.touches.last().token)
    }

    // ---- Content coordinate model (contentRect mapping, no double scaling) ----

    @Test
    fun touchOutsideContentRectIsRejected() {
        val h = Harness()
        h.connectAndEstablish()
        val rect = ProjectionRect(160f, 90f, 1600f, 900f)
        assertFalse(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 100f, 100f, rect)))
        assertTrue(h.provider.touches.isEmpty())
        assertEquals("touch-outside-content", h.backend.probeReport().lastTouchError)
    }

    @Test
    fun letterboxTouchMapsToContentLocalCoordinates() {
        // screen 1920x1080, contentRect left=160 top=90 width=1600 height=900,
        // touch (960,540) -> content-local (800,450), surface 1600x900.
        val h = Harness()
        h.connectAndEstablish()
        val rect = ProjectionRect(160f, 90f, 1600f, 900f)
        assertTrue(
            h.backend.onTouchEvent(
                touch(ProjectionTouchAction.DOWN, 960f, 540f, rect, screenWidth = 1920, screenHeight = 1080),
            ),
        )
        val sent = h.provider.touches.single()
        assertEquals(800f, sent.x, 0f)
        assertEquals(450f, sent.y, 0f)
        assertEquals(Triple(h.provider.started.last(), 1600, 900), h.provider.surfaces.single())
    }

    @Test
    fun fullScreenTouchIsNotPreScaledToVideoResolution() {
        // content 1792x828 == video 1792x828; touch (896,414) must reach CarLife
        // as (896,414) — NOT normalized, NOT scaled twice.
        val h = Harness()
        h.connectAndEstablish()
        val rect = ProjectionRect(0f, 0f, 1792f, 828f)
        assertTrue(
            h.backend.onTouchEvent(
                touch(ProjectionTouchAction.DOWN, 896f, 414f, rect, screenWidth = 1792, screenHeight = 828),
            ),
        )
        val sent = h.provider.touches.single()
        assertEquals(896f, sent.x, 0f)
        assertEquals(414f, sent.y, 0f)
        assertEquals(Triple(h.provider.started.last(), 1792, 828), h.provider.surfaces.single())
    }

    @Test
    fun localCoordinatesStayLocalWhenVideoSizeDiffersFromContent() {
        // content 1280x720, video 1920x1080 (negotiated phone-side): DiPlay
        // sends (640,360) with surface size 1280x720 and lets the SDK's
        // RemoteControlManager map to (960,540) exactly once. DiPlay must NOT
        // do that conversion itself.
        val h = Harness()
        h.connectAndEstablish()
        val rect = ProjectionRect(0f, 0f, 1280f, 720f)
        assertTrue(
            h.backend.onTouchEvent(
                touch(ProjectionTouchAction.DOWN, 640f, 360f, rect, screenWidth = 1280, screenHeight = 720),
            ),
        )
        val sent = h.provider.touches.single()
        assertEquals(640f, sent.x, 0f)
        assertEquals(360f, sent.y, 0f)
        assertEquals(Triple(h.provider.started.last(), 1280, 720), h.provider.surfaces.single())
    }

    // ---- Surface size propagation ----

    @Test
    fun surfaceSizeIsDeclaredBeforeTheFirstTouch() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        assertEquals(listOf("surface:1920x1080", "touch"), h.provider.log)
    }

    @Test
    fun unchangedSurfaceSizeIsNotResent() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        repeat(5) { h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 10f + it, 10f)) }
        assertEquals(1, h.provider.surfaces.size)
    }

    @Test
    fun surfaceSizeChangeIsPropagated() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        val rect = ProjectionRect(160f, 90f, 1600f, 900f)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 300f, 300f, rect))
        assertEquals(
            listOf(1920 to 1080, 1600 to 900),
            h.provider.surfaces.map { it.second to it.third },
        )
    }

    // ---- Gesture state (downTime) ----

    @Test
    fun downTimeIsStableInsideOneGestureAndRenewedPerGesture() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        h.clock.advance(16)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 20f, 20f))
        h.clock.advance(16)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.UP, 20f, 20f))
        val (down, move, up) = h.provider.touches
        assertEquals(1_000L, down.downTime)
        assertEquals(1_000L, move.downTime) // MOVE reuses the gesture's downTime
        assertEquals(1_000L, up.downTime) // UP reuses it too
        assertTrue(down.eventTime < move.eventTime && move.eventTime < up.eventTime)

        h.clock.advance(16)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 30f, 30f))
        assertEquals(1_048L, h.provider.touches.last().downTime) // fresh gesture
    }

    @Test
    fun disconnectResetsGestureState() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        h.clock.advance(1_000)
        assertTrue(h.backend.disconnect())

        // Reconnect: the previous gesture's downTime must be gone.
        h.connectAndEstablish()
        h.clock.advance(1_000)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 20f, 20f))
        val move = h.provider.touches.last()
        assertEquals("MOVE restarts with a fresh downTime", move.eventTime, move.downTime)
        assertEquals(3_000L, move.downTime)
    }

    @Test
    fun reconnectCannotReceivePreviousSessionGesture() {
        val h = Harness()
        val tokenA = h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        h.clock.advance(500)
        h.provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        val tokenB = h.connectAndEstablish()
        h.clock.advance(500)
        // The gesture captured in session A must not continue into session B.
        assertTrue(h.backend.onTouchEvent(touch(ProjectionTouchAction.UP, 10f, 10f)))
        val up = h.provider.touches.last()
        assertEquals(tokenB, up.token)
        assertEquals(MotionEvent.ACTION_UP, up.action)
        assertEquals(2_000L, up.downTime) // fresh: NOT session A's 1_000L
        assertEquals(2_000L, up.eventTime)
    }

    // ---- Diagnostics ----

    @Test
    fun touchDiagnosticsCountActionsAndSurface() {
        val h = Harness()
        h.connectAndEstablish()
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 20f))
        h.backend.onTouchEvent(touch(ProjectionTouchAction.MOVE, 11f, 21f))
        h.backend.onTouchEvent(touch(ProjectionTouchAction.UP, 11f, 21f))
        h.backend.onTouchEvent(touch(ProjectionTouchAction.CANCEL, 11f, 21f))
        val report = h.backend.probeReport()
        assertEquals("enabled", report.touchState)
        assertEquals(4, report.touchEventsSent)
        assertEquals(1, report.touchDownCount)
        assertEquals(1, report.touchMoveCount)
        assertEquals(1, report.touchUpCount)
        assertEquals(1, report.touchCancelCount)
        assertEquals("CANCEL", report.lastTouchAction)
        assertEquals(11f, report.lastTouchX!!, 0f)
        assertEquals(21f, report.lastTouchY!!, 0f)
        assertEquals(1920, report.touchSurfaceWidth)
        assertEquals(1080, report.touchSurfaceHeight)
        assertEquals(0, report.touchDropped)
    }

    @Test
    fun sendFailureIsReportedAsErrorAndDropped() {
        val h = Harness()
        h.connectAndEstablish()
        h.provider.rejectSends = true
        assertFalse(h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f)))
        val report = h.backend.probeReport()
        assertEquals("error", report.touchState)
        assertEquals(1, report.touchDropped)
        assertEquals("touch-send-rejected", report.lastTouchError)
        assertEquals(0, report.touchEventsSent)
    }

    @Test
    fun touchStateWaitsAgainAfterSessionEnds() {
        val h = Harness()
        val token = h.connectAndEstablish()
        assertEquals("enabled", h.backend.probeReport().touchState)
        h.backend.onTouchEvent(touch(ProjectionTouchAction.DOWN, 10f, 10f))
        h.provider.emit(CarLifeConnectionEvent.Detached(token))
        val report = h.backend.probeReport()
        assertEquals("waiting", report.touchState)
        assertNull(report.touchSurfaceWidth)
        assertNull(report.touchSurfaceHeight)
    }

    companion object {
        private val FULL_SCREEN = ProjectionRect(0f, 0f, 1920f, 1080f)
    }
}
