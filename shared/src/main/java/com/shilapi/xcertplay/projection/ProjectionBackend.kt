package com.shilapi.xcertplay.projection

import java.io.Closeable

/**
 * One phone-projection protocol behind the unified host surface.
 *
 * DiPlay is a projection host: Apple CarPlay, ICCOA CarLink and future protocols
 * (CarLife+, 亿连, Android Auto, HiCar) are all backends behind this interface.
 *
 * Threading follows the existing DiPlay model (no coroutines in `shared`):
 * implementations own their worker executors, lifecycle methods return quickly,
 * state changes are published through [ProjectionStateStore] and listeners may be
 * called on any thread. Callers on the UI must re-post to the main thread.
 */
interface ProjectionBackend : Closeable {
    /** Stable id, e.g. `carplay`, `carlink`. */
    val id: String

    /** User visible name, e.g. `Apple CarPlay`, `Android CarLink`. */
    val displayName: String

    val capabilities: ProjectionCapabilities

    /** Current lifecycle state. */
    val state: ProjectionState

    /** Shared resources this backend needs while running; used for arbitration. */
    val requiredResources: Set<ProjectionResource>
        get() = emptySet()

    /** Subscribes to state changes; may be called before [initialize]. */
    fun addStateListener(listener: ProjectionStateListener)

    fun removeStateListener(listener: ProjectionStateListener)

    /** Acquires protocol resources (MFi, sockets, SDK handles). Idempotent. */
    fun initialize()

    /** Begins discovery / device polling. Idempotent. */
    fun start()

    /** Stops discovery and ends any session; keeps [initialize] resources. */
    fun stop()

    /** Connects to a specific device, or to the first discovered one when null. */
    fun connect(device: ProjectionDevice?)

    /** Ends the current session and releases session resources. */
    fun disconnect()

    /** Feeds a unified touch event into this backend's input channel. */
    fun onTouchEvent(event: ProjectionTouchEvent): Boolean

    /** Feeds a unified key event into this backend's input channel. */
    fun onKeyEvent(event: ProjectionKeyEvent): Boolean
}
