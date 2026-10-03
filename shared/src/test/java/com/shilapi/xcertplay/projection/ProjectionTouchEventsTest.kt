package com.shilapi.xcertplay.projection

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MotionEvent -> ProjectionTouchEvent conversion semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ProjectionTouchEventsTest {
    private val geometry = ProjectionDisplayGeometry(
        screenWidth = 1920,
        screenHeight = 990,
        contentRect = ProjectionRect(0f, 0f, 1920f, 990f),
    )

    private fun event(action: Int, x: Float = 0f, y: Float = 0f): MotionEvent =
        MotionEvent.obtain(0, 0, action, x, y, 0)

    @Test
    fun downCarriesRawViewCoordinates() {
        val motion = event(MotionEvent.ACTION_DOWN, 123f, 456f)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(ProjectionTouchAction.DOWN, touch.action)
            val pointer = touch.pointers.single()
            assertEquals(123f, pointer.x, 0f)
            assertEquals(456f, pointer.y, 0f)
            assertTrue(pointer.down)
            assertEquals(0, pointer.id)
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun upMarksEveryPointerLifted() {
        val motion = event(MotionEvent.ACTION_UP, 10f, 10f)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(ProjectionTouchAction.UP, touch.action)
            assertFalse(touch.pointers.single().down)
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun cancelAlsoLiftsEveryPointer() {
        val motion = event(MotionEvent.ACTION_CANCEL)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(ProjectionTouchAction.CANCEL, touch.action)
            assertFalse(touch.pointers.single().down)
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun moveKeepsContactsDown() {
        val motion = event(MotionEvent.ACTION_MOVE, 50f, 50f)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(ProjectionTouchAction.MOVE, touch.action)
            assertTrue(touch.pointers.single().down)
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun geometryTravelsWithTheEvent() {
        val motion = event(MotionEvent.ACTION_DOWN)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(1920, touch.geometry.screenWidth)
            assertEquals(990, touch.geometry.screenHeight)
        } finally {
            motion.recycle()
        }
    }
}
