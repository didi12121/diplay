package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarPlay status -> neutral [ProjectionState] mapping. */
class CarPlayStateMappingTest {

    @Test
    fun setupStatesMapToInitializing() {
        for (status in listOf(
            CarPlayStatus.DiscoveringMfi,
            CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission,
            CarPlayStatus.StartingHotspot,
        )) {
            assertEquals(ProjectionState.Initializing, CarPlayStateMapping.toProjectionState(status))
        }
    }

    @Test
    fun readyStatesMapToReady() {
        assertEquals(ProjectionState.Ready, CarPlayStateMapping.toProjectionState(CarPlayStatus.MfiReady))
        assertEquals(
            ProjectionState.Ready,
            CarPlayStateMapping.toProjectionState(CarPlayStatus.HotspotReady("ssid", "5G", 36, "bssid", "addr", "p2p")),
        )
    }

    @Test
    fun discoveryStatesMapToDiscovering() {
        for (status in listOf(
            CarPlayStatus.WaitingForPairedIphone,
            CarPlayStatus.DiscoveringIphone,
            CarPlayStatus.WaitingForIphone,
            CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.WaitingForReenumeration,
        )) {
            assertEquals(ProjectionState.Discovering, CarPlayStateMapping.toProjectionState(status))
        }
    }

    @Test
    fun negotiationStatesMapToConnecting() {
        for (status in listOf(
            CarPlayStatus.ConnectingBluetooth,
            CarPlayStatus.SelectingConfiguration,
            CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing,
            CarPlayStatus.ConnectingControl,
            CarPlayStatus.AttachingNetwork,
        )) {
            assertEquals(ProjectionState.Connecting, CarPlayStateMapping.toProjectionState(status))
        }
    }

    @Test
    fun runningStatesMapToConnected() {
        for (status in listOf(
            CarPlayStatus.RunningWireless,
            CarPlayStatus.WirelessActive,
            CarPlayStatus.RunningControl,
        )) {
            assertEquals(ProjectionState.Connected, CarPlayStateMapping.toProjectionState(status))
        }
    }

    @Test
    fun controlEndedMapsToDisconnecting() {
        assertEquals(ProjectionState.Disconnecting, CarPlayStateMapping.toProjectionState(CarPlayStatus.ControlEnded))
    }

    @Test
    fun failureCarriesCodeMessageAndBackendId() {
        val state = CarPlayStateMapping.toProjectionState(CarPlayStatus.Failed("USB permission denied"))
        assertTrue(state is ProjectionState.Error)
        state as ProjectionState.Error
        assertEquals(ProjectionErrorCode.TRANSPORT_ERROR, state.code)
        assertEquals("USB permission denied", state.message)
        assertEquals("carplay", state.backendId)
    }

    @Test
    fun everyStatusHasAMapping() {
        val statuses = listOf(
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission,
            CarPlayStatus.MfiReady, CarPlayStatus.StartingHotspot, CarPlayStatus.WaitingForPairedIphone,
            CarPlayStatus.ConnectingBluetooth, CarPlayStatus.RunningWireless, CarPlayStatus.WirelessActive,
            CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone, CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.WaitingForReenumeration, CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl, CarPlayStatus.AttachingNetwork,
            CarPlayStatus.RunningControl, CarPlayStatus.ControlEnded,
        )
        for (status in statuses) {
            // No exception and not an Error state for normal statuses.
            val mapped = CarPlayStateMapping.toProjectionState(status)
            assertTrue("status $status mapped to $mapped", mapped !is ProjectionState.Error)
        }
    }
}
