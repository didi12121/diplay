package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Protocol-neutral rendering entry points of the shared Android media
 * pipeline. Unlike [com.shilapi.xcertplay.airplay.MediaSink] (which speaks the
 * CarPlay/AirPlay stream vocabulary), this surface consumes plain access
 * units:
 *
 * ```
 *                    Android Audio/Video Renderer   (this surface)
 *                               ↑
 *             AudioAccessUnit / video access unit
 *                               ↑
 *            ┌──────────────────┴──────────────────┐
 *      CarPlay RTP adapter                  CarLink raw frames
 *   (AirPlayAudioAdapter strips RTP)     (delivered as-is)
 * ```
 *
 * Video configuration carries real codec + coded size, frames carry their
 * real presentation timestamps; audio carries raw PCM/encoded units with an
 * explicit PCM byte order. Implementations: [AndroidMediaSink].
 */
interface AccessUnitMediaSink {

    // ---- Screen output ----

    /** Binds the decoder output for [streamId] to a rendering surface. */
    fun attachScreenSurface(streamId: Int, surface: Surface)

    /**
     * Unbinds the output surface of [streamId]. The decoder must stop
     * outputting to the dead surface; the stream itself may continue and
     * re-attach later.
     */
    fun detachScreenSurface(streamId: Int)

    /** Reports whether the screen stream is actively delivering frames. */
    fun setScreenStreamActive(streamId: Int, active: Boolean)

    // ---- Video ----

    /**
     * Configures the video decoder for [streamId]. [width]/[height] are the
     * real coded dimensions (0 = unknown, fall back to the sink defaults) —
     * never assume 1280x720 for an arbitrary protocol.
     */
    fun configureVideoStream(
        streamId: Int,
        codec: VideoCodec,
        codecData: ByteArray,
        width: Int,
        height: Int,
    )

    /**
     * Submits one encoded access unit. [presentationTimeUs] is propagated to
     * `MediaCodec.queueInputBuffer`; pass [AudioAccessUnit.PTS_UNSPECIFIED]
     * when the protocol has no timestamps and the renderer falls back to a
     * monotonic clock.
     */
    fun submitVideoAccessUnit(
        streamId: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
    )

    // ---- Audio ----

    /** Opens an audio stream with an explicit PCM byte order. */
    fun startAudioAccessUnitStream(
        streamId: AudioStreamId,
        format: AudioFormat,
        pcm: PcmEncoding,
        firstSample: Int,
    )

    /** Feeds one raw access unit of an open stream; PTS is preserved. */
    fun submitAudioAccessUnit(streamId: AudioStreamId, unit: AudioAccessUnit)

    fun stopAudioAccessUnitStream(streamId: AudioStreamId)
}
