// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionTransport

/**
 * Which physical transport ONE CarLife attempt uses (Phase 9.2W-A).
 *
 * CarLife has exactly ONE backend ([CarLifeProjectionBackend]); the transport
 * is a property of the connect REQUEST, never a separate backend. Selection is
 * deterministic: it comes from the typed request field
 * [ProjectionDevice.transport] and is resolved ONCE at connect — never from a
 * stale value of a previous session, and never silently converted between USB
 * and wireless.
 *
 * Enabled in this phase: [USB_AOA] (real-device proven) and [WIFI_AP]
 * (wireless AP / same LAN). [WIFI_DIRECT] remains EXPERIMENTAL_DISABLED: the
 * modern Android Wi-Fi Direct / Bluetooth bootstrap work (BLUETOOTH_CONNECT,
 * BLUETOOTH_SCAN, NEARBY_WIFI_DEVICES, RFCOMM) is NOT in this phase.
 *
 * WIFI_AP maps to the upstream runtime value CONNECTION_TYPE_HOTSPOT
 * (wire name kept verbatim; the UI may say "Wireless AP / Same LAN" because
 * the upstream name is misleading — this transport assumes the phone and the
 * head unit ALREADY have IP connectivity and does not create a hotspot).
 */
enum class CarLifeTransport {
    /** USB accessory mode (AOA) — the proven wired path. */
    USB_AOA,

    /** Wireless AP / same LAN: UDP discovery + TCP channels over IP. */
    WIFI_AP,

    /** Wi-Fi Direct + Bluetooth bootstrap — EXPERIMENTAL_DISABLED. */
    WIFI_DIRECT;

    /** WIFI_DIRECT is defined but never selectable in Phase 9.2W-A. */
    val isEnabled: Boolean
        get() = this != WIFI_DIRECT

    /** Upstream runtime value (CarLifeContext constants) — never renamed. */
    fun connectType(): Int = when (this) {
        USB_AOA -> CarLifeContext.CONNECTION_TYPE_AOA
        WIFI_AP -> CarLifeContext.CONNECTION_TYPE_HOTSPOT
        WIFI_DIRECT -> CarLifeContext.CONNECTION_TYPE_WIFIDIRECT
    }

    /**
     * Shared hardware this transport needs. USB_AOA claims USB, WIFI_AP claims
     * WIFI — never both; AUDIO is shared by every CarLife transport.
     */
    fun resources(): Set<ProjectionResource> = when (this) {
        USB_AOA -> setOf(ProjectionResource.USB, ProjectionResource.AUDIO)
        WIFI_AP -> setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO)
        WIFI_DIRECT -> setOf(
            ProjectionResource.WIFI,
            ProjectionResource.BLUETOOTH,
            ProjectionResource.AUDIO,
        )
    }

    companion object {
        /**
         * Deterministic resolution of a CarLife connect request.
         *
         *  - USB / UNKNOWN / no device  → [USB_AOA] (the legacy wired auto
         *    connect request — unchanged Phase 9.1 behavior)
         *  - WIFI / WIFI_HOTSPOT        → [WIFI_AP] ("Wireless AP / Same LAN")
         *  - WIFI_DIRECT                → [WIFI_DIRECT] (rejected as disabled)
         *  - BLUETOOTH                  → null (no CarLife transport; rejected)
         *
         * USB is NEVER silently converted into wireless or vice versa.
         */
        fun forDevice(device: ProjectionDevice?): CarLifeTransport? = when (device?.transport) {
            null, ProjectionTransport.UNKNOWN, ProjectionTransport.USB -> USB_AOA
            ProjectionTransport.WIFI, ProjectionTransport.WIFI_HOTSPOT -> WIFI_AP
            ProjectionTransport.WIFI_DIRECT -> WIFI_DIRECT
            ProjectionTransport.BLUETOOTH -> null
        }

        /** Upstream runtime value → transport (diagnostics display). */
        fun fromConnectType(type: Int): CarLifeTransport = when (type) {
            CarLifeContext.CONNECTION_TYPE_HOTSPOT -> WIFI_AP
            CarLifeContext.CONNECTION_TYPE_WIFIDIRECT -> WIFI_DIRECT
            else -> USB_AOA
        }
    }
}
