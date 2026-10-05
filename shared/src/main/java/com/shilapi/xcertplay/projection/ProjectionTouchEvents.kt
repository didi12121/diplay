package com.shilapi.xcertplay.projection

import android.view.MotionEvent

/**
 * Builds unified [ProjectionTouchEvent]s from Android [MotionEvent]s.
 *
 * Contact semantics mirror `CarPlayTouchMapper` exactly (finger-slot ids,
 * pointer-up lift handling, all-up on ACTION_UP/CANCEL) so routing touch through
 * the projection layer is behavior preserving; only the coordinate conversion
 * moves into each backend.
 */
object ProjectionTouchEvents {
    fun from(event: MotionEvent, geometry: ProjectionDisplayGeometry): ProjectionTouchEvent {
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val pointers = ArrayList<ProjectionTouchPointer>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            pointers.add(
                ProjectionTouchPointer(
                    // Phase 9.2b: the contact id MUST be the Android pointer id
                    // (stable across DOWN..UP); the slot index is NOT stable
                    // once pointers are lifted out of order.
                    id = event.getPointerId(index),
                    x = event.getX(index),
                    y = event.getY(index),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        val mapped = when (action) {
            MotionEvent.ACTION_UP -> ProjectionTouchAction.UP
            MotionEvent.ACTION_CANCEL -> ProjectionTouchAction.CANCEL
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> ProjectionTouchAction.DOWN
            else -> ProjectionTouchAction.MOVE
        }
        return ProjectionTouchEvent(mapped, pointers, geometry)
    }
}
