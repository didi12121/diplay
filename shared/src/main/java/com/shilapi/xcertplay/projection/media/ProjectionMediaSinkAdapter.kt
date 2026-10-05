package com.shilapi.xcertplay.projection.media

import com.shilapi.xcertplay.media.AccessUnitMediaSink
import com.shilapi.xcertplay.media.AudioAccessUnit
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink

/**
 * Bridges backend-neutral projection media into the shared Android renderer
 * ([AccessUnitMediaSink] — `AndroidMediaSink` → MediaCodec / AudioTrack).
 *
 * Preservation contract (regression tested):
 *  - video: codec + real coded width/height from [ProjectionVideoConfig] reach
 *    the decoder configuration; [ProjectionVideoFrame.presentationTimeUs]
 *    reaches `queueInputBuffer` (never re-generated);
 *  - audio: raw access units are forwarded as [AudioAccessUnit] payload
 *    windows **without any transport framing** — no RTP header stripping and
 *    never a synthesized one; PCM byte order follows the declared
 *    [ProjectionAudioFormat.sampleFormat];
 *  - buffers are forwarded zero-copy whenever the window spans the whole
 *    payload.
 */
class ProjectionMediaSinkAdapter(
    private val sink: AccessUnitMediaSink,
    /** Screen stream type used for video frames (e.g. 110 main, 111 cluster). */
    private val screenType: Int = DEFAULT_SCREEN_TYPE,
) : ProjectionVideoSink, ProjectionAudioSink {

    // ---- Video ----

    override fun onVideoConfig(config: ProjectionVideoConfig) {
        sink.configureVideoStream(
            screenType,
            ProjectionMediaMapping.toSharedCodec(config.codec),
            config.codecData,
            config.width,
            config.height,
        )
    }

    override fun onVideoFrame(frame: ProjectionVideoFrame) {
        sink.submitVideoAccessUnit(
            screenType,
            frame.payload,
            frame.offset,
            frame.length,
            frame.presentationTimeUs,
        )
    }

    override fun onVideoStreamActive(active: Boolean) {
        sink.setScreenStreamActive(screenType, active)
    }

    // ---- Audio ----

    override fun onAudioStarted(id: ProjectionAudioStreamId, format: ProjectionAudioFormat) {
        sink.startAudioAccessUnitStream(
            ProjectionMediaMapping.toSharedStreamId(id),
            ProjectionMediaMapping.toSharedFormat(format),
            ProjectionMediaMapping.toPcmEncoding(format.sampleFormat),
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
        sink.submitAudioAccessUnit(
            ProjectionMediaMapping.toSharedStreamId(id),
            AudioAccessUnit(payload, offset, length, presentationTimeUs),
        )
    }

    override fun onAudioStopped(id: ProjectionAudioStreamId) {
        sink.stopAudioAccessUnitStream(ProjectionMediaMapping.toSharedStreamId(id))
    }

    companion object {
        const val DEFAULT_SCREEN_TYPE = 110
    }
}
