package com.shilapi.xcertplay.projection

/**
 * Display geometry a backend needs to translate host touch coordinates into
 * protocol coordinates. Nothing here is hard-coded: the head unit reports its
 * screen, the letterboxed content rect, rotation and safe area.
 */
class ProjectionDisplayGeometry(
    /** Full screen width in pixels. */
    val screenWidth: Int,
    /** Full screen height in pixels. */
    val screenHeight: Int,
    /** Letterboxed content rectangle inside the view (pixels). */
    val contentRect: ProjectionRect,
    /** Surface rotation in degrees (0/90/180/270). */
    val rotation: Int = 0,
    /** Driver-visible safe area inside the content rect, when known. */
    val safeArea: ProjectionRect? = null,
)

/** Axis-aligned rectangle in pixels. */
class ProjectionRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height
}

/** Touch phase of a [ProjectionTouchEvent]. */
enum class ProjectionTouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

/** One finger contact in view pixel coordinates. */
class ProjectionTouchPointer(
    /** Stable contact id across DOWN..UP. */
    val id: Int,
    /** X in view pixels. */
    val x: Float,
    /** Y in view pixels. */
    val y: Float,
    /** Whether this contact is currently pressed. */
    val down: Boolean,
)

/**
 * Unified touch event fed to whichever backend is active.
 *
 * Coordinates are raw view pixels plus the [ProjectionDisplayGeometry] needed to
 * convert them; each backend performs its own protocol coordinate mapping
 * (CarPlay normalizes to 0..1 inside the content rect, CarLink will map into its
 * own display space).
 */
class ProjectionTouchEvent(
    val action: ProjectionTouchAction,
    val pointers: List<ProjectionTouchPointer>,
    val geometry: ProjectionDisplayGeometry,
)

/** Unified key events; rotary and steering codes are reserved for later phases. */
enum class ProjectionKeyAction {
    DOWN,
    UP,
}

enum class ProjectionKeyCode {
    VOICE_ASSISTANT,
    MEDIA_PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    HOME,
    BACK,
    SELECT,
    /** Reserved for rotary controllers. */
    ROTARY,
    /** Reserved for steering wheel buttons. */
    STEERING_BUTTON,
}

class ProjectionKeyEvent(
    val action: ProjectionKeyAction,
    val code: ProjectionKeyCode,
    /** Backend-specific sub-code (e.g. CarPlay HID media index); 0 when unused. */
    val detail: Int = 0,
)

/**
 * Destination for input flowing *to* the phone. Backends implement this to
 * forward unified events into their protocol input channel (CarPlay HID,
 * CarLink input protocol, ...).
 */
interface ProjectionInputSink {
    fun onTouchEvent(event: ProjectionTouchEvent): Boolean = false

    fun onKeyEvent(event: ProjectionKeyEvent): Boolean = false
}
