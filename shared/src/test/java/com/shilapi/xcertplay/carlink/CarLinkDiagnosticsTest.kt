package com.shilapi.xcertplay.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarLink log redaction: secrets must never reach a log line. */
class CarLinkDiagnosticsTest {

    @Test
    fun dropsPasswordAndTokenFragments() {
        val redacted = CarLinkDiagnostics.redact("device=x1 password=hunter2 token=abc123 ok=1")
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("device=x1"))
        assertTrue(redacted.contains("ok=1"))
    }

    @Test
    fun dropsCertificateAndKeyMaterial() {
        val redacted = CarLinkDiagnostics.redact(
            "certificate=MIIDxjCCAqygAwIBAgIJALRvVj8kN0t5MA0GCSqGSIb3DQEBCwUAMHcxCzAJ " +
                "private=AAAA " +
                "auth=BBBB",
        )
        assertFalse(redacted.contains("MIIDxjCC"))
        assertFalse(redacted.contains("AAAA"))
        assertFalse(redacted.contains("BBBB"))
    }

    @Test
    fun dropsBareHexBlobsThatLookLikeKeyMaterial() {
        val hex = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val redacted = CarLinkDiagnostics.redact("keymaterial=$hex")
        assertFalse(redacted.contains(hex))
    }

    @Test
    fun keepsOrdinaryDiagnostics() {
        val redacted = CarLinkDiagnostics.redact("device=mock-xiaomi-carwith state=CONNECTED rtt=12")
        assertEquals("device=mock-xiaomi-carwith state=CONNECTED rtt=12", redacted)
    }

    @Test
    fun diagnosticsEventPassesThroughRedaction() {
        val lines = mutableListOf<String>()
        val diagnostics = CarLinkDiagnostics(logger = { lines.add(it) })
        diagnostics.event("connect", "device=x1 password=leaked")
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("backend=carlink connect"))
        assertFalse(lines.single().contains("leaked"))
    }

    @Test
    fun wifiPassphraseIsDropped() {
        val redacted = CarLinkDiagnostics.redact("ssid=MyCar passphrase=s3cret-band")
        assertFalse(redacted.contains("s3cret-band"))
    }
}
