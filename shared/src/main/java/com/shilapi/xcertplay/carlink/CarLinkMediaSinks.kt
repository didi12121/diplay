package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import com.shilapi.xcertplay.projection.display.ProjectionDisplayHost
import com.shilapi.xcertplay.projection.display.ProjectionSurfaceHandle
import com.shilapi.xcertplay.projection.media.ProjectionMediaSinkAdapter
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
 *
 * The rendering surface comes from the UI layer through [display] — the
 * protocol stack never sees an Android Surface. When the session acquires
 * sinks mid-life (or after an Activity recreate) the current surface is
 * attached immediately; when the UI reports the surface destroyed the decoder
 * detaches, and the session keeps running for a later re-attach.
 */
class AndroidCarLinkMediaSinkProvider(
    /** UI-owned display host providing the rendering surface. */
    private val display: ProjectionDisplayHost,
    /**
     * Application context — pass `applicationContext`, never an Activity:
     * audio focus and AudioManager routes need a long-lived context and an
     * Activity reference would leak.
     */
    private val context: android.content.Context? = null,
    /** Video surface stream index (CarPlay uses 110 for its main screen). */
    private val screenType: Int = ProjectionMediaSinkAdapter.DEFAULT_SCREEN_TYPE,
) : CarLinkMediaSinkProvider {

    override fun acquire(): CarLinkMediaSinks {
        val sink = AndroidMediaSink(context = context)
        val adapter = ProjectionMediaSinkAdapter(sink, screenType)
        val listener = object : ProjectionDisplayHost.Listener {
            override fun onSurfaceAvailable(handle: ProjectionSurfaceHandle) {
                sink.attachScreenSurface(screenType, handle.surface)
            }

            override fun onSurfaceChanged(handle: ProjectionSurfaceHandle) {
                sink.attachScreenSurface(screenType, handle.surface)
            }

            override fun onSurfaceDestroyed() {
                sink.detachScreenSurface(screenType)
            }
        }
        // Replays the current surface immediately when one exists.
        display.addListener(listener)
        return CarLinkMediaSinks(
            video = adapter,
            audio = adapter,
            onClose = {
                display.removeListener(listener)
                sink.close()
            },
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
