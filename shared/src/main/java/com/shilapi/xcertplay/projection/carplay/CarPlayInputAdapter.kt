package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.projection.ProjectionDisplayGeometry
import com.shilapi.xcertplay.projection.ProjectionRect
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTouchPointer

/**
 * Translates unified projection touch events into CarPlay HID contacts.
 *
 * The math mirrors `CarPlayTouchMapper` exactly: contacts are normalized to
 * 0..1 inside the content rectangle, the first two contacts are kept (the CarPlay
 * touch HID descriptor has two finger slots) and contact ids are finger slot
 * indices. See `CarPlayTouchMapperEquivalenceTest`.
 */
object CarPlayInputAdapter {
    /** CarPlay touch HID descriptor reports two finger slots. */
    const val MAX_CONTACTS = 2

    fun contacts(event: ProjectionTouchEvent): List<AirPlayContact> {
        val content = event.geometry.contentRect
        val width = content.width.coerceAtLeast(1f)
        val height = content.height.coerceAtLeast(1f)
        val pointers = event.pointers
        val count = minOf(MAX_CONTACTS, pointers.size)
        val contacts = ArrayList<AirPlayContact>(count)
        for (index in 0 until count) {
            val pointer = pointers[index]
            contacts.add(
                AirPlayContact(
                    id = pointer.id,
                    x = normalize(pointer.x - content.left, width),
                    y = normalize(pointer.y - content.top, height),
                    down = pointer.down,
                ),
            )
        }
        return contacts
    }

    /** Same arithmetic as CarPlayTouchMapper: subtract in Float, divide in Double, clamp. */
    private fun normalize(delta: Float, span: Float): Double =
        (delta.toDouble() / span).coerceIn(0.0, 1.0)
}

/** Convenience constructors for unified touch events used by CarPlay callers. */
object CarPlayTouchEvents {
    /** Lifts every contact, like sending an empty contact list to the HID report. */
    fun liftAll(geometry: ProjectionDisplayGeometry): ProjectionTouchEvent =
        ProjectionTouchEvent(
            action = com.shilapi.xcertplay.projection.ProjectionTouchAction.UP,
            pointers = emptyList(),
            geometry = geometry,
        )

    /** Builds a full-bleed geometry for callers that only know a view size. */
    fun fullBleed(viewWidth: Int, viewHeight: Int): ProjectionDisplayGeometry =
        ProjectionDisplayGeometry(
            screenWidth = viewWidth,
            screenHeight = viewHeight,
            contentRect = ProjectionRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()),
        )
}

/** Internal helper keeping pointer construction in one place. */
internal fun touchPointer(index: Int, x: Float, y: Float, down: Boolean): ProjectionTouchPointer =
    ProjectionTouchPointer(id = index, x = x, y = y, down = down)
