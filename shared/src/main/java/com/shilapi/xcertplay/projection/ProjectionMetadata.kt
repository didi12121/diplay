package com.shilapi.xcertplay.projection

/** Generic now-playing / navigation metadata shared across backends. */
class ProjectionMetadata(
    /** Track title, or null when unknown. */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Artwork bytes, or null; callers must not retain without copying. */
    val artwork: ByteArray? = null,
    /** True while media is actively playing. */
    val playing: Boolean = false,
    /** Navigation: distance to the next maneuver in meters, or null. */
    val maneuverDistanceMeters: Int? = null,
    /** Navigation: free-form next maneuver text, or null. */
    val maneuverText: String? = null,
    /** Navigation: street/road name, or null. */
    val street: String? = null,
) {
    override fun toString(): String =
        "ProjectionMetadata(title=$title, artist=$artist, album=$album, " +
            "artwork=${artwork?.size ?: 0}B, playing=$playing, " +
            "maneuverDistanceMeters=$maneuverDistanceMeters, maneuverText=$maneuverText, " +
            "street=$street)"
}
