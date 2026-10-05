package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder config equality: same CSD + codec but a DIFFERENT coded size is a new
 * decoder setup and must force a reconfigure (dynamic resolution switching).
 */
class VideoConfigEqualityTest {

    private val csd = byteArrayOf(0x67, 0x42.toByte(), 0x00, 0x1e)

    @Test
    fun identicalConfigIsADuplicate() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 720)
        assertTrue(first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H264, csd.copyOf(), 1280, 720)))
    }

    @Test
    fun sizeChangeForcesReconfigureEvenWithSameCsd() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 720)
        // 1280x720 -> 1920x1080 with identical SPS/PPS: NOT a duplicate.
        assertFalse(
            first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H264, csd.copyOf(), 1920, 1080)),
        )
    }

    @Test
    fun heightChangeAloneForcesReconfigure() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1920, 720)
        assertFalse(first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H264, csd.copyOf(), 1920, 1080)))
    }

    @Test
    fun widthChangeAloneForcesReconfigure() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 1080)
        assertFalse(first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H264, csd.copyOf(), 1920, 1080)))
    }

    @Test
    fun codecChangeForcesReconfigure() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 720)
        assertFalse(first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H265, csd.copyOf(), 1280, 720)))
    }

    @Test
    fun csdChangeForcesReconfigure() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 720)
        assertFalse(
            first.sameDecoderSetupAs(VideoJob.Config(VideoCodec.H264, byteArrayOf(1, 2, 3), 1280, 720)),
        )
    }

    @Test
    fun nullPreviousIsNotADuplicate() {
        val first = VideoJob.Config(VideoCodec.H264, csd, 1280, 720)
        assertFalse(first.sameDecoderSetupAs(null))
    }
}
