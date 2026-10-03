package com.shilapi.xcertplay.projection

/**
 * Audio channel roles on the shared media layer.
 *
 * Names are protocol neutral: CarPlay maps its `media`/`telephony`/
 * `speechrecognition`/`alert` stream types here, and Android phone protocols
 * (CarLink, CarLife, HiCar...) map their music/navigation/call/assistant
 * streams onto the same values.
 */
enum class ProjectionAudioChannel {
    MEDIA,
    NAVIGATION,
    PHONE_CALL,
    VOICE_ASSISTANT,
}

/** Encoded audio codecs the shared media layer can describe. */
enum class ProjectionAudioCodec {
    AAC_LC,
    OPUS,
    LPCM,
}

/** Stream format of one audio stream. */
class ProjectionAudioFormat(
    val codec: ProjectionAudioCodec,
    val sampleRate: Int,
    val channels: Int,
    /** Routing role of this stream. */
    val channel: ProjectionAudioChannel,
    /** Backend wire payload type, kept for diagnostics and mapping tables. */
    val payloadType: Int = 0,
    /** Backend wire type name (e.g. CarPlay `audioType`), for mapping tables only. */
    val wireType: String = "",
)

/** Identity of one audio stream inside a session. */
class ProjectionAudioStreamId(
    /** Backend stream number; opaque. */
    val stream: Int,
    val channel: ProjectionAudioChannel,
)

/**
 * Destination for decoded audio of any projection backend.
 *
 * Frames carry either raw PCM or an encoded access unit exactly as the backend
 * delivered it; the sink decides how to render. No buffer is copied on the way in.
 */
interface ProjectionAudioSink {
    fun onAudioStarted(id: ProjectionAudioStreamId, format: ProjectionAudioFormat) {}

    /** One audio payload; [presentationTimeUs] is monotonic per stream. */
    fun onAudioFrame(
        id: ProjectionAudioStreamId,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ) {
    }

    fun onAudioStopped(id: ProjectionAudioStreamId) {}

    companion object {
        /** Discards everything. */
        val NOOP: ProjectionAudioSink = object : ProjectionAudioSink {}
    }
}
