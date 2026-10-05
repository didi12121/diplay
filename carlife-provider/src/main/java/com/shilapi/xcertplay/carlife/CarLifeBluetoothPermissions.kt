// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.os.Build

/**
 * Bluetooth permission + deterministic target model for the modern wireless
 * bootstrap (Phase 9.2W-B1). Pure and unit-testable; no Android BT calls.
 *
 * Rules (section 8/10/11):
 *  - Android 12+ (API 31+): BLUETOOTH_CONNECT is the runtime permission this
 *    path needs (bonded-device lookup + RFCOMM connect). BLUETOOTH_SCAN is
 *    NEVER requested - the flow performs no discovery/scanning.
 *  - Older Android: legacy install-time BLUETOOTH / BLUETOOTH_ADMIN only -
 *    no runtime request.
 *  - No location permission is required or requested (no scanning APIs).
 *  - Target selection is deterministic: EXACT device-name match on bonded
 *    devices only. Never a reflection `isConnected()` fallback, never a random
 *    paired device. Names only - no MAC addresses are displayed or stored.
 */
object CarLifeBluetoothPermissions {

    /** Runtime permissions this path may request on [sdkInt]; empty when none. */
    fun runtimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf("android.permission.BLUETOOTH_CONNECT")
        } else {
            emptyList()
        }

    /** True when [sdkInt] needs a runtime grant before bonded lookup. */
    fun needsRuntimeGrant(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    /** Final real-device result classifications (section 35) for the BT path. */
    const val REASON_BT_PERMISSION = "BT_PERMISSION"
    const val REASON_BT_TARGET_NOT_SELECTED = "BT_TARGET_NOT_SELECTED"
    const val REASON_BT_TARGET_NOT_BONDED = "BT_TARGET_NOT_BONDED"
    const val REASON_BT_RFCOMM_CONNECT_FAILED = "BT_RFCOMM_CONNECT_FAILED"
    const val REASON_BT_BOOTSTRAP_SILENT = "BT_BOOTSTRAP_SILENT"
    const val REASON_WIRELESS_INFO_NEGOTIATION_FAILED = "WIRELESS_INFO_NEGOTIATION_FAILED"
    const val REASON_WIFI_DIRECT_REQUIRED = "WIFI_DIRECT_REQUIRED"
    const val REASON_PHONE_IP_NOT_PROVIDED = "PHONE_IP_NOT_PROVIDED"
    const val REASON_TCP_CONNECT_FAILED = "TCP_CONNECT_FAILED"
}

/**
 * Deterministic Bluetooth target selection (Phase 9.2W-B1): exact-name match
 * against the BONDED list only. Returns the matched name (never an address) or
 * null; empty [targetName] means "not selected" and never falls back to any
 * device.
 */
fun selectBluetoothTarget(bondedNames: List<String>, targetName: String?): String? {
    val wanted = targetName?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    return bondedNames.firstOrNull { it == wanted }
}
