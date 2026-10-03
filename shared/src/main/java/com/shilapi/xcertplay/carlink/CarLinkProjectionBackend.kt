package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
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
import com.shilapi.xcertplay.projection.ProjectionVideoSink

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
 * CarLinkProtocolAdapter   ← MockCarLinkProtocolAdapter today,
 *       ↓                     OfficialCarLinkSdkAdapter / NativeCarLinkProtocolAdapter later
 * ICCOA CarLink SDK / protocol
 * ```
 *
 * With no SDK installed the backend reports
 * `CarLink protocol provider unavailable` instead of pretending to connect.
 */
class CarLinkProjectionBackend(
    private val adapter: CarLinkProtocolAdapter,
    videoSink: ProjectionVideoSink = ProjectionVideoSink.NOOP,
    audioSink: ProjectionAudioSink = ProjectionAudioSink.NOOP,
    logger: ProjectionLogger = ProjectionLogger.NONE,
) : ProjectionBackend {

    override val id: String = ID

    override val displayName: String = "Android CarLink"

    override val capabilities: ProjectionCapabilities = ProjectionCapabilities(
        videoCodecs = CarLinkCapabilities.videoCodecs,
        audioChannels = CarLinkCapabilities.audioChannels,
        inputKinds = CarLinkCapabilities.inputKinds,
        maxTouchContacts = CarLinkCapabilities.MAX_TOUCH_CONTACTS,
        microphoneUplink = false,
        metadata = true,
        navigation = true,
    )

    override val requiredResources: Set<ProjectionResource> = setOf(
        ProjectionResource.USB,
        ProjectionResource.WIFI,
        ProjectionResource.AUDIO,
    )

    private val stateStore = ProjectionStateStore(ID, logger)
    private val controller = CarLinkController(adapter, videoSink, audioSink, logger, ID)

    override val state: ProjectionState
        get() = stateStore.state

    /** Devices discovery found so far. */
    val discoveredDevices: List<ProjectionDevice>
        get() = controller.discoveredDevices()

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

    override fun initialize() {
        if (!adapter.isAvailable) {
            stateStore.publish(
                ProjectionState.Error(
                    code = ProjectionErrorCode.PROVIDER_UNAVAILABLE,
                    message = PROVIDER_UNAVAILABLE_MESSAGE,
                    cause = null,
                    backendId = ID,
                ),
            )
            return
        }
        stateStore.publish(ProjectionState.Initializing)
        controller.initialize()
        stateStore.publish(ProjectionState.Ready)
    }

    override fun start() {
        if (!adapter.isAvailable) return
        stateStore.publish(ProjectionState.Discovering)
        controller.startDiscovery()
    }

    override fun stop() {
        controller.stopDiscovery()
        if (stateStore.state is ProjectionState.Connected) {
            stateStore.publish(ProjectionState.Disconnecting)
        }
    }

    override fun connect(device: ProjectionDevice?) {
        if (!adapter.isAvailable) {
            stateStore.publish(
                ProjectionState.Error(
                    code = ProjectionErrorCode.PROVIDER_UNAVAILABLE,
                    message = PROVIDER_UNAVAILABLE_MESSAGE,
                    cause = null,
                    backendId = ID,
                ),
            )
            return
        }
        stateStore.publish(ProjectionState.Connecting)
        controller.connect(device)
    }

    override fun disconnect() {
        if (stateStore.state is ProjectionState.Connected) {
            stateStore.publish(ProjectionState.Disconnecting)
        }
        controller.disconnect()
        stateStore.publish(ProjectionState.Ready)
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

    private fun onSessionEvent(event: CarLinkSessionEvent) {
        when (event) {
            is CarLinkSessionEvent.Connected -> stateStore.publish(ProjectionState.Connected)
            is CarLinkSessionEvent.Disconnected ->
                stateStore.publish(ProjectionState.Ready)
            is CarLinkSessionEvent.Error -> {
                val code = when (event.code) {
                    "PROVIDER_UNAVAILABLE" -> ProjectionErrorCode.PROVIDER_UNAVAILABLE
                    "CONNECT_FAILED" -> ProjectionErrorCode.CONNECT_FAILED
                    "TIMEOUT" -> ProjectionErrorCode.TIMEOUT
                    "AUTH" -> ProjectionErrorCode.AUTHENTICATION_FAILED
                    else -> ProjectionErrorCode.PROTOCOL_ERROR
                }
                stateStore.publish(
                    ProjectionState.Error(
                        code = code,
                        message = event.message,
                        cause = null,
                        backendId = ID,
                    ),
                )
            }
        }
    }

    companion object {
        const val ID = "carlink"
        const val PROVIDER_UNAVAILABLE_MESSAGE = "CarLink protocol provider unavailable"
    }
}
