// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.receiver.transport.aoa.UsbAccessoryScanner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 14+ dynamic receiver registration: the USB scanner must request an
 * explicit export flag on API 33+ (RECEIVER_NOT_EXPORTED = 4) while older
 * platforms keep the classic registration. The USB attach/detach broadcasts
 * and the app-scoped USB permission reply are delivered to NOT_EXPORTED
 * receivers (they come from system components).
 */
@RunWith(RobolectricTestRunner::class)
class UsbAccessoryScannerFlagsTest {

    @Test
    @Config(sdk = [34])
    fun api34UsesReceiverNotExported() {
        assertEquals(0x4, UsbAccessoryScanner.receiverFlags())
    }

    @Test
    @Config(sdk = [33])
    fun api33UsesReceiverNotExported() {
        assertEquals(0x4, UsbAccessoryScanner.receiverFlags())
    }

    @Test
    @Config(sdk = [28])
    fun legacyApiKeepsClassicRegistration() {
        assertEquals(0, UsbAccessoryScanner.receiverFlags())
    }
}
