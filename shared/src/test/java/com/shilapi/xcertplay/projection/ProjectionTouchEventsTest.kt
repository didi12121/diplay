package com.shilapi.xcertplay.projection

import android.view.InputDevice
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

    // ---- Phase 9.2b: stable pointer identity ----

    private fun multiPointerEvent(action: Int, actionIndex: Int = 0): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 5; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 3; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 20f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 30f; y = 40f; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            0L, 0L,
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    /** Regression: ProjectionTouchPointer.id is the Android pointer id
     *  (MotionEvent.getPointerId), NOT the raw slot index — the contract
     *  promises a stable id across DOWN..UP. */
    @Test
    fun pointerIdsAreAndroidPointerIdsNotSlotIndices() {
        val motion = multiPointerEvent(MotionEvent.ACTION_MOVE)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(listOf(5, 3), touch.pointers.map { it.id })
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun pointerIdsStayStableWhenAnotherPointerLifts() {
        // Pointer id 3 lifts (slot 1): the surviving contact keeps id 5 even
        // though its slot index would shift later.
        val motion = multiPointerEvent(MotionEvent.ACTION_POINTER_UP, actionIndex = 1)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(listOf(5, 3), touch.pointers.map { it.id })
            assertTrue(touch.pointers[0].down)
            assertFalse(touch.pointers[1].down)
        } finally {
            motion.recycle()
        }
    }

    @Test
    fun singleTouchKeepsPointerIdZero() {
        val motion = event(MotionEvent.ACTION_DOWN, 123f, 456f)
        try {
            val touch = ProjectionTouchEvents.from(motion, geometry)
            assertEquals(0, touch.pointers.single().id)
        } finally {
            motion.recycle()
        }
    }
}
