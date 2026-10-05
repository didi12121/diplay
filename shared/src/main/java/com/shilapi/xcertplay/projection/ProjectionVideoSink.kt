package com.shilapi.xcertplay.projection

/**
 * One encoded video access unit on the shared media layer.
 *
 * The frame borrows a buffer window ([payload] plus [offset]/[length]) instead of
 * copying bytes, so hot decode paths can hand the same array straight to
 * MediaCodec. Implementations must not retain the payload beyond the sink call
 * unless they copy it themselves.
 */
class ProjectionVideoFrame(
    /** Codec of this access unit. */
    val codec: ProjectionVideoCodec,
    /** Coded picture width in pixels; 0 when the stream did not report it. */
    val width: Int,
    /** Coded picture height in pixels; 0 when the stream did not report it. */
    val height: Int,
    /** Presentation timestamp in microseconds; monotonic per stream. */
    val presentationTimeUs: Long,
    /** True when this access unit starts an independently decodable unit. */
    val keyFrame: Boolean,
    /** Encoded payload buffer (Annex-B access unit or equivalent). */
    val payload: ByteArray,
    /** First valid byte inside [payload]. */
    val offset: Int = 0,
    /** Number of valid bytes inside [payload]. */
    val length: Int = payload.size - offset,
)

/** Codecs the shared media layer can describe. */
enum class ProjectionVideoCodec {
    H264,
    H265,
    ;

    companion object {
        /** Maps the CarPlay [com.shilapi.xcertplay.airplay.VideoCodec] name without importing it. */
        fun fromAirPlayName(name: String): ProjectionVideoCodec =
            if (name.equals("H265", ignoreCase = true)) H265 else H264
    }
}

/** Out-of-band decoder configuration (SPS/PPS or VPS/SPS/PPS record). */
class ProjectionVideoConfig(
    val codec: ProjectionVideoCodec,
    /** Codec-specific configuration record (e.g. avcC/hvcC contents). */
    val codecData: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Destination for decoded video of any projection backend.
 *
 * CarPlay feeds this from its AirPlay screen stream; CarLink feeds the same
 * interface from its video channel. Both end up in AndroidMediaSink.
 */
interface ProjectionVideoSink {
    /** Stream format became known or changed. */
    fun onVideoConfig(config: ProjectionVideoConfig) {}

    /** One encoded access unit. */
    fun onVideoFrame(frame: ProjectionVideoFrame) {}

    /** The screen stream started or stopped. */
    fun onVideoStreamActive(active: Boolean) {}

    companion object {
        /** Discards everything. */
        val NOOP: ProjectionVideoSink = object : ProjectionVideoSink {}
    }
}
