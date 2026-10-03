package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink

/**
 * CarLink video channel: converts adapter video callbacks into neutral
 * [ProjectionVideoFrame]s and forwards them to a [ProjectionVideoSink]
 * (typically `ProjectionMediaSinkAdapter` over `AndroidMediaSink`).
 */
class CarLinkVideoChannel(
    private val sink: ProjectionVideoSink,
    private val diagnostics: CarLinkDiagnostics,
) {
    fun onConfig(codecName: String, codecData: ByteArray) {
        val codec = codecOf(codecName)
        diagnostics.event("video-config", "codec=${codec.name} bytes=${codecData.size}")
        sink.onVideoConfig(ProjectionVideoConfig(codec, codecData))
    }

    fun onFrame(codecName: String, keyFrame: Boolean, payload: ByteArray, offset: Int, length: Int) {
        sink.onVideoFrame(
            ProjectionVideoFrame(
                codec = codecOf(codecName),
                width = 0,
                height = 0,
                presentationTimeUs = 0L,
                keyFrame = keyFrame,
                payload = payload,
                offset = offset,
                length = length,
            ),
        )
    }

    fun onActive(active: Boolean) {
        diagnostics.event("video-stream", "active=$active")
        sink.onVideoStreamActive(active)
    }

    private fun codecOf(name: String): ProjectionVideoCodec =
        if (name.equals("h265", true) || name.equals("hevc", true)) ProjectionVideoCodec.H265
        else ProjectionVideoCodec.H264
}

/**
 * CarLink audio channel: converts adapter audio callbacks into neutral audio
 * frames. Channel roles come from the protocol; wire types stay in metadata
 * only so nothing Apple-specific leaks into the shared layer.
 */
class CarLinkAudioChannel(
    private val sink: ProjectionAudioSink,
    private val diagnostics: CarLinkDiagnostics,
    private val streamId: Int = 1,
) {
    private var open = false

    fun start(codec: ProjectionAudioCodec, sampleRate: Int, channels: Int, role: ProjectionAudioChannel) {
        val id = ProjectionAudioStreamId(streamId, role)
        sink.onAudioStarted(
            id,
            ProjectionAudioFormat(codec, sampleRate, channels, role),
        )
        open = true
        diagnostics.event("audio-start", "role=${role.name} rate=$sampleRate channels=$channels")
    }

    fun frame(role: ProjectionAudioChannel, presentationTimeUs: Long, payload: ByteArray) {
        if (!open) return
        sink.onAudioFrame(ProjectionAudioStreamId(streamId, role), presentationTimeUs, payload)
    }

    fun stop(role: ProjectionAudioChannel) {
        if (!open) return
        open = false
        sink.onAudioStopped(ProjectionAudioStreamId(streamId, role))
        diagnostics.event("audio-stop", "role=${role.name}")
    }
}

/**
 * CarLink input channel: forwards unified touch/key events to the protocol
 * adapter. Coordinate conversion into protocol space is the adapter's job.
 */
class CarLinkInputChannel(
    private val adapter: CarLinkProtocolAdapter,
) {
    fun onTouch(event: ProjectionTouchEvent): Boolean {
        adapter.sendTouch(event)
        return true
    }

    fun onKey(event: ProjectionKeyEvent): Boolean {
        adapter.sendKey(event)
        return true
    }
}

/** Small holder for metadata events so callers can observe them uniformly. */
class CarLinkMetadataChannel {
    @Volatile
    var last: ProjectionMetadata? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ProjectionMetadata) -> Unit>()

    fun publish(metadata: ProjectionMetadata) {
        last = metadata
        for (listener in listeners) listener(metadata)
    }

    fun addListener(listener: (ProjectionMetadata) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (ProjectionMetadata) -> Unit) {
        listeners.remove(listener)
    }
}
