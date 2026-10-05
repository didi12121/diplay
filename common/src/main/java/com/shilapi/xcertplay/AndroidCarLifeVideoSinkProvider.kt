// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.carlife.CarLifeVideoSinkProvider
import com.shilapi.xcertplay.carlife.CarLifeVideoSinkSession
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.projection.display.ProjectionDisplayHost
import com.shilapi.xcertplay.projection.display.ProjectionSurfaceHandle
import com.shilapi.xcertplay.projection.media.ProjectionMediaSinkAdapter

/**
 * Production CarLife video sink: renders CarLife video through the SAME
 * `AndroidMediaSink` pipeline CarPlay/CarLink use (one MediaCodec, one
 * Surface owner — the upstream SDK FrameDecoder stays bypassed). The rendering
 * surface comes from the shared [ProjectionDisplayHost]
 * (ProjectionSessionActivity / SurfaceView), so CarLife, CarLink and CarPlay
 * share the protocol-neutral display host.
 */
class AndroidCarLifeVideoSinkProvider(
    /** UI-owned display host providing the rendering surface. */
    private val display: ProjectionDisplayHost,
    /**
     * Application context — pass `applicationContext`, never an Activity.
     */
    private val context: Context? = null,
    private val screenType: Int = ProjectionMediaSinkAdapter.DEFAULT_SCREEN_TYPE,
) : CarLifeVideoSinkProvider {

    override fun acquire(onDecoderDiagnostic: (String) -> Unit): CarLifeVideoSinkSession {
        val sink = AndroidMediaSink(context = context)
        // Real decoder diagnostics ("first frame rendered", decoder errors)
        // feed the probe panel's render stage.
        sink.setVideoDiagnosticHandler(screenType, onDecoderDiagnostic)
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
        display.addListener(listener)
        return CarLifeVideoSinkSession(adapter) {
            display.removeListener(listener)
            sink.close()
        }
    }
}
