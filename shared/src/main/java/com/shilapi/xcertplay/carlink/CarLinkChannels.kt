package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink

/**
 * CarLink video channel: forwards neutral [ProjectionVideoConfig]/
 * [ProjectionVideoFrame] objects from the protocol adapter to the session's
 * [ProjectionVideoSink] (typically `ProjectionMediaSinkAdapter` over
 * `AndroidMediaSink`). Codec, size, PTS, keyframe flag and payload windows are
 * preserved as-is — no zeroing, no copying, no string codec guessing here.
 *
 * The sink is bound per session ([bind]/[unbind]); frames outside a session are
 * dropped with a diagnostic instead of being silently swallowed.
 */
class CarLinkVideoChannel(
    private val diagnostics: CarLinkDiagnostics,
) {
    @Volatile
    private var sink: ProjectionVideoSink? = null

    fun bind(sink: ProjectionVideoSink) {
        this.sink = sink
    }

    fun unbind() {
        sink = null
    }

    fun onConfig(config: ProjectionVideoConfig) {
        val target = sink ?: run {
            diagnostics.event("video-config-dropped", "no-session-sink")
            return
        }
        diagnostics.event("video-config", "codec=${config.codec.name} bytes=${config.codecData.size}")
        target.onVideoConfig(config)
    }

    fun onFrame(frame: ProjectionVideoFrame) {
        val target = sink ?: run {
            diagnostics.event("video-frame-dropped", "no-session-sink")
            return
        }
        target.onVideoFrame(frame)
    }

    fun onActive(active: Boolean) {
        diagnostics.event("video-stream", "active=$active")
        sink?.onVideoStreamActive(active)
    }
}

/**
 * CarLink audio channel: explicit per-stream lifecycle ([start] → [frame] →
 * [stop]) mapped onto the neutral [ProjectionAudioSink]. Frames of streams that
 * were never started are dropped with a diagnostic instead of silently.
 *
 * The sink is bound per session ([bind]/[unbind]).
 */
class CarLinkAudioChannel(
    private val diagnostics: CarLinkDiagnostics,
) {
    @Volatile
    private var sink: ProjectionAudioSink? = null

    /** Stream ids currently open; frame delivery requires membership. */
    private val open = java.util.concurrent.ConcurrentHashMap<Int, ProjectionAudioStreamId>()

    fun bind(sink: ProjectionAudioSink) {
        this.sink = sink
    }

    fun unbind() {
        sink = null
        open.clear()
    }

    fun start(format: CarLinkAudioFormat) {
        val target = sink ?: run {
            diagnostics.event("audio-start-dropped", "no-session-sink")
            return
        }
        val id = ProjectionAudioStreamId(format.streamId, format.role)
        open[format.streamId] = id
        target.onAudioStarted(
            id,
            ProjectionAudioFormat(
                codec = format.codec,
                sampleRate = format.sampleRate,
                channels = format.channels,
                channel = format.role,
                payloadType = format.payloadType,
                sampleFormat = format.sampleFormat,
            ),
        )
        diagnostics.event(
            "audio-start",
            "stream=${format.streamId} role=${format.role.name} rate=${format.sampleRate} " +
                "channels=${format.channels} codec=${format.codec.name}",
        )
    }

    fun frame(frame: CarLinkAudioFrame) {
        val target = sink ?: run {
            diagnostics.event("audio-frame-dropped", "no-session-sink")
            return
        }
        val id = open[frame.streamId]
        if (id == null) {
            // Not a started stream: never silently swallow real audio.
            diagnostics.event("audio-frame-unknown-stream", "stream=${frame.streamId}")
            return
        }
        target.onAudioFrame(id, frame.presentationTimeUs, frame.payload, frame.offset, frame.length)
    }

    fun stop(streamId: Int) {
        val id = open.remove(streamId) ?: run {
            diagnostics.event("audio-stop-unknown-stream", "stream=$streamId")
            return
        }
        sink?.onAudioStopped(id)
        diagnostics.event("audio-stop", "stream=$streamId role=${id.channel.name}")
    }

    /** Stops every open stream (session teardown). */
    fun stopAll() {
        for (streamId in open.keys.toList()) stop(streamId)
    }
}

/**
 * CarLink input channel: forwards unified touch/key events to the protocol
 * adapter. Coordinate conversion into protocol space is the adapter's job.
 * Adapter exceptions are swallowed and reported — input never crashes the UI
 * thread.
 */
class CarLinkInputChannel(
    private val adapter: CarLinkProtocolAdapter,
    private val diagnostics: CarLinkDiagnostics,
) {
    fun onTouch(event: ProjectionTouchEvent): Boolean = guarded("sendTouch") {
        adapter.sendTouch(event)
    }

    fun onKey(event: ProjectionKeyEvent): Boolean = guarded("sendKey") {
        adapter.sendKey(event)
    }

    private fun guarded(operation: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Exception) {
        diagnostics.event("input-failed", "op=$operation error=${error.javaClass.simpleName}")
        false
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
