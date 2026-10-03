package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionInputKind
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionLogger
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.logName

/**
 * Apple CarPlay projection backend.
 *
 * Thin adapter over the existing [CarPlayController]: the controller keeps owning
 * iAP2, MFi, AirPlay/RTSP, Bonjour, USB/Wi-Fi bring-up and the CarPlay session
 * unchanged; this class only maps its status vocabulary onto [ProjectionState],
 * maps unified input events onto CarPlay HID calls, and exposes the resource
 * hints used by `ProjectionManager` arbitration.
 *
 * Two lifecycle modes:
 *  - **owned** — created via the [CarPlayProjectionBackend.owned] factory;
 *    `start()`/`disconnect()` drive the controller and can confirm a stop;
 *  - **wrapped** — created via [CarPlayProjectionBackend.wrapping] around a
 *    controller the host activity owns (the current DiPlay CarPlay flow). The
 *    backend never closes that controller on its own: a stop request is
 *    forwarded to the host via [hostStopHandler], and [isSessionActive] keeps
 *    reporting the truth (`!controller.isClosed()`) until the host really
 *    stopped it. The host then confirms via
 *    `ProjectionManager.onBackendSessionStopped(id)` (wired automatically
 *    through [setSessionStoppedListener]) — only then are shared resources
 *    released.
 */
class CarPlayProjectionBackend internal constructor(
    private val sessionFactory: (((CarPlayStatus) -> Unit) -> CarPlayController)?,
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
    private val transport: ProjectionTransport = ProjectionTransport.USB,
) : ProjectionBackend {

    override val id: String = ID

    override val displayName: String = "Apple CarPlay"

    override val capabilities: ProjectionCapabilities = ProjectionCapabilities(
        videoCodecs = setOf(ProjectionVideoCodec.H264, ProjectionVideoCodec.H265),
        audioChannels = setOf(
            com.shilapi.xcertplay.projection.ProjectionAudioChannel.MEDIA,
            com.shilapi.xcertplay.projection.ProjectionAudioChannel.NAVIGATION,
            com.shilapi.xcertplay.projection.ProjectionAudioChannel.PHONE_CALL,
            com.shilapi.xcertplay.projection.ProjectionAudioChannel.VOICE_ASSISTANT,
        ),
        inputKinds = setOf(
            ProjectionInputKind.TOUCH,
            ProjectionInputKind.MULTI_TOUCH,
            ProjectionInputKind.KEY,
        ),
        maxTouchContacts = CarPlayInputAdapter.MAX_CONTACTS,
        microphoneUplink = true,
        metadata = true,
        navigation = true,
    )

    override val requiredResources: Set<ProjectionResource>
        get() = when (transport) {
            ProjectionTransport.USB ->
                setOf(ProjectionResource.USB, ProjectionResource.AUDIO, ProjectionResource.MICROPHONE)
            else ->
                setOf(
                    ProjectionResource.WIFI,
                    ProjectionResource.BLUETOOTH,
                    ProjectionResource.AUDIO,
                    ProjectionResource.MICROPHONE,
                )
        }

    private val stateStore = ProjectionStateStore(ID, logger)

    /** True when this backend created the controller and may stop it. */
    private val ownsLifecycle: Boolean
        get() = sessionFactory != null

    override val state: ProjectionState
        get() = stateStore.state

    /**
     * Ground truth for arbitration: true while the CarPlay controller (and thus
     * the real CarPlay session stack) is alive. For a wrapped session this stays
     * true across a stop *request* until the host actually closed it.
     */
    override val isSessionActive: Boolean
        get() = controller?.let { !it.isClosed() } ?: false

    @Volatile
    private var controller: CarPlayController? = null

    @Volatile
    private var started = false

    @Volatile
    private var sessionStoppedListener: Runnable? = null

    /**
     * Host lifecycle/takeover hook for wrapped sessions: invoked when the
     * projection layer wants the externally-owned CarPlay session stopped
     * (e.g. an explicit takeover). The host performs its real shutdown and then
     * calls [notifySessionStopped]. Null when no host registered a handler —
     * the backend then simply reports the session as still active.
     */
    @Volatile
    var hostStopHandler: (() -> Unit)? = null

    /** The wrapped/created CarPlay controller, for Apple-only surfaces. */
    val carPlayController: CarPlayController?
        get() = controller

    override fun addStateListener(listener: ProjectionStateListener) {
        stateStore.addListener(listener)
    }

    override fun removeStateListener(listener: ProjectionStateListener) {
        stateStore.removeListener(listener)
    }

    override fun setSessionStoppedListener(listener: Runnable?) {
        sessionStoppedListener = listener
    }

    /**
     * The host reports that its externally-owned session has actually stopped
     * (after [hostStopHandler] or its own teardown). Releases the arbitration
     * hold via the registered session-stopped listener.
     */
    fun notifySessionStopped() {
        logger.log("backend=$ID host-confirmed-session-stopped")
        sessionStoppedListener?.run()
    }

    /** Wraps an externally-owned controller (wrapped mode). */
    fun attach(controller: CarPlayController) {
        synchronized(this) {
            this.controller = controller
        }
        logger.log("backend=$ID attach external-controller transport=$transport")
    }

    /** Forwards a CarPlay status into the projection state machine. */
    fun acceptStatus(status: CarPlayStatus) = onCarPlayStatus(status)

    override fun initialize() {
        val factory = sessionFactory ?: return
        synchronized(this) {
            if (controller != null) return
            stateStore.publish(ProjectionState.Initializing)
            controller = try {
                factory { status -> onCarPlayStatus(status) }
            } catch (error: Exception) {
                stateStore.publish(
                    ProjectionState.Error(
                        code = ProjectionErrorCode.INTERNAL_ERROR,
                        message = error.message ?: error.javaClass.simpleName,
                        cause = error,
                        backendId = ID,
                    ),
                )
                return
            }
            logger.log("backend=$ID controller-created transport=$transport")
        }
    }

    override fun start() {
        val session = controller ?: run { initialize(); controller }
        if (session == null || session.isClosed()) return
        synchronized(this) {
            if (started) return
            started = true
        }
        // Wrapped controllers are started by their host; only owned ones are
        // driven from here so CarPlay behavior never changes underneath the UI.
        if (ownsLifecycle) {
            logger.log("backend=$ID start")
            session.start()
        }
    }

    override fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        logger.log("backend=$ID stop")
        if (ownsLifecycle) closeController()
    }

    override fun connect(device: ProjectionDevice?) {
        initialize()
        start()
    }

    /**
     * Requests the CarPlay session to stop. Returns true only when the session
     * is confirmed stopped:
     *  - owned mode closes the controller and waits for it;
     *  - wrapped mode delegates to [hostStopHandler] and reports `false` until
     *    the host calls [notifySessionStopped] — shared resources stay claimed
     *    meanwhile, so no other backend can mistake the hardware for free.
     */
    override fun disconnect(): Boolean {
        logger.log("backend=$ID disconnect requested ownsLifecycle=$ownsLifecycle")
        synchronized(this) { started = false }
        return if (ownsLifecycle) {
            closeController()
            stateStore.publish(ProjectionState.Idle)
            true
        } else {
            if (stateStore.state is ProjectionState.Connected) {
                stateStore.publish(ProjectionState.Disconnecting)
            }
            val handler = hostStopHandler
            if (handler != null) {
                try {
                    handler()
                } catch (error: Exception) {
                    logger.log("backend=$ID host-stop-failed ${error.javaClass.simpleName}")
                }
            }
            // Truthful answer: the wrapped session is alive until its host
            // really closed the controller.
            !isSessionActive
        }
    }

    override fun onTouchEvent(event: ProjectionTouchEvent): Boolean {
        val session = controller ?: return false
        return sendContacts(session, CarPlayInputAdapter.contacts(event))
    }

    override fun onKeyEvent(event: ProjectionKeyEvent): Boolean {
        val session = controller ?: return false
        return when (event.code) {
            com.shilapi.xcertplay.projection.ProjectionKeyCode.VOICE_ASSISTANT -> session.requestSiri()
            com.shilapi.xcertplay.projection.ProjectionKeyCode.MEDIA_PLAY_PAUSE,
            com.shilapi.xcertplay.projection.ProjectionKeyCode.MEDIA_NEXT,
            com.shilapi.xcertplay.projection.ProjectionKeyCode.MEDIA_PREVIOUS,
            -> session.sendMediaButton(event.detail)
            else -> false
        }
    }

    /** Legacy CarPlay touch path used by the host UI: normalized contacts straight to HID. */
    fun sendTouchContacts(contacts: List<AirPlayContact>): Boolean {
        val session = controller ?: return false
        return sendContacts(session, contacts)
    }

    override fun close() {
        if (ownsLifecycle) {
            closeController()
        }
        stateStore.publish(ProjectionState.Idle)
    }

    private fun sendContacts(session: CarPlayController, contacts: List<AirPlayContact>): Boolean =
        try {
            session.sendTouch(contacts)
        } catch (error: Exception) {
            logger.log("backend=$ID touch-failed ${error.javaClass.simpleName}")
            false
        }

    private fun closeController() {
        val session: CarPlayController
        synchronized(this) {
            session = controller ?: return
            controller = null
        }
        try {
            if (!session.isClosed()) {
                session.close()
                session.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            }
        } catch (error: Exception) {
            logger.log("backend=$ID close-failed ${error.javaClass.simpleName}")
        }
    }

    private fun onCarPlayStatus(status: CarPlayStatus) {
        val mapped = CarPlayStateMapping.toProjectionState(status, ID)
        logger.log("backend=$ID carplay-status=${status.javaClass.simpleName} state=${mapped.logName}")
        stateStore.publish(mapped)
    }

    companion object {
        const val ID = "carplay"
        private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L

        /** Owned mode: the backend creates and drives the controller. */
        fun owned(
            sessionFactory: (reportStatus: (CarPlayStatus) -> Unit) -> CarPlayController,
            logger: ProjectionLogger = ProjectionLogger.NONE,
            transport: ProjectionTransport = ProjectionTransport.USB,
        ): CarPlayProjectionBackend = CarPlayProjectionBackend(sessionFactory, logger, transport)

        /** Wrapped mode: the backend mirrors an externally-owned controller. */
        fun wrapping(
            controller: CarPlayController,
            logger: ProjectionLogger = ProjectionLogger.NONE,
            transport: ProjectionTransport = ProjectionTransport.USB,
        ): CarPlayProjectionBackend = CarPlayProjectionBackend(null, logger, transport).apply {
            attach(controller)
        }
    }
}
