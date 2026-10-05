// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 9.2b: REAL [CarLifeV2Provider] touch-boundary tests (not FakeProvider).
 *
 * Properties under test:
 *  - touch is session-scoped: only the current ARMED attempt's token may send;
 *    a stale token is rejected and never reaches the captured receiver
 *  - the surface size reaches `onSurfaceSizeChanged` before/with the first
 *    touch and is never resent unchanged (per-attempt cache, cleared with the
 *    attempt)
 *  - sends stay on the high-level facade (`onTouchEvent`); no protocol
 *    internals are touched by DiPlay code
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeV2ProviderTouchTest {

    /** Scripted SDK facade: records surface-size and touch calls. */
    private class ScriptedSdk : CarLifeReceiverFacade {
        val calls = mutableListOf<String>()
        val surfaceSizes = mutableListOf<Pair<Int, Int>>()
        val touches = mutableListOf<Triple<Int, Float, Float>>() // action, x, y

        override fun addConnectionListener(listener: com.baidu.carlife.sdk.ConnectionChangeListener) {}
        override fun removeConnectionListener(listener: com.baidu.carlife.sdk.ConnectionChangeListener) {}
        override fun addProgressListener(listener: com.baidu.carlife.sdk.receiver.ConnectProgressListener) {}
        override fun removeProgressListener(listener: com.baidu.carlife.sdk.receiver.ConnectProgressListener) {}
        override fun connect() {}
        override fun stopConnect() {}
        override fun shutdown() {}
        override fun connectionState(): Int = 3
        override fun protocolVersion(): Int = 4
        override fun carlifeVersion(): Int = 0
        override fun usbDeviceSummaries(): List<String> = emptyList()

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            calls.add("surface:${width}x$height")
            surfaceSizes.add(width to height)
        }

        override fun onTouchEvent(event: MotionEvent) {
            calls.add("touch")
            touches.add(Triple(event.action, event.x, event.y))
        }
    }

    private class Harness {
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> sdk }

        init {
            provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        }

        fun event(action: Int = MotionEvent.ACTION_DOWN, x: Float = 10f, y: Float = 20f): MotionEvent =
            MotionEvent.obtain(0L, 0L, action, x, y, 0)
    }

    @Test
    fun sendTouchRejectsStaleToken() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        h.provider.startConnection(tokenA) { }
        val stale = CarLifeSessionToken(999)
        val motion = h.event()
        try {
            h.provider.updateTouchSurface(stale, 1280, 720)
            assertFalse(h.provider.sendTouch(stale, motion))
        } finally {
            motion.recycle()
        }
        // Nothing reached the captured receiver.
        assertTrue(h.sdk.calls.isEmpty())
    }

    @Test
    fun sendTouchRejectsAfterAttemptStopped() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        h.provider.startConnection(tokenA) { }
        h.provider.updateTouchSurface(tokenA, 1280, 720)
        h.provider.stopConnection(tokenA)
        val motion = h.event()
        try {
            assertFalse(h.provider.sendTouch(tokenA, motion))
        } finally {
            motion.recycle()
        }
        // Only the earlier surface declaration reached the receiver.
        assertEquals(listOf("surface:1280x720"), h.sdk.calls)
    }

    @Test
    fun sendTouchRejectsTokenOfSupersededAttempt() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val tokenB = CarLifeSessionToken(2)
        h.provider.startConnection(tokenA) { }
        h.provider.updateTouchSurface(tokenA, 1280, 720)
        h.provider.startConnection(tokenB) { } // supersedes A
        val motion = h.event()
        try {
            // A is superseded — its token can no longer touch B's session.
            assertFalse(h.provider.sendTouch(tokenA, motion))
            // B declared no surface yet, so nothing is sent for it either.
            assertFalse(h.provider.sendTouch(tokenB, motion))
        } finally {
            motion.recycle()
        }
        assertTrue(h.sdk.touches.isEmpty())
    }

    @Test
    fun surfaceSizeIsSentBeforeFirstTouch() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token) { }
        h.provider.updateTouchSurface(token, 1280, 720)
        val motion = h.event()
        try {
            assertTrue(h.provider.sendTouch(token, motion))
        } finally {
            motion.recycle()
        }
        assertEquals(listOf("surface:1280x720", "touch"), h.sdk.calls)
    }

    @Test
    fun sendTouchWithoutDeclaredSurfaceIsRejected() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token) { }
        val motion = h.event()
        try {
            assertFalse(h.provider.sendTouch(token, motion))
        } finally {
            motion.recycle()
        }
        assertTrue(h.sdk.calls.isEmpty())
    }

    @Test
    fun unchangedSurfaceSizeIsNotResentToReceiver() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token) { }
        h.provider.updateTouchSurface(token, 1280, 720)
        h.provider.updateTouchSurface(token, 1280, 720)
        repeat(3) {
            val motion = h.event(MotionEvent.ACTION_MOVE)
            try {
                h.provider.sendTouch(token, motion)
            } finally {
                motion.recycle()
            }
        }
        assertEquals(listOf(1280 to 720), h.sdk.surfaceSizes)
        assertEquals(3, h.sdk.touches.size)
    }

    @Test
    fun surfaceSizeChangeIsPropagatedToReceiver() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token) { }
        h.provider.updateTouchSurface(token, 1280, 720)
        h.provider.updateTouchSurface(token, 1600, 900)
        assertEquals(listOf(1280 to 720, 1600 to 900), h.sdk.surfaceSizes)
    }

    @Test
    fun surfaceSizeCacheIsPerAttempt() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val tokenB = CarLifeSessionToken(2)
        h.provider.startConnection(tokenA) { }
        h.provider.updateTouchSurface(tokenA, 1280, 720)
        h.provider.stopConnection(tokenA)
        h.provider.startConnection(tokenB) { }
        // New attempt = fresh cache: the same size must be declared again.
        h.provider.updateTouchSurface(tokenB, 1280, 720)
        assertEquals(listOf(1280 to 720, 1280 to 720), h.sdk.surfaceSizes)
    }

    @Test
    fun surfaceSizeIsSentAgainWithFirstTouchOfNewSession() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val tokenB = CarLifeSessionToken(2)
        h.provider.startConnection(tokenA) { }
        h.provider.updateTouchSurface(tokenA, 1280, 720)
        val first = h.event()
        try {
            assertTrue(h.provider.sendTouch(tokenA, first))
        } finally {
            first.recycle()
        }
        h.provider.stopConnection(tokenA)
        h.provider.startConnection(tokenB) { }
        h.provider.updateTouchSurface(tokenB, 1280, 720)
        val second = h.event(MotionEvent.ACTION_MOVE, 640f, 360f)
        try {
            assertTrue(h.provider.sendTouch(tokenB, second))
        } finally {
            second.recycle()
        }
        assertEquals(
            listOf(
                "surface:1280x720", "touch",
                "surface:1280x720", "touch",
            ),
            h.sdk.calls,
        )
    }

    @Test
    fun touchCoordinatesReachReceiverUnchanged() {
        // The provider is a pure seam: no coordinate conversion happens here.
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token) { }
        h.provider.updateTouchSurface(token, 1600, 900)
        val motion = h.event(MotionEvent.ACTION_DOWN, 800f, 450f)
        try {
            assertTrue(h.provider.sendTouch(token, motion))
        } finally {
            motion.recycle()
        }
        assertEquals(listOf(Triple(MotionEvent.ACTION_DOWN, 800f, 450f)), h.sdk.touches)
    }
}
