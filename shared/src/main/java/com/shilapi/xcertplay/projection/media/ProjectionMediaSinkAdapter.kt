package com.shilapi.xcertplay.projection.media

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink

/**
 * Feeds backend-neutral projection media into the existing shared media layer.
 *
 * Wraps a [MediaSink] — normally `AndroidMediaSink` — behind the neutral
 * [ProjectionVideoSink] / [ProjectionAudioSink] interfaces so a future CarLink
 * (or CarLife/HiCar) video and audio channel renders through exactly the same
 * MediaCodec/AudioTrack pipeline CarPlay already uses.
 *
 * Zero-copy contract: [ProjectionVideoFrame.payload] windows are forwarded to
 * the shared sink as-is when they span the whole buffer and copied only when a
 * sub-range is delivered.
 */
class ProjectionMediaSinkAdapter(
    private val sink: MediaSink,
    /** Screen stream type used for video frames (e.g. 110 main, 111 cluster). */
    private val screenType: Int = DEFAULT_SCREEN_TYPE,
) : ProjectionVideoSink, ProjectionAudioSink {

    private val streamChannels = java.util.concurrent.ConcurrentHashMap<Int, com.shilapi.xcertplay.airplay.AudioFormat>()

    // ---- Video ----

    override fun onVideoConfig(config: ProjectionVideoConfig) {
        sink.onVideoCodec(screenType, ProjectionMediaMapping.toSharedCodec(config.codec))
        sink.onVideoConfig(screenType, config.codecData)
    }

    override fun onVideoFrame(frame: ProjectionVideoFrame) {
        sink.onVideoFrame(screenType, frame.bytes())
    }

    override fun onVideoStreamActive(active: Boolean) {
        sink.onScreenStreamActive(screenType, active)
    }

    // ---- Audio ----

    override fun onAudioStarted(id: ProjectionAudioStreamId, format: ProjectionAudioFormat) {
        val sharedFormat = ProjectionMediaMapping.toSharedFormat(format)
        streamChannels[id.stream] = sharedFormat
        sink.onAudioStarted(
            ProjectionMediaMapping.toSharedStreamId(id),
            sharedFormat,
            firstSample = 0,
        )
    }

    override fun onAudioFrame(
        id: ProjectionAudioStreamId,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val sharedId = ProjectionMediaMapping.toSharedStreamId(id)
        val format = streamChannels[id.stream] ?: return
        val sample = (presentationTimeUs * format.sampleRate / 1_000_000L).toInt()
        sink.onAudioRtp(sharedId, format, window(payload, offset, length), sample)
    }

    override fun onAudioStopped(id: ProjectionAudioStreamId) {
        streamChannels.remove(id.stream)
        sink.onAudioStopped(ProjectionMediaMapping.toSharedStreamId(id))
    }

    private fun ProjectionVideoFrame.bytes(): ByteArray = window(payload, offset, length)

    private fun window(payload: ByteArray, offset: Int, length: Int): ByteArray =
        if (offset == 0 && length == payload.size) payload
        else payload.copyOfRange(offset, offset + length)

    companion object {
        const val DEFAULT_SCREEN_TYPE = 110
    }
}
