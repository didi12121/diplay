package com.shilapi.xcertplay.projection.display

import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One rendering surface plus the window metrics the UI reported with it.
 * The [Surface] is an Android SDK type on purpose: it is rendering
 * infrastructure, not a View. No Activity, SurfaceView or TextureView ever
 * crosses into the protocol core — the UI layer pushes surfaces in here.
 */
class ProjectionSurfaceHandle(
    val surface: Surface,
    val width: Int,
    val height: Int,
)

/**
 * Protocol-neutral display seam between the Android UI layer and the media
 * renderers.
 *
 * ```
 * ProjectionSessionActivity / SurfaceView / TextureView   (UI layer)
 *            │ push Surface events
 *            ▼
 * ProjectionDisplayHost            (this package)
 *            │ Listener callbacks
 *            ▼
 * CarLinkMediaSinkProvider → AndroidMediaSink → MediaCodec
 * ```
 *
 * Renderers subscribe as [Listener]s; the UI feeds [ProjectionSurfaceHost]
 * (or its own implementation) from SurfaceHolder/TextureView callbacks.
 * Lifecycle contract:
 *  - `available`/`changed` deliver the current surface — a late subscriber
 *    immediately receives the surface that already exists (Activity recreate
 *    while a session is alive re-attaches without dropping the session);
 *  - `destroyed` means the surface is gone — renderers must detach so no
 *    MediaCodec keeps outputting to a dead surface.
 */
interface ProjectionDisplayHost {
    /** The surface currently pushed by the UI layer, or null. */
    val currentSurface: ProjectionSurfaceHandle?

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    interface Listener {
        /** A usable surface exists (created or re-created). */
        fun onSurfaceAvailable(handle: ProjectionSurfaceHandle)

        /** Same surface, new size/geometry. */
        fun onSurfaceChanged(handle: ProjectionSurfaceHandle)

        /** The surface is gone; renderers must detach. */
        fun onSurfaceDestroyed()
    }
}

/**
 * UI-side [ProjectionDisplayHost]: a SurfaceHolder.Callback (or TextureView
 * listener) forwards its events here and listeners are notified on the caller
 * thread. [addListener] replays the current surface to late subscribers so a
 * renderer created mid-session (or after an Activity recreate) attaches
 * immediately.
 */
class ProjectionSurfaceHost : ProjectionDisplayHost {

    private val listeners = CopyOnWriteArrayList<ProjectionDisplayHost.Listener>()

    @Volatile
    private var current: ProjectionSurfaceHandle? = null

    override val currentSurface: ProjectionSurfaceHandle?
        get() = current

    override fun addListener(listener: ProjectionDisplayHost.Listener) {
        listeners.addIfAbsent(listener)
        val existing = current ?: return
        runCatching { listener.onSurfaceAvailable(existing) }
    }

    override fun removeListener(listener: ProjectionDisplayHost.Listener) {
        listeners.remove(listener)
    }

    /** SurfaceHolder.Callback: surfaceCreated. */
    fun surfaceAvailable(surface: Surface, width: Int, height: Int) {
        val handle = ProjectionSurfaceHandle(surface, width, height)
        current = handle
        dispatch { it.onSurfaceAvailable(handle) }
    }

    /** SurfaceHolder.Callback: surfaceChanged. */
    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        val handle = ProjectionSurfaceHandle(surface, width, height)
        current = handle
        dispatch { it.onSurfaceChanged(handle) }
    }

    /** SurfaceHolder.Callback: surfaceDestroyed. */
    fun surfaceDestroyed() {
        current = null
        dispatch { it.onSurfaceDestroyed() }
    }

    private fun dispatch(block: (ProjectionDisplayHost.Listener) -> Unit) {
        for (listener in listeners) {
            runCatching { block(listener) }
        }
    }
}
