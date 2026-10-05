package com.shilapi.xcertplay.projection

/** Which kinds of input a backend understands. */
enum class ProjectionInputKind {
    TOUCH,
    MULTI_TOUCH,
    KEY,
    ROTARY,
    STEERING_BUTTON,
}

/**
 * What one backend can do. Used by the UI to grey out impossible actions and by
 * the resource coordinator to reason about shared hardware.
 */
class ProjectionCapabilities(
    val videoCodecs: Set<ProjectionVideoCodec> = emptySet(),
    val audioChannels: Set<ProjectionAudioChannel> = emptySet(),
    val inputKinds: Set<ProjectionInputKind> = emptySet(),
    /** Touch protocol accepts at most this many simultaneous contacts. */
    val maxTouchContacts: Int = 1,
    /** The phone can consume a microphone uplink (Siri / calls). */
    val microphoneUplink: Boolean = false,
    /** Backend reports now-playing metadata. */
    val metadata: Boolean = false,
    /** Backend reports navigation instructions. */
    val navigation: Boolean = false,
) {
    fun supportsVideo(codec: ProjectionVideoCodec): Boolean = codec in videoCodecs

    fun supportsInput(kind: ProjectionInputKind): Boolean = kind in inputKinds
}
