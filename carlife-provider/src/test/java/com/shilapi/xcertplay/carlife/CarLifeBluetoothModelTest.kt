// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.os.Build
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 9.2W-B1 transport/permission/target model:
 *  - BT_HOTSPOT resources {WIFI, BLUETOOTH, AUDIO}; legacy USB/WIFI_AP kept
 *  - BLUETOOTH request maps to BT_HOTSPOT (never to legacy WIFI_AP)
 *  - permission gates: BLUETOOTH_CONNECT on API 31+, never BLUETOOTH_SCAN,
 *    no runtime request below 31
 *  - API 33+ receiver export flags
 *  - deterministic target selection: exact-name match only, no fallback
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeBluetoothModelTest {

    private fun device(transport: ProjectionTransport, name: String = "vivo X") =
        ProjectionDevice("d", name, CarLifeProjectionBackend.ID, transport = transport)

    // ---- Transport model ----

    @Test
    fun btHotspotClaimsWifiBluetoothAndAudioNeverUsb() {
        val resources = CarLifeTransport.BT_HOTSPOT.resources()
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.BLUETOOTH, ProjectionResource.AUDIO),
            resources,
        )
        assertFalse(resources.contains(ProjectionResource.USB))
    }

    @Test
    fun legacyTransportsKeepTheirResources() {
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            CarLifeTransport.USB_AOA.resources(),
        )
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            CarLifeTransport.WIFI_AP.resources(),
        )
        assertFalse(CarLifeTransport.WIFI_AP.resources().contains(ProjectionResource.BLUETOOTH))
    }

    @Test
    fun bluetoothRequestMapsToBtHotspotNotLegacyAp() {
        assertEquals(CarLifeTransport.BT_HOTSPOT, CarLifeTransport.forDevice(device(ProjectionTransport.BLUETOOTH)))
        assertEquals(CarLifeTransport.WIFI_AP, CarLifeTransport.forDevice(device(ProjectionTransport.WIFI)))
        assertEquals(CarLifeTransport.USB_AOA, CarLifeTransport.forDevice(device(ProjectionTransport.USB)))
    }

    @Test
    fun btHotspotAndLegacyApShareTheUpstreamHotspotConnectType() {
        // Same TCP/protocol stack below the bootstrap; wire value verbatim.
        assertEquals(CarLifeTransport.WIFI_AP.connectType(), CarLifeTransport.BT_HOTSPOT.connectType())
        assertTrue(CarLifeTransport.BT_HOTSPOT.isEnabled)
        assertFalse(CarLifeTransport.WIFI_DIRECT.isEnabled)
    }

    // ---- Permission gates ----

    @Test
    fun android12PlusNeedsOnlyBluetoothConnect() {
        val permissions = CarLifeBluetoothPermissions.runtimePermissions(31)
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), permissions)
        assertFalse("BLUETOOTH_SCAN must never be requested", permissions.any { it.endsWith("BLUETOOTH_SCAN") })
        assertTrue(CarLifeBluetoothPermissions.needsRuntimeGrant(31))
        assertTrue(CarLifeBluetoothPermissions.runtimePermissions(34).all { it.endsWith("BLUETOOTH_CONNECT") })
    }

    @Test
    fun legacyAndroidNeedsNoRuntimeGrant() {
        assertTrue(CarLifeBluetoothPermissions.runtimePermissions(28).isEmpty())
        assertFalse(CarLifeBluetoothPermissions.needsRuntimeGrant(28))
        assertTrue(CarLifeBluetoothPermissions.runtimePermissions(30).isEmpty())
        assertFalse(CarLifeBluetoothPermissions.needsRuntimeGrant(30))
    }

    // ---- API 33+ dynamic receiver flags (section 9) ----

    @Test
    @Config(sdk = [28])
    fun bluetoothReceiverFlagsAreZeroBelowApi33() {
        assertEquals(0, com.baidu.carlife.sdk.receiver.transport.instant.BluetoothDeviceDiscover.receiverFlags())
    }

    @Test
    @Config(sdk = [33])
    fun bluetoothReceiverFlagsAreNotExportedOnApi33() {
        // RECEIVER_NOT_EXPORTED = 4.
        assertEquals(0x4, com.baidu.carlife.sdk.receiver.transport.instant.BluetoothDeviceDiscover.receiverFlags())
    }

    @Test
    @Config(sdk = [34])
    fun bluetoothReceiverFlagsStayNotExportedOnApi34Plus() {
        assertEquals(0x4, com.baidu.carlife.sdk.receiver.transport.instant.BluetoothDeviceDiscover.receiverFlags())
    }

    // ---- Deterministic target selection ----

    @Test
    fun targetSelectionMatchesExactBondedName() {
        val bonded = listOf("vivo X27", "vivo X", "Mi Phone")
        assertEquals("vivo X", selectBluetoothTarget(bonded, "vivo X"))
    }

    @Test
    fun targetSelectionNeverFallsBackToAnotherDevice() {
        val bonded = listOf("vivo X27", "Mi Phone")
        assertNull(selectBluetoothTarget(bonded, "vivo X"))
        assertNull(selectBluetoothTarget(bonded, null))
        assertNull(selectBluetoothTarget(bonded, ""))
        assertNull(selectBluetoothTarget(bonded, "   "))
        assertNull(selectBluetoothTarget(emptyList(), "vivo X"))
    }

    @Test
    fun targetSelectionTrimsButDoesNotPartiallyMatch() {
        val bonded = listOf("vivo X27")
        assertEquals("vivo X27", selectBluetoothTarget(bonded, "  vivo X27  "))
        assertNull(selectBluetoothTarget(bonded, "vivo"))
        assertNull(selectBluetoothTarget(bonded, "VIVO X27")) // case-exact
    }
}
