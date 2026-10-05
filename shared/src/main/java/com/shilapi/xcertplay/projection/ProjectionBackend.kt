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
 * state changes are published through `ProjectionStateStore` and listeners may be
 * called on any thread. Callers on the UI must re-post to the main thread.
 *
 * Resource rule: a backend may hold shared hardware (`ProjectionResource`)
 * **only while its underlying session is actually running or stopping has been
 * confirmed**. [disconnect] therefore reports whether the session is already
 * confirmed stopped; externally-owned sessions answer `false` and their host
 * later confirms via `ProjectionManager.onBackendSessionStopped`.
 */
interface ProjectionBackend : Closeable {
    /** Stable id, e.g. `carplay`, `carlink`. */
    val id: String

    /** User visible name, e.g. `Apple CarPlay`, `Android CarLink`. */
    val displayName: String

    val capabilities: ProjectionCapabilities

    /** Current lifecycle state. */
    val state: ProjectionState

    /**
     * True while the backend's underlying protocol session is actually running
     * (or still tearing down). This is the ground truth for resource
     * arbitration: a wrapped, externally-owned session stays `true` until its
     * host really stopped it.
     */
    val isSessionActive: Boolean

    /** Default shared resources this backend needs; see [requiredResourcesFor]. */
    val requiredResources: Set<ProjectionResource>
        get() = emptySet()

    /**
     * Shared resources needed when connecting to [device]. Defaults to
     * [requiredResources]; backends with per-session negotiation (e.g. USB vs
     * wireless CarLink) override this. `null` means "no known target" and
     * should claim nothing speculative.
     */
    fun requiredResourcesFor(device: ProjectionDevice?): Set<ProjectionResource> =
        requiredResources

    /**
     * Resolves which device a `connect(requested)` would actually talk to, so
     * the manager can claim resources for the SAME device the backend will
     * really connect (never "manager thinks UNKNOWN, backend picks USB").
     *
     * Default: the requested device as-is. Backends whose connect(null) picks a
     * discovered device must override this and return that device here too.
     * Returning null means there is nothing to connect to right now.
     */
    fun resolveConnectDevice(requested: ProjectionDevice?): ProjectionDevice? = requested

    /** Devices discovery found so far; used by AUTO selection and connect(null). */
    fun discoveredDevices(): List<ProjectionDevice> = emptyList()

    /**
     * Registered by `ProjectionManager`: implementations invoke it once their
     * underlying session has **actually** stopped after an asynchronous
     * teardown (e.g. a wrapped, host-owned session). Backends whose
     * [disconnect] already confirms the stop synchronously may leave this
     * unused.
     */
    fun setSessionStoppedListener(listener: Runnable?) {}

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

    /**
     * Ends the current session. Returns **true** when the underlying session is
     * confirmed stopped and shared resources may be released immediately.
     * Returns **false** when teardown completes asynchronously (externally-owned
     * sessions): resources must stay claimed until the owner confirms via
     * `ProjectionManager.onBackendSessionStopped(id)`.
     */
    fun disconnect(): Boolean

    /** Feeds a unified touch event into this backend's input channel. */
    fun onTouchEvent(event: ProjectionTouchEvent): Boolean

    /** Feeds a unified key event into this backend's input channel. */
    fun onKeyEvent(event: ProjectionKeyEvent): Boolean
}
