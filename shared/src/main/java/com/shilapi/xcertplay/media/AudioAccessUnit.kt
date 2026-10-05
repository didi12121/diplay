package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind

/** Byte order of raw 16-bit PCM samples delivered to the shared renderer. */
enum class PcmEncoding {
    /** Android AudioTrack consumes little-endian PCM natively. */
    PCM_S16_LE,

    /** Big-endian PCM16 (Apple wired LPCM); the renderer byte-swaps it. */
    PCM_S16_BE,
}

/**
 * One protocol-neutral audio access unit — a raw AAC-LC access unit, an Opus
 * packet, or a PCM16 block. CarPlay adapts its RTP packets into this shape
 * ([AirPlayAudioAdapter]); CarLink delivers raw frames directly. The shared
 * renderer never sees, and never fabricates, an RTP header.
 */
class AudioAccessUnit(
    /** Payload buffer; may contain bytes outside [offset]..[offset]+[length]. */
    val payload: ByteArray,
    val offset: Int = 0,
    val length: Int = payload.size - offset,
    /** Presentation timestamp in microseconds; propagated to MediaCodec as-is. */
    val presentationTimeUs: Long = PTS_UNSPECIFIED,
) {
    /** Zero-copy window when the unit spans the whole buffer. */
    fun window(): ByteArray =
        if (offset == 0 && length == payload.size) payload
        else payload.copyOfRange(offset, offset + length)

    companion object {
        /** Unknown PTS: renderers fall back to a monotonic clock. */
        const val PTS_UNSPECIFIED: Long = -1L
    }
}

/**
 * CarPlay-side adapter: AirPlay RTP packet → [AudioAccessUnit]. All RTP
 * framing knowledge lives here — the 12-byte header is stripped once, and the
 * RTP timestamp is converted to microseconds, before the shared renderer sees
 * the data. This is the only place that knows about RTP.
 */
internal object AirPlayAudioAdapter {
    /** AirPlay/CarPlay RTP carries a 12-byte clear header in front of the payload. */
    const val RTP_HEADER_BYTES = 12

    fun presentationTimeUs(sample: Int, sampleRate: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / sampleRate

    fun accessUnit(rtp: ByteArray, sample: Int, sampleRate: Int): AudioAccessUnit {
        val length = (rtp.size - RTP_HEADER_BYTES).coerceAtLeast(0)
        return AudioAccessUnit(
            payload = rtp,
            offset = RTP_HEADER_BYTES,
            length = length,
            presentationTimeUs = presentationTimeUs(sample, sampleRate),
        )
    }
}

/**
 * Codec dispatch shared by every protocol: raw access units in, renderer
 * actions out. CarPlay RTP reaches this through [AirPlayAudioAdapter]; CarLink
 * raw frames reach it directly — no fake headers are ever synthesized.
 */
internal class AudioAccessUnitRouter(
    private val codec: AudioCodecKind,
    private val sampleRate: Int,
    private val channels: Int,
    private val pcmEncoding: PcmEncoding,
    private val onPcm: (ByteArray) -> Unit,
    private val onEncoded: (accessUnit: ByteArray, presentationTimeUs: Long) -> Unit,
    private val onDropped: (reason: String, bytes: Int) -> Unit = { _, _ -> },
) {
    fun route(unit: AudioAccessUnit) {
        when (codec) {
            AudioCodecKind.LPCM -> onPcm(pcm16(unit.window()))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = unit.window()
                if (accessUnit.isEmpty()) {
                    onDropped("empty-aac-access-unit", 0)
                    return
                }
                onEncoded(
                    MediaCodecSupport.adtsFrame(accessUnit, sampleRate, channels),
                    unit.presentationTimeUs,
                )
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = unit.window()
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    onDropped("short-opus-packet", accessUnit.size)
                    return
                }
                onEncoded(accessUnit, unit.presentationTimeUs)
            }
        }
    }

    /** Applies the stream's declared PCM byte order to the AudioTrack layout. */
    private fun pcm16(data: ByteArray): ByteArray = when (pcmEncoding) {
        PcmEncoding.PCM_S16_LE -> data
        PcmEncoding.PCM_S16_BE -> byteSwapS16(data)
    }

    private companion object {
        const val MIN_OPUS_PACKET_BYTES = 4
    }
}

/** Byte-swaps 16-bit samples in place-order; odd trailing bytes pass through. */
internal fun byteSwapS16(source: ByteArray): ByteArray {
    val out = ByteArray(source.size)
    var i = 0
    while (i + 1 < source.size) {
        out[i] = source[i + 1]
        out[i + 1] = source[i]
        i += 2
    }
    if (i < source.size) out[i] = source[i]
    return out
}
