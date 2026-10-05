// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9.2W-A transport model: deterministic request resolution (never a
 * silent USB <-> wireless conversion), upstream connect-type values kept
 * verbatim, transport-scoped resources, WIFI_DIRECT disabled.
 */
class CarLifeTransportModelTest {

    private fun device(transport: ProjectionTransport) =
        ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID, transport = transport)

    // ---- Upstream connect-type mapping (wire values verbatim) ----

    @Test
    fun connectTypesMatchUpstreamConstants() {
        assertEquals(CarLifeContext.CONNECTION_TYPE_AOA, CarLifeTransport.USB_AOA.connectType())
        assertEquals(CarLifeContext.CONNECTION_TYPE_HOTSPOT, CarLifeTransport.WIFI_AP.connectType())
        assertEquals(CarLifeContext.CONNECTION_TYPE_WIFIDIRECT, CarLifeTransport.WIFI_DIRECT.connectType())
        assertEquals(CarLifeTransport.WIFI_AP, CarLifeTransport.fromConnectType(CarLifeContext.CONNECTION_TYPE_HOTSPOT))
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.fromConnectType(CarLifeContext.CONNECTION_TYPE_AOA))
    }

    // ---- Deterministic request resolution ----

    @Test
    fun usbAndLegacyRequestsResolveToUsbAoa() {
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.forDevice(null))
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.forDevice(device(ProjectionTransport.UNKNOWN)))
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.forDevice(device(ProjectionTransport.USB)))
    }

    @Test
    fun wirelessRequestsResolveToWifiAp() {
        assertEquals(CarLifeTransport.WIFI_AP, CarLifeTransport.forDevice(device(ProjectionTransport.WIFI)))
        assertEquals(CarLifeTransport.WIFI_AP, CarLifeTransport.forDevice(device(ProjectionTransport.WIFI_HOTSPOT)))
    }

    @Test
    fun usbIsNeverSilentlyConvertedToWirelessOrViceVersa() {
        // Explicit requests stay distinct in BOTH directions.
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.forDevice(device(ProjectionTransport.USB)))
        assertEquals(CarLifeTransport.WIFI_AP, CarLifeTransport.forDevice(device(ProjectionTransport.WIFI)))
    }

    @Test
    fun wifiDirectIsResolvedButDisabled() {
        val transport = CarLifeTransport.forDevice(device(ProjectionTransport.WIFI_DIRECT))
        assertEquals(CarLifeTransport.WIFI_DIRECT, transport)
        assertFalse("WIFI_DIRECT stays EXPERIMENTAL_DISABLED", transport!!.isEnabled)
    }

    @Test
    fun bluetoothRequestResolvesToBtHotspot() {
        // Phase 9.2W-B1 contract change (was rejected in 9.2W-A): a BLUETOOTH
        // request is the modern Bluetooth + phone-hotspot bootstrap.
        assertEquals(
            CarLifeTransport.BT_HOTSPOT,
            CarLifeTransport.forDevice(device(ProjectionTransport.BLUETOOTH)),
        )
    }

    // ---- Transport-scoped resources (section 25) ----

    @Test
    fun usbAoaClaimsUsbAndAudioOnly() {
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            CarLifeTransport.USB_AOA.resources(),
        )
        assertFalse(CarLifeTransport.USB_AOA.resources().contains(ProjectionResource.WIFI))
    }

    @Test
    fun wifiApClaimsWifiAndAudioOnly() {
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            CarLifeTransport.WIFI_AP.resources(),
        )
        assertFalse(CarLifeTransport.WIFI_AP.resources().contains(ProjectionResource.USB))
        assertFalse(CarLifeTransport.WIFI_AP.resources().contains(ProjectionResource.BLUETOOTH))
    }

    @Test
    fun enabledTransportsAreExactlyUsbAndWifiAp() {
        assertTrue(CarLifeTransport.USB_AOA.isEnabled)
        assertTrue(CarLifeTransport.WIFI_AP.isEnabled)
        assertFalse(CarLifeTransport.WIFI_DIRECT.isEnabled)
    }
}
