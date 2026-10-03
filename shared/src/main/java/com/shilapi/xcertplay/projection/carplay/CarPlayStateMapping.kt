package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionState

/**
 * Maps the CarPlay controller's status vocabulary onto the backend-neutral
 * [ProjectionState] machine. Pure function: no side effects, fully unit tested.
 */
object CarPlayStateMapping {
    fun toProjectionState(status: CarPlayStatus, backendId: String = "carplay"): ProjectionState =
        when (status) {
            // Preparing the MFi authentication provider / transport resources.
            CarPlayStatus.DiscoveringMfi,
            CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission,
            -> ProjectionState.Initializing

            CarPlayStatus.MfiReady -> ProjectionState.Ready

            // Bringing up the wireless access point is still setup work.
            CarPlayStatus.StartingHotspot -> ProjectionState.Initializing
            is CarPlayStatus.HotspotReady -> ProjectionState.Ready

            // Looking for the phone.
            CarPlayStatus.WaitingForPairedIphone,
            CarPlayStatus.DiscoveringIphone,
            CarPlayStatus.WaitingForIphone,
            CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.WaitingForReenumeration,
            -> ProjectionState.Discovering

            // Phone found; negotiating the session.
            CarPlayStatus.ConnectingBluetooth,
            CarPlayStatus.SelectingConfiguration,
            CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing,
            CarPlayStatus.ConnectingControl,
            CarPlayStatus.AttachingNetwork,
            -> ProjectionState.Connecting

            // Control loop running: projection is live.
            CarPlayStatus.RunningWireless,
            CarPlayStatus.WirelessActive,
            CarPlayStatus.RunningControl,
            -> ProjectionState.Connected

            CarPlayStatus.ControlEnded -> ProjectionState.Disconnecting

            is CarPlayStatus.Failed -> ProjectionState.Error(
                code = ProjectionErrorCode.TRANSPORT_ERROR,
                message = status.message,
                cause = null,
                backendId = backendId,
            )
        }
}
