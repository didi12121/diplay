// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Schedules the probe failure-classification timeout. The timeout ONLY
 * classifies why a probe never progressed — it never fabricates session
 * identity, connection state or success, and it never retries/bypasses auth.
 */
fun interface CarLifeProbeTimeoutScheduler {
    /** Returns a handle whose `close()` cancels the timeout. */
    fun schedule(timeoutMillis: Long, onTimeout: () -> Unit): AutoCloseable
}

/** Default scheduler: one daemon timer thread. */
object SystemCarLifeProbeTimeoutScheduler : CarLifeProbeTimeoutScheduler {
    override fun schedule(timeoutMillis: Long, onTimeout: () -> Unit): AutoCloseable {
        val timer = java.util.Timer("carlife-probe-timeout", true)
        val task = object : java.util.TimerTask() {
            override fun run() {
                runCatching { onTimeout() }
            }
        }
        timer.schedule(task, timeoutMillis)
        return AutoCloseable {
            task.cancel()
            timer.cancel()
        }
    }
}

/**
 * Third DiPlay projection backend: open CarLife (Baidu CarLife+) over USB AOA,
 * built on the Apache-2.0 CarLife V2.0 SDK imported from Apollo-DuerOS.
 *
 * Phase 9.1 scope — **Compatibility Probe only** (no media wiring).
 *
 * State mapping (never reports Connected early):
 * ```
 * AOA / protocol negotiating      -> Connecting
 * CONNECTION_ESTABLISHED          -> Connected (exactly once)
 * version rejected                -> Error (PROTOCOL_VERSION)
 * auth/channel failed             -> Error (AUTHENTICATION_FAILED)
 * disconnect in progress          -> Disconnecting
 * detached                        -> Ready
 * ```
 *
 * Diagnostics come from the REAL SDK (ConnectProgressListener progress,
 * protocol/carlife versions, VID:PID-only USB summaries). Progress numbers are
 * diagnostic only — success is exclusively `CONNECTION_ESTABLISHED`.
 *
 * Resource lease: every terminal outcome (detach, version reject, auth
 * failure, transport failure) confirms the session stopped via the manager's
 * session-stopped listener, so USB/AUDIO leases are released immediately —
 * never left to the next connect's stale-claim sweep.
 */
class CarLifeProjectionBackend(
    private val provider: CarLifeProvider,
    private val timeoutScheduler: CarLifeProbeTimeoutScheduler = SystemCarLifeProbeTimeoutScheduler,
    private val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
) : ProjectionBackend {

    override val id: String = ID
    override val displayName: String = "Android CarLife"
    override val capabilities: ProjectionCapabilities = ProjectionCapabilities()

    private val stateStore = ProjectionStateStore(ID)

    /** Wired CarLife session hardware: USB link + audio output. */
    override val requiredResources: Set<ProjectionResource> =
        setOf(ProjectionResource.USB, ProjectionResource.AUDIO)

    @Volatile
    override var isSessionActive: Boolean = false
        private set

    @Volatile
    private var connectPending = false

    private val sessionCounter = AtomicLong()

    @Volatile
    private var activeSession: CarLifeSessionToken? = null

    @Volatile
    private var probeReport = CarLifeProbeReport()

    @Volatile
    private var sessionStoppedListener: Runnable? = null

    @Volatile
    private var probeTimeout: AutoCloseable? = null

    override val state: ProjectionState
        get() = stateStore.state

    /** Latest protocol diagnostics (no user content, no secrets). */
    fun probeReport(): CarLifeProbeReport = probeReport

    override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)

    override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)

    override fun setSessionStoppedListener(listener: Runnable?) {
        sessionStoppedListener = listener
    }

    override fun initialize() {
        // The provider is initialized by the host (it needs an Activity class
        // and the application context); nothing else to prepare per probe.
    }

    override fun start() {
        // No discovery step for wired AOA: the phone appears as a USB
        // accessory after the (arbitrated) connect starts the transport.
    }

    override fun stop() {
        if (isSessionActive || connectPending) {
            stateStore.publish(ProjectionState.Disconnecting)
            provider.stopConnection(activeSession ?: return)
        }
    }

    override fun resolveConnectDevice(requested: ProjectionDevice?): ProjectionDevice? {
        // Wired AOA has no peer-device discovery concept: the phone attaches
        // itself. Nothing to resolve — resources are fixed (USB+AUDIO).
        return requested
    }

    override fun connect(device: ProjectionDevice?) {
        if (!provider.isAvailable) {
            publishError(ProjectionErrorCode.PROVIDER_UNAVAILABLE, "CarLife provider unavailable")
            return
        }
        if (isSessionActive || connectPending) return
        val token = CarLifeSessionToken(sessionCounter.incrementAndGet())
        activeSession = token
        connectPending = true
        probeReport = CarLifeProbeReport(
            state = CarLifeProbeState.USB_DEVICE_FOUND,
            blocker = CarLifeBlocker.NO_REAL_DEVICE,
            session = token.value,
        )
        stateStore.publish(ProjectionState.Connecting)
        try {
            provider.startConnection(token, ::onConnectionEvent)
            // Real diagnostics from the SDK (VID:PID only; no serials).
            provider.diagnostics().let { diag ->
                probeReport = probeReport.copy(
                    usbDevice = diag.usbDevices.joinToString().ifEmpty { null },
                    protocolVersion = diag.localProtocolVersion,
                )
            }
            probeReport = probeReport.copy(state = CarLifeProbeState.AOA_SWITCH_REQUESTED)
            armProbeTimeout(token)
        } catch (error: Exception) {
            connectPending = false
            activeSession = null
            probeReport = probeReport.copy(
                state = CarLifeProbeState.ERROR,
                blocker = CarLifeBlocker.OTHER,
                lastError = error.javaClass.simpleName,
            )
            publishError(ProjectionErrorCode.CONNECT_FAILED, "CarLife connect failed: ${error.javaClass.simpleName}")
        }
    }

    override fun disconnect(): Boolean {
        val token = activeSession ?: return true
        stateStore.publish(ProjectionState.Disconnecting)
        return try {
            provider.stopConnection(token)
            finishSession(CarLifeProbeState.DETACHED, blocker = CarLifeBlocker.NONE)
            true
        } catch (error: Exception) {
            probeReport = probeReport.copy(lastError = error.javaClass.simpleName)
            false
        }
    }

    override fun onTouchEvent(event: ProjectionTouchEvent): Boolean {
        // Phase 9.2: ProjectionTouchEvent -> CarLife MotionEvent uplink.
        return false
    }

    override fun onKeyEvent(event: ProjectionKeyEvent): Boolean {
        // Phase 9.2: key uplink via CarLife.receiver().onKeyEvent.
        return false
    }

    override fun close() {
        cancelProbeTimeout()
        runCatching { provider.dispose() }
        isSessionActive = false
        connectPending = false
        activeSession = null
    }

    // ---- Session events (token-checked; the provider tags every callback) ----

    private fun onConnectionEvent(event: CarLifeConnectionEvent) {
        val current = activeSession
        if (current == null || event.session != current) {
            // Stale callback of a dead attempt: identity comparison, never
            // ordering heuristics. A late event cannot pollute the new probe.
            probeReport = probeReport.copy(lastError = "stale-session-callback-ignored:${event::class.simpleName}")
            return
        }
        when (event) {
            is CarLifeConnectionEvent.Attached -> {
                // AOA attach observed — from here the timeout classifies as
                // AOA_COMPATIBILITY gone (progress continues).
                cancelProbeTimeout()
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.AOA_ATTACHED,
                    aoaState = "attached",
                    connectionState = 1,
                )
                armProbeTimeout(current)
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Reattached -> {
                probeReport = probeReport.copy(state = CarLifeProbeState.AOA_ATTACHED, aoaState = "reattached")
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Progress -> {
                // Real SDK progress: 0 -> protocol request, 30 -> version
                // accepted, ~70 -> auth completed. NEVER a success criterion —
                // Connected comes only from CONNECTION_ESTABLISHED.
                val stage = when {
                    event.progress < 30 -> CarLifeProbeState.PROTOCOL_NEGOTIATING
                    event.progress < 70 -> CarLifeProbeState.PROTOCOL_ACCEPTED
                    else -> CarLifeProbeState.AUTHENTICATING
                }
                probeReport = probeReport.copy(state = stage)
            }
            is CarLifeConnectionEvent.Established -> {
                // REAL success only at CONNECTION_ESTABLISHED. Reports
                // Connected exactly once.
                if (isSessionActive) return
                cancelProbeTimeout()
                connectPending = false
                isSessionActive = true
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.ESTABLISHED,
                    blocker = CarLifeBlocker.NONE,
                    connectionState = 3,
                    authResult = "accepted",
                    protocolVersion = provider.diagnostics().localProtocolVersion ?: probeReport.protocolVersion,
                    phoneCarLifeVersion = provider.diagnostics().phoneCarLifeVersion?.toString(),
                )
                stateStore.publish(ProjectionState.Connected)
            }
            is CarLifeConnectionEvent.VersionNotSupported -> {
                finishSession(
                    CarLifeProbeState.VERSION_REJECTED,
                    blocker = CarLifeBlocker.PROTOCOL_VERSION,
                    errorCode = ProjectionErrorCode.PROTOCOL_ERROR,
                    message = "CarLife protocol version rejected by phone",
                )
            }
            is CarLifeConnectionEvent.AuthFailed -> {
                finishSession(
                    CarLifeProbeState.AUTH_FAILED,
                    blocker = CarLifeBlocker.CHANNEL_OR_AUTH,
                    errorCode = ProjectionErrorCode.AUTHENTICATION_FAILED,
                    message = "CarLife channel/auth verification failed",
                )
            }
            is CarLifeConnectionEvent.Detached -> {
                finishSession(CarLifeProbeState.DETACHED, blocker = CarLifeBlocker.NONE)
            }
            is CarLifeConnectionEvent.Failed -> {
                finishSession(
                    CarLifeProbeState.ERROR,
                    blocker = CarLifeBlocker.OTHER,
                    errorCode = ProjectionErrorCode.TRANSPORT_ERROR,
                    message = event.message,
                )
            }
        }
    }

    /**
     * Terminal outcome of the current attempt: cancels the watchdog, clears
     * session state, publishes the mapped state and — critically — confirms
     * the session stopped so the manager releases the USB/AUDIO lease
     * immediately (never waiting for the next connect's sweep).
     */
    private fun finishSession(
        probeState: CarLifeProbeState,
        blocker: CarLifeBlocker,
        errorCode: ProjectionErrorCode? = null,
        message: String? = null,
    ) {
        cancelProbeTimeout()
        connectPending = false
        isSessionActive = false
        probeReport = probeReport.copy(state = probeState, blocker = blocker)
        if (errorCode != null) {
            publishError(errorCode, message ?: "CarLife session failed")
        } else {
            stateStore.publish(ProjectionState.Ready)
        }
        // Terminal cleanup: the session is confirmed stopped NOW.
        sessionStoppedListener?.run()
    }

    /** Classification-only watchdog (never fabricates state or identity). */
    private fun armProbeTimeout(token: CarLifeSessionToken) {
        cancelProbeTimeout()
        probeTimeout = timeoutScheduler.schedule(probeTimeoutMillis) {
            if (activeSession != token) return@schedule
            if (isSessionActive) return@schedule
            // Classify why the probe never reached AOA attach.
            val usbSeen = probeReport.usbDevice != null
            val blocker = if (usbSeen) CarLifeBlocker.AOA_COMPATIBILITY else CarLifeBlocker.NO_REAL_DEVICE
            finishSession(
                CarLifeProbeState.ERROR,
                blocker = blocker,
                errorCode = ProjectionErrorCode.TIMEOUT,
                message = "CarLife probe timed out waiting for AOA attach",
            )
        }
    }

    private fun cancelProbeTimeout() {
        probeTimeout?.let { runCatching { it.close() } }
        probeTimeout = null
    }

    private fun publishError(code: ProjectionErrorCode, message: String) {
        stateStore.publish(ProjectionState.Error(code, message, null, ID))
    }

    companion object {
        const val ID = "carlife"
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 15_000L
    }
}
