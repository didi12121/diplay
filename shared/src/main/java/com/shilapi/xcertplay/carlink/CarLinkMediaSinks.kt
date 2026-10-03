package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.projection.media.ProjectionMediaSinkAdapter
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import java.io.Closeable

/**
 * Session-scoped pair of media sinks for one CarLink session, plus its
 * lifecycle. Acquired when the protocol session starts and closed when it ends
 * — a session that never started never holds a decoder or an AudioTrack.
 */
class CarLinkMediaSinks(
    val video: ProjectionVideoSink,
    val audio: ProjectionAudioSink,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

/**
 * Supplies the media sinks a CarLink session renders into. This is the seam
 * that wires real sessions into the existing Android media rendering
 * infrastructure (`ProjectionMediaSinkAdapter` → `AndroidMediaSink` →
 * MediaCodec / AudioTrack) without creating a second video pipeline.
 */
fun interface CarLinkMediaSinkProvider {
    /** Acquires sinks for one session; caller [CarLinkMediaSinks.close]s them. */
    fun acquire(): CarLinkMediaSinks
}

/**
 * Production provider: renders CarLink sessions through the same
 * [AndroidMediaSink] pipeline CarPlay uses. One sink instance per session,
 * created on acquire and closed on release, so CarPlay and CarLink lifecycles
 * stay isolated even though the rendering infrastructure is shared.
 */
class AndroidCarLinkMediaSinkProvider(
    /** Optional app context; enables audio focus handling in AndroidMediaSink. */
    private val context: android.content.Context? = null,
    /** Video surface type index (CarPlay uses 110 for its main screen). */
    private val screenType: Int = ProjectionMediaSinkAdapter.DEFAULT_SCREEN_TYPE,
) : CarLinkMediaSinkProvider {

    override fun acquire(): CarLinkMediaSinks {
        val sink = AndroidMediaSink(
            context = context,
        )
        val adapter = ProjectionMediaSinkAdapter(sink, screenType)
        return CarLinkMediaSinks(
            video = adapter,
            audio = adapter,
            onClose = { sink.close() },
        )
    }
}

/**
 * Headless provider for tests and diagnostics harnesses: the caller supplies
 * the sinks and optionally observes their close.
 */
class StaticCarLinkMediaSinkProvider(
    private val video: ProjectionVideoSink,
    private val audio: ProjectionAudioSink,
    private val onClose: () -> Unit = {},
) : CarLinkMediaSinkProvider {
    override fun acquire(): CarLinkMediaSinks = CarLinkMediaSinks(video, audio, onClose)
}
