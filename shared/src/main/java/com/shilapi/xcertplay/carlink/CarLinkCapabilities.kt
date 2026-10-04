package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionTransport

/**
 * Capability profile for an ICCOA CarLink session.
 *
 * Values describe what the CarLink framework targets (Xiaomi CarWith,
 * vivo Jovi InCar, OPPO Car+ over ICCOA CarLink); they are NOT a compatibility
 * claim — see docs/MULTI_PROJECTION.md.
 */
object CarLinkCapabilities {
    /** Video codecs the CarLink framework is prepared to negotiate. */
    val videoCodecs = setOf(
        com.shilapi.xcertplay.projection.ProjectionVideoCodec.H264,
        com.shilapi.xcertplay.projection.ProjectionVideoCodec.H265,
    )

    /** Audio roles an Android phone projection may open. */
    val audioChannels = setOf(
        com.shilapi.xcertplay.projection.ProjectionAudioChannel.MEDIA,
        com.shilapi.xcertplay.projection.ProjectionAudioChannel.NAVIGATION,
        com.shilapi.xcertplay.projection.ProjectionAudioChannel.PHONE_CALL,
        com.shilapi.xcertplay.projection.ProjectionAudioChannel.VOICE_ASSISTANT,
    )

    /** Input kinds the CarLink input channel will accept. */
    val inputKinds = setOf(
        com.shilapi.xcertplay.projection.ProjectionInputKind.TOUCH,
        com.shilapi.xcertplay.projection.ProjectionInputKind.MULTI_TOUCH,
        com.shilapi.xcertplay.projection.ProjectionInputKind.KEY,
    )

    /** Vendor ecosystems routed through ICCOA CarLink today (framework target). */
    val targetEcosystems = listOf("Xiaomi CarWith", "vivo Jovi InCar", "OPPO Car+")

    const val MAX_TOUCH_CONTACTS = 10

    /**
     * Reserves for future BLE/Bluetooth discovery. Off until the official
     * protocol requirements are known — never hard-code speculative claims.
     */
    const val BLUETOOTH_DISCOVERY_RESERVED = false

    /**
     * Reserves for a future microphone uplink (voice assistant / calls). Off
     * until the official protocol requirements are known.
     */
    const val MICROPHONE_UPLINK_RESERVED = false

    /**
     * Shared hardware a CarLink session needs, computed from the negotiated
     * [transport] instead of a fixed list:
     *
     *  - USB CarLink      → USB + AUDIO
     *  - Wireless CarLink → WIFI + AUDIO
     *  - unknown          → nothing. Without a resolved device the transport is
     *    unknown and no session hardware may be claimed speculatively — the
     *    connect fails cleanly instead of holding AUDIO (or USB/WIFI) hostage.
     *
     * Reserved capabilities ([BLUETOOTH_DISCOVERY_RESERVED],
     * [MICROPHONE_UPLINK_RESERVED]) add BLUETOOTH / MICROPHONE when the real
     * protocol is known to need them.
     */
    fun resourcesFor(transport: ProjectionTransport): Set<ProjectionResource> {
        if (transport == ProjectionTransport.UNKNOWN) return emptySet()
        val resources = mutableSetOf(ProjectionResource.AUDIO)
        when (transport) {
            ProjectionTransport.USB -> resources.add(ProjectionResource.USB)
            ProjectionTransport.WIFI,
            ProjectionTransport.WIFI_DIRECT,
            ProjectionTransport.WIFI_HOTSPOT,
            -> resources.add(ProjectionResource.WIFI)
            ProjectionTransport.BLUETOOTH ->
                if (BLUETOOTH_DISCOVERY_RESERVED) resources.add(ProjectionResource.BLUETOOTH)
            ProjectionTransport.UNKNOWN -> Unit
        }
        if (MICROPHONE_UPLINK_RESERVED) resources.add(ProjectionResource.MICROPHONE)
        return resources
    }
}
