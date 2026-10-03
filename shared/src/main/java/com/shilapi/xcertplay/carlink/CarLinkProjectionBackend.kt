package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionLogger
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport

/**
 * ICCOA CarLink projection backend.
 *
 * Wraps a [CarLinkController] (which in turn wraps a [CarLinkProtocolAdapter])
 * behind the neutral [ProjectionBackend]. The adapter is injected so the core
 * never depends on a concrete SDK:
 *
 * ```
 * ProjectionManager
 *       ↓
 * CarLinkProjectionBackend
 *       ↓
 * CarLinkController
 *       ↓
 * CarLinkProtocolAdapter   ← MockCarLinkProtocolAdapter (tests/dev harness only),
 *       ↓                     OfficialCarLinkSdkAdapter / NativeCarLinkProtocolAdapter later
 * ICCOA CarLink SDK / protocol
 * ```
 *
 * Media: real sessions acquire [CarLinkMediaSinks] from the injected
 * [CarLinkMediaSinkProvider] when the session starts and release them when it
 * ends, so video/audio reach the shared Android rendering pipeline
 * (ProjectionMediaSinkAdapter → AndroidMediaSink → MediaCodec / AudioTrack).
 *
 * Resources: [requiredResourcesFor] computes the shared hardware from the
 * session's negotiated transport (USB vs wireless), not a fixed list.
 *
 * With no SDK installed the backend reports
 * `CarLink protocol provider unavailable` instead of pretending to connect.
 */
class CarLinkProjectionBackend(
    private val adapter: CarLinkProtocolAdapter,
    private val mediaSinks: CarLinkMediaSinkProvider,
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
) : ProjectionBackend {

    override val id: String = ID

    override val displayName: String = "Android CarLink"

    override val capabilities: ProjectionCapabilities = ProjectionCapabilities(
        videoCodecs = CarLinkCapabilities.videoCodecs,
        audioChannels = CarLinkCapabilities.audioChannels,
        inputKinds = CarLinkCapabilities.inputKinds,
        maxTouchContacts = CarLinkCapabilities.MAX_TOUCH_CONTACTS,
        microphoneUplink = CarLinkCapabilities.MICROPHONE_UPLINK_RESERVED,
        metadata = true,
        navigation = true,
    )

    /**
     * Transport-aware resources: USB CarLink claims USB+AUDIO, wireless claims
     * WIFI+AUDIO. Before a device is known, AUDIO alone is claimed and the
     * per-session set is re-evaluated at connect time via
     * [requiredResourcesFor].
     */
    override val requiredResources: Set<ProjectionResource> =
        CarLinkCapabilities.resourcesFor(ProjectionTransport.UNKNOWN)

    private val stateStore = ProjectionStateStore(ID, logger)

    @Volatile
    private var sessionStoppedListener: Runnable? = null

    /** Last negotiated transport; feeds [requiredResourcesFor] when device=null. */
    @Volatile
    private var currentTransport: ProjectionTransport = ProjectionTransport.UNKNOWN

    private val controller = CarLinkController(
        adapter = adapter,
        mediaSinks = mediaSinks,
        logger = logger,
        backendId = ID,
    )

    override val state: ProjectionState
        get() = stateStore.state

    override val isSessionActive: Boolean
        get() = controller.sessionActive

    override fun requiredResourcesFor(device: ProjectionDevice?): Set<ProjectionResource> =
        CarLinkCapabilities.resourcesFor(
            device?.transport ?: currentTransport,
        )

    override fun discoveredDevices(): List<ProjectionDevice> = controller.discoveredDevices()

    /** The CarLink controller, for UI/diagnostics and metadata listeners. */
    val carLink: CarLinkController
        get() = controller

    init {
        controller.addSessionListener { event -> onSessionEvent(event) }
    }

    override fun addStateListener(listener: ProjectionStateListener) {
        stateStore.addListener(listener)
    }

    override fun removeStateListener(listener: ProjectionStateListener) {
        stateStore.removeListener(listener)
    }

    override fun setSessionStoppedListener(listener: Runnable?) {
        sessionStoppedListener = listener
    }

    override fun initialize() {
        if (!adapter.isAvailable) {
            publishProviderUnavailable()
            return
        }
        stateStore.publish(ProjectionState.Initializing)
        // Only advance to Ready on a real success: a failing adapter publishes
        // Error (via the session listener) and that Error must stay visible —
        // never be overwritten by a healthy state.
        val result = controller.initialize()
        if (result.isSuccess) {
            stateStore.publish(ProjectionState.Ready)
        }
    }

    override fun start() {
        if (!adapter.isAvailable) return
        stateStore.publish(ProjectionState.Discovering)
        val result = controller.startDiscovery()
        if (!result.isSuccess) {
            // Error already published through the session listener; discovery
            // must not claim progress over it.
            logger.log("backend=$ID discovery-failed code=${result.code()}")
        }
    }

    override fun stop() {
        controller.stopDiscovery()
        if (stateStore.state is ProjectionState.Connected) {
            stateStore.publish(ProjectionState.Disconnecting)
        }
    }

    override fun connect(device: ProjectionDevice?) {
        if (!adapter.isAvailable) {
            publishProviderUnavailable()
            return
        }
        currentTransport = device?.transport ?: ProjectionTransport.UNKNOWN
        stateStore.publish(ProjectionState.Connecting)
        val result = controller.connect(device)
        if (!result.isSuccess) {
            logger.log("backend=$ID connect-failed code=${result.code()}")
        }
    }

    override fun disconnect(): Boolean {
        if (stateStore.state is ProjectionState.Connected) {
            stateStore.publish(ProjectionState.Disconnecting)
        }
        val result = controller.disconnect()
        if (!result.isSuccess) {
            logger.log("backend=$ID disconnect-failed code=${result.code()}")
        }
        // The controller tears its session state down synchronously for
        // adapters that stop inline; asynchronous adapters end via
        // onSessionEnded → session-stopped notification.
        return !controller.sessionActive
    }

    override fun onTouchEvent(event: ProjectionTouchEvent): Boolean {
        if (!controller.sessionActive) return false
        return controller.input.onTouch(event)
    }

    override fun onKeyEvent(event: ProjectionKeyEvent): Boolean {
        if (!controller.sessionActive) return false
        return controller.input.onKey(event)
    }

    override fun close() {
        controller.dispose()
        stateStore.publish(ProjectionState.Idle)
    }

    private fun publishProviderUnavailable() {
        stateStore.publish(
            ProjectionState.Error(
                code = ProjectionErrorCode.PROVIDER_UNAVAILABLE,
                message = PROVIDER_UNAVAILABLE_MESSAGE,
                cause = null,
                backendId = ID,
            ),
        )
    }

    private fun CarLinkOperationResult.code(): String =
        (this as? CarLinkOperationResult.Failure)?.code ?: "ok"

    private fun onSessionEvent(event: CarLinkSessionEvent) {
        when (event) {
            is CarLinkSessionEvent.Connected -> {
                currentTransport = event.device.transport
                stateStore.publish(ProjectionState.Connected)
            }
            is CarLinkSessionEvent.Disconnected -> {
                stateStore.publish(ProjectionState.Ready)
                // The session really ended: release arbitration hold.
                sessionStoppedListener?.run()
            }
            is CarLinkSessionEvent.Error -> {
                val code = when (event.code) {
                    "PROVIDER_UNAVAILABLE" -> ProjectionErrorCode.PROVIDER_UNAVAILABLE
                    "CONNECT_FAILED" -> ProjectionErrorCode.CONNECT_FAILED
                    "TIMEOUT" -> ProjectionErrorCode.TIMEOUT
                    "AUTH" -> ProjectionErrorCode.AUTHENTICATION_FAILED
                    "ADAPTER_FAILURE" -> ProjectionErrorCode.PROTOCOL_ERROR
                    "RESOURCE_CONFLICT" -> ProjectionErrorCode.RESOURCE_CONFLICT
                    else -> ProjectionErrorCode.PROTOCOL_ERROR
                }
                stateStore.publish(
                    ProjectionState.Error(
                        code = code,
                        message = event.message,
                        cause = event.cause,
                        backendId = ID,
                    ),
                )
                // A failed session holds nothing; if it was up, it is gone now.
                if (!controller.sessionActive) sessionStoppedListener?.run()
            }
        }
    }

    companion object {
        const val ID = "carlink"
        const val PROVIDER_UNAVAILABLE_MESSAGE = "CarLink protocol provider unavailable"
    }
}
