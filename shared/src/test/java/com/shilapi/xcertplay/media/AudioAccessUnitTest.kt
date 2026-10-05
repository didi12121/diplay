package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol boundary tests: CarPlay RTP is stripped exactly once at the
 * AirPlay adapter, CarLink raw access units are never stripped, PCM byte
 * order is honored, and presentation timestamps survive to the renderer.
 */
class AudioAccessUnitTest {

    private data class Rendered(
        val pcm: ByteArray? = null,
        val encoded: ByteArray? = null,
        val pts: Long = -2,
    )

    private fun router(
        codec: AudioCodecKind,
        pcmEncoding: PcmEncoding,
        sampleRate: Int = 48_000,
        channels: Int = 2,
    ): Pair<AudioAccessUnitRouter, MutableList<Rendered>> {
        val out = mutableListOf<Rendered>()
        val router = AudioAccessUnitRouter(
            codec = codec,
            sampleRate = sampleRate,
            channels = channels,
            pcmEncoding = pcmEncoding,
            onPcm = { out.add(Rendered(pcm = it)) },
            onEncoded = { unit, pts -> out.add(Rendered(encoded = unit, pts = pts)) },
        )
        return router to out
    }

    // ---- CarPlay RTP: header stripped exactly once ----

    @Test
    fun carPlayRtpAacHasItsTwelveByteHeaderRemoved() {
        val rtp = ByteArray(12) { 0x7f } + byteArrayOf(0x11, 0x22, 0x33)
        val unit = AirPlayAudioAdapter.accessUnit(rtp, sample = 48000, sampleRate = 48_000)
        assertEquals(12, unit.offset)
        assertEquals(3, unit.length)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), unit.window())
    }

    @Test
    fun carPlayRtpOpusIsAlsoStrippedExactlyOnce() {
        val rtp = ByteArray(12) + byteArrayOf(1, 2, 3, 4, 5)
        val unit = AirPlayAudioAdapter.accessUnit(rtp, sample = 0, sampleRate = 48_000)
        assertEquals(5, unit.length)
        val (router, out) = router(AudioCodecKind.OPUS, PcmEncoding.PCM_S16_LE)
        router.route(unit)
        // Raw Opus reaches the encoder input with no synthetic framing.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), out.single().encoded)
    }

    @Test
    fun carPlayRtpTimestampConvertsToMicroseconds() {
        // sample 48000 @ 48 kHz = 1 second.
        val unit = AirPlayAudioAdapter.accessUnit(ByteArray(12), sample = 48000, sampleRate = 48_000)
        assertEquals(1_000_000L, unit.presentationTimeUs)
    }

    // ---- CarLink raw: never stripped, never framed ----

    @Test
    fun carLinkRawAacIsNotStripped() {
        val payload = byteArrayOf(0x21, 0x10, 0x56, 0xe5.toByte()) // raw AAC access unit
        val (router, out) = router(AudioCodecKind.AAC_LC, PcmEncoding.PCM_S16_LE)
        router.route(AudioAccessUnit(payload, presentationTimeUs = 33_333))
        val rendered = out.single()
        // ADTS wrapping may add a header, but the raw payload must survive whole.
        assertTrue(rendered.encoded!!.containsSubArray(payload))
        assertEquals(33_333, rendered.pts)
    }

    @Test
    fun carLinkRawOpusIsNotStripped() {
        val payload = byteArrayOf(0x08, 0x01, 0x02, 0x03, 0x04, 0x05)
        val (router, out) = router(AudioCodecKind.OPUS, PcmEncoding.PCM_S16_LE)
        router.route(AudioAccessUnit(payload, presentationTimeUs = 7))
        assertArrayEquals(payload, out.single().encoded)
        assertEquals(7L, out.single().pts)
    }

    // ---- PCM byte order ----

    @Test
    fun littleEndianPcmIsNotSwapped() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val (router, out) = router(AudioCodecKind.LPCM, PcmEncoding.PCM_S16_LE)
        router.route(AudioAccessUnit(payload))
        assertArrayEquals(payload, out.single().pcm)
    }

    @Test
    fun bigEndianPcmIsByteSwapped() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04.toByte())
        val (router, out) = router(AudioCodecKind.LPCM, PcmEncoding.PCM_S16_BE)
        router.route(AudioAccessUnit(payload))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), out.single().pcm)
    }

    @Test
    fun payloadWindowsAreHonoredWithoutCopyingWholeBuffers() {
        val payload = byteArrayOf(9, 9, 0x21, 0x10, 0x56, 0x60, 9)
        val (router, out) = router(AudioCodecKind.OPUS, PcmEncoding.PCM_S16_LE)
        router.route(AudioAccessUnit(payload, offset = 2, length = 4, presentationTimeUs = 5))
        assertArrayEquals(byteArrayOf(0x21, 0x10, 0x56, 0x60), out.single().encoded)
    }

    @Test
    fun unknownPtsFallsThroughAsUnspecified() {
        val (router, out) = router(AudioCodecKind.OPUS, PcmEncoding.PCM_S16_LE)
        router.route(AudioAccessUnit(byteArrayOf(1, 2, 3, 4)))
        assertEquals(AudioAccessUnit.PTS_UNSPECIFIED, out.single().pts)
    }

    private fun ByteArray.containsSubArray(needle: ByteArray): Boolean =
        indices.any { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        }
}
