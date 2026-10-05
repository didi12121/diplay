// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.os.SystemClock
import android.view.MotionEvent
import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchAction
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
 * Phase 9.2a adds REAL VIDEO: protocol video flows through the CarLife video
 * seam ([CarLifeVideoBridge] / [CarLifeVideoListener]) into
 * `ProjectionVideoConfig`/`ProjectionVideoFrame` and the SHARED
 * `AndroidMediaSink` — the upstream SDK's own FrameDecoder is bypassed
 * (`CONFIG_EXTERNAL_VIDEO_SINK` / RAW_BRIDGE_MODE), so there is exactly one
 * MediaCodec and one Surface owner.
 *
 * State mapping stays honest: Connected ONLY at CONNECTION_ESTABLISHED.
 * Video lifecycle is session-scoped: bridge + sink belong to one
 * [CarLifeSessionToken]; a late video callback of a dead attempt is dropped
 * with a `stale-video-*-ignored` diagnostic and never reaches the live
 * decoder. Media ownership rules: teardown of session A can never close
 * session B's decoder.
 *
 * Phase 9.2b adds REAL TOUCH: unified [ProjectionTouchEvent]s are mapped to
 * content-LOCAL coordinates and forwarded through the HIGH-LEVEL CarLife API
 * ([CarLifeProvider.updateTouchSurface] / [CarLifeProvider.sendTouch] →
 * `CarLifeReceiver.onSurfaceSizeChanged` / `onTouchEvent` →
 * RemoteControlManager → phone). The custom CarLife touch protocol is NEVER
 * reimplemented here, and coordinates are NEVER pre-scaled to video size:
 * RemoteControlManager performs the single surface → video mapping in the
 * SDK. Touch is session-scoped exactly like video — a stale touch from a dead
 * session is dropped with a `stale-touch-ignored` diagnostic.
 */
class CarLifeProjectionBackend(
    private val provider: CarLifeProvider,
    private val timeoutScheduler: CarLifeProbeTimeoutScheduler = SystemCarLifeProbeTimeoutScheduler,
    private val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
    private val videoSinkProvider: CarLifeVideoSinkProvider? = null,
    /** Event clock for MotionEvent timestamps (SystemClock.uptimeMillis). */
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
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

    // ---- Video pipeline state (session-scoped) ----
    private val videoLock = Any()
    private var videoSink: CarLifeVideoSinkSession? = null
    private var videoSinkToken: CarLifeSessionToken? = null
    private var videoFrameCount = 0
    private var videoConfigCount = 0
    private var videoBytes = 0L
    private var keyframeCount = 0

    // ---- Touch uplink state (session-scoped, Phase 9.2b) ----
    private val touchLock = Any()

    /** downTime of the current gesture; 0 = no live gesture. */
    private var gestureDownTime = 0L

    /** Last touch surface declared to the SDK (0 = none) — never resent unchanged. */
    private var lastTouchSurfaceWidth = 0
    private var lastTouchSurfaceHeight = 0

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
        // The provider is initialized by the host (needs an Activity class and
        // the application context).
    }

    override fun start() {
        // No discovery step for wired AOA.
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
        videoFrameCount = 0
        videoConfigCount = 0
        videoBytes = 0
        keyframeCount = 0
        // Fresh session: no gesture or surface-size state may leak in from a
        // previous one (reconnect cannot receive a previous session gesture).
        clearTouchGestureState()
        probeReport = CarLifeProbeReport(
            state = CarLifeProbeState.USB_DEVICE_FOUND,
            blocker = CarLifeBlocker.NO_REAL_DEVICE,
            session = token.value,
            ptsSource = CarLifeVideoFraming.PTS_SOURCE,
        )
        stateStore.publish(ProjectionState.Connecting)
        try {
            provider.startConnection(token, ::onConnectionEvent)
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
        // ---- 1/2. Session fencing: touch belongs to exactly one live session.
        // A late Activity/event of session A must never reach session B.
        val token = activeSession
        if (!isSessionActive || token == null || state != ProjectionState.Connected) {
            dropTouch("stale-touch-ignored")
            return false
        }
        // 3. Pointer list must not be empty.
        val pointer = event.pointers.firstOrNull()
        if (pointer == null) {
            dropTouch("empty-pointer-list")
            return false
        }
        // 4. Only touches inside the projected content are forwarded.
        val rect = event.geometry.contentRect
        if (!rect.contains(pointer.x, pointer.y)) {
            dropTouch("touch-outside-content")
            return false
        }
        val surfaceWidth = rect.width.toInt()
        val surfaceHeight = rect.height.toInt()
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            dropTouch("invalid-touch-surface")
            return false
        }
        // 5. View -> content-LOCAL coordinates. RemoteControlManager performs
        //    the final surface -> video mapping in the SDK — do NOT pre-scale
        //    to video size here (that would double-scale).
        val localX = pointer.x - rect.left
        val localY = pointer.y - rect.top

        val now = clock()
        synchronized(touchLock) {
            // 6. Touch surface size — declared only when the geometry changed.
            if (lastTouchSurfaceWidth != surfaceWidth || lastTouchSurfaceHeight != surfaceHeight) {
                try {
                    provider.updateTouchSurface(token, surfaceWidth, surfaceHeight)
                } catch (error: Exception) {
                    dropTouch(error.javaClass.simpleName)
                    return false
                }
                lastTouchSurfaceWidth = surfaceWidth
                lastTouchSurfaceHeight = surfaceHeight
                probeReport = probeReport.copy(
                    touchSurfaceWidth = surfaceWidth,
                    touchSurfaceHeight = surfaceHeight,
                )
            }

            // 7. ONE Android MotionEvent per unified event (single touch).
            //    Gesture state: DOWN mints a fresh downTime; MOVE/UP/CANCEL
            //    reuse the live gesture's downTime.
            val downTime = when (event.action) {
                ProjectionTouchAction.DOWN -> {
                    gestureDownTime = now
                    now
                }
                else -> {
                    if (gestureDownTime == 0L) gestureDownTime = now
                    gestureDownTime
                }
            }
            val motionAction = when (event.action) {
                ProjectionTouchAction.DOWN -> MotionEvent.ACTION_DOWN
                ProjectionTouchAction.MOVE -> MotionEvent.ACTION_MOVE
                ProjectionTouchAction.UP -> MotionEvent.ACTION_UP
                ProjectionTouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
            }
            val motion = MotionEvent.obtain(downTime, now, motionAction, localX, localY, 0)
            // 8. Synchronous high-level send; the event is borrowed only for
            //    the call and recycled immediately afterwards.
            var sendError: String? = null
            val sent = try {
                provider.sendTouch(token, motion)
            } catch (error: Exception) {
                sendError = error.javaClass.simpleName
                false
            } finally {
                motion.recycle()
            }
            if (motionAction == MotionEvent.ACTION_UP || motionAction == MotionEvent.ACTION_CANCEL) {
                // Gesture ended: the next gesture gets a fresh downTime.
                gestureDownTime = 0L
            }
            // 9. Accepted send -> exactly one counted touch event.
            if (!sent) {
                dropTouch(sendError ?: "touch-send-rejected")
                probeReport = probeReport.copy(touchState = "error")
                return false
            }
            recordTouch(event.action, localX, localY)
        }
        return true
    }

    /** Counts one accepted touch event (no history, no user content). */
    private fun recordTouch(action: ProjectionTouchAction, x: Float, y: Float) {
        probeReport = probeReport.copy(
            touchState = "enabled",
            touchEventsSent = probeReport.touchEventsSent + 1,
            touchDownCount = probeReport.touchDownCount + if (action == ProjectionTouchAction.DOWN) 1 else 0,
            touchMoveCount = probeReport.touchMoveCount + if (action == ProjectionTouchAction.MOVE) 1 else 0,
            touchUpCount = probeReport.touchUpCount + if (action == ProjectionTouchAction.UP) 1 else 0,
            touchCancelCount = probeReport.touchCancelCount + if (action == ProjectionTouchAction.CANCEL) 1 else 0,
            lastTouchAction = action.name,
            lastTouchX = x,
            lastTouchY = y,
        )
    }

    /** Counts one rejected touch event with its reason (diagnostic only). */
    private fun dropTouch(reason: String) {
        probeReport = probeReport.copy(
            touchDropped = probeReport.touchDropped + 1,
            lastTouchError = reason,
        )
    }

    /** Clears gesture + surface cache so a new session cannot inherit state. */
    private fun clearTouchGestureState() {
        synchronized(touchLock) {
            gestureDownTime = 0L
            lastTouchSurfaceWidth = 0
            lastTouchSurfaceHeight = 0
        }
    }

    override fun onKeyEvent(event: ProjectionKeyEvent): Boolean {
        // Phase 9.2b: key uplink.
        return false
    }

    override fun close() {
        cancelProbeTimeout()
        val token = activeSession
        provider.detachVideo(token ?: CarLifeSessionToken(-1))
        closeVideoSink(token)
        clearTouchGestureState()
        runCatching { provider.dispose() }
        isSessionActive = false
        connectPending = false
        activeSession = null
    }

    // ---- Session events (token-checked; provider tags every callback) ----

    private fun onConnectionEvent(event: CarLifeConnectionEvent) {
        val current = activeSession
        if (current == null || event.session != current) {
            probeReport = probeReport.copy(lastError = "stale-session-callback-ignored:${event::class.simpleName}")
            return
        }
        when (event) {
            is CarLifeConnectionEvent.Attached -> {
                cancelProbeTimeout()
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.AOA_ATTACHED,
                    aoaState = "attached",
                    connectionState = 1,
                )
                armProbeTimeout(current)
                // Open the session video pipeline EARLY (idempotent): the
                // phone may answer VIDEO_ENCODER_INIT_DONE right after
                // Established, before any later callback could register the
                // bridge. Racing it would drop the first config.
                ensureVideoSink(current)
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Reattached -> {
                ensureVideoSink(current)
                probeReport = probeReport.copy(state = CarLifeProbeState.AOA_ATTACHED, aoaState = "reattached")
                stateStore.publish(ProjectionState.Connecting)
            }
            is CarLifeConnectionEvent.Progress -> {
                val stage = when {
                    event.progress < 30 -> CarLifeProbeState.PROTOCOL_NEGOTIATING
                    event.progress < 70 -> CarLifeProbeState.PROTOCOL_ACCEPTED
                    else -> CarLifeProbeState.AUTHENTICATING
                }
                probeReport = probeReport.copy(state = stage)
            }
            is CarLifeConnectionEvent.Established -> {
                if (isSessionActive) return
                cancelProbeTimeout()
                connectPending = false
                isSessionActive = true
                probeReport = probeReport.copy(
                    state = CarLifeProbeState.ESTABLISHED,
                    blocker = CarLifeBlocker.NONE,
                    connectionState = 3,
                    authResult = "accepted",
                    phoneCarlifeProtocolVersion = provider.diagnostics().phoneCarlifeProtocolVersion,
                )
                // REAL VIDEO: ensure the session-scoped video pipeline exists
                // (opened at Attached already; idempotent per token).
                ensureVideoSink(current)
                // Touch uplink is live from here on.
                probeReport = probeReport.copy(touchState = "enabled")
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
     * Terminal outcome of the current attempt: cancels the watchdog, closes
     * THIS session's video path (never a newer one's), clears session state
     * and confirms the session stopped so the manager releases USB/AUDIO now.
     */
    private fun finishSession(
        probeState: CarLifeProbeState,
        blocker: CarLifeBlocker,
        errorCode: ProjectionErrorCode? = null,
        message: String? = null,
    ) {
        cancelProbeTimeout()
        val token = activeSession
        connectPending = false
        isSessionActive = false
        provider.detachVideo(token ?: CarLifeSessionToken(-1))
        closeVideoSink(token)
        // Session over: gesture state and the touch surface cache die with it.
        clearTouchGestureState()
        probeReport = probeReport.copy(
            state = probeState,
            blocker = blocker,
            decoderState = if (errorCode != null) "error" else "stopped",
            touchState = "waiting",
            touchSurfaceWidth = null,
            touchSurfaceHeight = null,
        )
        if (errorCode != null) {
            publishError(errorCode, message ?: "CarLife session failed")
        } else {
            stateStore.publish(ProjectionState.Ready)
        }
        sessionStoppedListener?.run()
    }

    // ---- Video pipeline (session-scoped; identity-bound like the session) ----

    /** Stats + relay into the session's projection sink. */
    private inner class VideoRelay(
        private val adapter: CarLifeProjectionVideoAdapter,
    ) : CarLifeVideoListener {
        override fun onVideoConfig(session: CarLifeSessionToken, config: CarLifeVideoConfig) {
            if (session != activeSession) {
                probeReport = probeReport.copy(lastVideoError = "stale-video-config-ignored")
                return
            }
            videoConfigCount++
            probeReport = probeReport.copy(
                videoStage = "VIDEO_CONFIG_RECEIVED",
                videoCodec = "H264",
                videoWidth = config.width,
                videoHeight = config.height,
                videoConfigCount = videoConfigCount,
                decoderState = "configured",
            )
            adapter.onVideoConfig(session, config)
        }

        override fun onVideoFrame(session: CarLifeSessionToken, frame: CarLifeVideoFrame) {
            if (session != activeSession) {
                probeReport = probeReport.copy(lastVideoError = "stale-video-frame-ignored")
                return
            }
            videoFrameCount++
            videoBytes += frame.length
            if (frame.keyFrame) keyframeCount++
            probeReport = probeReport.copy(
                videoStage = if (probeReport.firstFrameRendered) {
                    "FIRST_OUTPUT_FRAME_RENDERED"
                } else {
                    "VIDEO_FRAME_QUEUED"
                },
                videoFrameCount = videoFrameCount,
                videoBytes = videoBytes,
                keyframeCount = keyframeCount,
                lastFrameAgeMs = 0,
                decoderState = if (probeReport.firstFrameRendered) "rendering" else "decoding",
            )
            adapter.onVideoFrame(session, frame)
        }

        override fun onVideoStopped(session: CarLifeSessionToken) {
            if (session != activeSession) {
                probeReport = probeReport.copy(lastVideoError = "stale-video-stop-ignored")
                return
            }
            probeReport = probeReport.copy(decoderState = "stopped")
            adapter.onVideoStopped(session)
        }

        override fun onVideoHandshake(session: CarLifeSessionToken, handshake: CarLifeVideoHandshake) {
            if (session != activeSession) return
            probeReport = probeReport.copy(
                videoInitSent = handshake.initSent,
                videoInitDoneReceived = handshake.initDoneReceived,
                videoStartSent = handshake.startSent,
                videoDataSeen = handshake.dataSeen,
            )
        }
    }

    /**
     * Idempotent per [token]: opens THIS session's video pipeline at most
     * once. Called at AOA attach (before version/auth) and re-checked at
     * Established — never creates a second sink for the same session.
     */
    private fun ensureVideoSink(token: CarLifeSessionToken) {
        val sinkProvider = videoSinkProvider ?: return
        synchronized(videoLock) {
            if (videoSinkToken == token) return // already open for this session
        }
        try {
            val sessionSink = sinkProvider.acquire(::onDecoderDiagnostic)
            val adapter = CarLifeProjectionVideoAdapter(sessionSink.video)
            synchronized(videoLock) {
                videoSink = sessionSink
                videoSinkToken = token
            }
            provider.attachVideo(token, VideoRelay(adapter))
            probeReport = probeReport.copy(videoStage = "VIDEO_PIPELINE_OPEN", decoderState = "waiting")
        } catch (error: Exception) {
            probeReport = probeReport.copy(
                lastVideoError = error.javaClass.simpleName,
                decoderState = "error",
            )
        }
    }

    private fun closeVideoSink(token: CarLifeSessionToken?) {
        val toClose: CarLifeVideoSinkSession?
        synchronized(videoLock) {
            // Only the owning session may close its media path. A teardown of
            // session A can never close session B's decoder.
            if (token != null && videoSinkToken != null && token != videoSinkToken) return
            toClose = videoSink
            videoSink = null
            videoSinkToken = null
        }
        runCatching { toClose?.close() }
    }

    /** Shared-decoder diagnostics (e.g. "first frame rendered"). */
    private fun onDecoderDiagnostic(message: String) {
        val rendered = message.contains("first frame rendered")
        probeReport = probeReport.copy(
            decoderState = if (rendered) "rendering" else probeReport.decoderState,
            firstFrameRendered = probeReport.firstFrameRendered || rendered,
            videoStage = if (rendered) "FIRST_OUTPUT_FRAME_RENDERED" else probeReport.videoStage,
        )
    }

    /** Classification-only watchdog (never fabricates state or identity). */
    private fun armProbeTimeout(token: CarLifeSessionToken) {
        cancelProbeTimeout()
        probeTimeout = timeoutScheduler.schedule(probeTimeoutMillis) {
            if (activeSession != token) return@schedule
            if (isSessionActive) return@schedule
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
