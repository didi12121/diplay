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
 * Third DiPlay projection backend: open CarLife (Baidu CarLife+) over USB AOA,
 * built on the Apache-2.0 CarLife V2.0 SDK imported from Apollo-DuerOS.
 *
 * Phase 9.1 scope — **Compatibility Probe only**:
 *  - real USB session → AOA accessory → CarLife handshake → version
 *    negotiation → authentication → CONNECTION_ESTABLISHED
 *  - NO media adapter yet (video/audio/touch wiring arrives in Phase 9.2 and
 *    must go through the shared `ProjectionVideoSink`/`AudioAccessUnit`
 *    pipeline, not the SDK's own player/decoder).
 *
 * State mapping (never reports Connected early):
 * ```
 * USB/AOA negotiating          -> Connecting
 * CONNECTION_ESTABLISHED       -> Connected
 * version rejected             -> Error (PROTOCOL_VERSION)
 * auth/channel failed          -> Error (AUTHENTICATION_FAILED)
 * disconnect in progress       -> Disconnecting
 * detached                     -> Ready
 * ```
 *
 * Session identity: every connect attempt mints a [CarLifeSessionToken];
 * callbacks of older attempts are ignored by identity comparison (see
 * [CarLifeProvider]) — the CarLink stale-callback lesson, applied from day one
 * (and kept separate from `CarLinkSessionToken`).
 */
class CarLifeProjectionBackend(
    private val provider: CarLifeProvider,
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

    override val state: ProjectionState
        get() = stateStore.state

    /** Latest protocol diagnostics (no user content, no secrets). */
    fun probeReport(): CarLifeProbeReport = probeReport

    override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)

    override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)

    override fun initialize() {
        // The provider is initialized by the host (it needs an Activity class
        // and the application context); nothing else to prepare per probe.
    }

    override fun start() {
        // No discovery step for wired AOA: the phone appears as a USB
        // accessory. Reports a discoverable device so the manager's AUTO
        // selection can see CarLife as a candidate.
    }

    override fun stop() {
        if (isSessionActive || connectPending) {
            stateStore.publish(ProjectionState.Disconnecting)
            provider.stopConnection(activeSession ?: return)
        }
    }

    override fun resolveConnectDevice(requested: ProjectionDevice?): ProjectionDevice? {
        // Wired AOA has no peer-device discovery concept: the phone attaches
        // itself. There is nothing to resolve — resources are fixed (USB+AUDIO).
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
            state = CarLifeProbeState.AOA_SWITCH_REQUESTED,
            blocker = CarLifeBlocker.NO_REAL_DEVICE,
            session = token.value,
        )
        stateStore.publish(ProjectionState.Connecting)
        try {
            provider.startConnection(token, ::onConnectionEvent)
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
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.AOA_ATTACHED,
                    aoaState = "attached",
                    connectionState = 1,
                )
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Reattached -> {
                probeReport = probeReport.copy(state = CarLifeProbeState.AOA_ATTACHED, aoaState = "reattached")
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Established -> {
                // REAL success only at CONNECTION_ESTABLISHED (not at AOA, not
                // at USB detection). Reports Connected exactly once.
                if (isSessionActive) return
                connectPending = false
                isSessionActive = true
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.ESTABLISHED,
                    blocker = CarLifeBlocker.NONE,
                    connectionState = 3,
                    authResult = "accepted",
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

    private fun finishSession(
        probeState: CarLifeProbeState,
        blocker: CarLifeBlocker,
        errorCode: ProjectionErrorCode? = null,
        message: String? = null,
    ) {
        connectPending = false
        isSessionActive = false
        probeReport = probeReport.copy(state = probeState, blocker = blocker)
        if (errorCode != null) {
            publishError(errorCode, message ?: "CarLife session failed")
        } else {
            stateStore.publish(ProjectionState.Ready)
        }
    }

    private fun publishError(code: ProjectionErrorCode, message: String) {
        stateStore.publish(ProjectionState.Error(code, message, null, ID))
    }

    companion object {
        const val ID = "carlife"
    }
}
