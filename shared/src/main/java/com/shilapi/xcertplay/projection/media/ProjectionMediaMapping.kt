package com.shilapi.xcertplay.projection.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AudioChannel
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.AudioChannelMapper
import com.shilapi.xcertplay.media.AudioChannelSelection
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionSampleFormat
import com.shilapi.xcertplay.projection.ProjectionVideoCodec

/**
 * Pure mapping tables between the backend-neutral projection media vocabulary
 * and the existing CarPlay/shared media types. No side effects; unit tested.
 */
object ProjectionMediaMapping {

    // ---- Video ----

    fun toSharedCodec(codec: ProjectionVideoCodec): VideoCodec = when (codec) {
        ProjectionVideoCodec.H264 -> VideoCodec.H264
        ProjectionVideoCodec.H265 -> VideoCodec.H265
    }

    fun toProjectionCodec(codec: VideoCodec): ProjectionVideoCodec = when (codec) {
        VideoCodec.H264 -> ProjectionVideoCodec.H264
        VideoCodec.H265 -> ProjectionVideoCodec.H265
    }

    // ---- Audio channels ----

    /**
     * Maps a protocol wire `audioType` string onto the neutral channel role.
     * CarPlay wire names are used as the canonical input because every backend
     * ultimately presents music / navigation / call / assistant audio.
     */
    fun channelForWireType(audioType: String, payloadType: Int = 0): ProjectionAudioChannel =
        channelForWireType(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE)

    internal fun channelForWireType(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
    ): ProjectionAudioChannel =
        toProjectionChannel(AudioChannelMapper.map(audioType, payloadType, mode).channel)

    internal fun toProjectionChannel(channel: AudioChannel): ProjectionAudioChannel = when (channel) {
        AudioChannel.MEDIA -> ProjectionAudioChannel.MEDIA
        AudioChannel.NAVIGATION -> ProjectionAudioChannel.NAVIGATION
        AudioChannel.PHONE -> ProjectionAudioChannel.PHONE_CALL
        AudioChannel.ASSISTANT -> ProjectionAudioChannel.VOICE_ASSISTANT
    }

    /** Reverse direction for renderers still keyed on the internal [AudioChannel]. */
    internal fun toSharedChannel(channel: ProjectionAudioChannel): AudioChannel = when (channel) {
        ProjectionAudioChannel.MEDIA -> AudioChannel.MEDIA
        ProjectionAudioChannel.NAVIGATION -> AudioChannel.NAVIGATION
        ProjectionAudioChannel.PHONE_CALL -> AudioChannel.PHONE
        ProjectionAudioChannel.VOICE_ASSISTANT -> AudioChannel.ASSISTANT
    }

    internal fun toSharedSelection(
        format: ProjectionAudioFormat,
        mode: AudioChannelMappingMode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
        navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    ): AudioChannelSelection =
        AudioChannelMapper.map(format.wireType, format.payloadType, mode, navigationStreamType)

    // ---- Audio formats ----

    fun toSharedFormat(format: ProjectionAudioFormat): AudioFormat = AudioFormat(
        codec = toSharedAudioCodec(format.codec),
        sampleRate = format.sampleRate,
        channels = format.channels,
        payloadType = format.payloadType,
        // Backend-neutral callers leave wireType empty; the channel role then
        // determines the routing label so nothing depends on Apple wire strings.
        audioType = format.wireType.ifEmpty { wireTypeFor(format.channel) },
    )

    fun toProjectionFormat(format: AudioFormat, channel: ProjectionAudioChannel): ProjectionAudioFormat =
        ProjectionAudioFormat(
            codec = toProjectionAudioCodec(format.codec),
            sampleRate = format.sampleRate,
            channels = format.channels,
            channel = channel,
            payloadType = format.payloadType,
            wireType = format.audioType,
        )

    fun toSharedAudioCodec(codec: ProjectionAudioCodec): AudioCodecKind = when (codec) {
        ProjectionAudioCodec.AAC_LC -> AudioCodecKind.AAC_LC
        ProjectionAudioCodec.OPUS -> AudioCodecKind.OPUS
        ProjectionAudioCodec.LPCM -> AudioCodecKind.LPCM
    }

    fun toPcmEncoding(format: ProjectionSampleFormat): com.shilapi.xcertplay.media.PcmEncoding =
        when (format) {
            ProjectionSampleFormat.PCM_S16_LE -> com.shilapi.xcertplay.media.PcmEncoding.PCM_S16_LE
            ProjectionSampleFormat.PCM_S16_BE -> com.shilapi.xcertplay.media.PcmEncoding.PCM_S16_BE
        }

    fun toProjectionAudioCodec(codec: AudioCodecKind): ProjectionAudioCodec = when (codec) {
        AudioCodecKind.AAC_LC -> ProjectionAudioCodec.AAC_LC
        AudioCodecKind.OPUS -> ProjectionAudioCodec.OPUS
        AudioCodecKind.LPCM -> ProjectionAudioCodec.LPCM
    }

    /** Bridges a neutral stream id onto the shared `AudioStreamId(type, audioType)`. */
    fun toSharedStreamId(id: ProjectionAudioStreamId): AudioStreamId = AudioStreamId(
        type = id.stream,
        audioType = wireTypeFor(id.channel),
    )

    fun wireTypeFor(channel: ProjectionAudioChannel): String = when (channel) {
        ProjectionAudioChannel.MEDIA -> "media"
        ProjectionAudioChannel.NAVIGATION -> "alert"
        ProjectionAudioChannel.PHONE_CALL -> "telephony"
        ProjectionAudioChannel.VOICE_ASSISTANT -> "speechrecognition"
    }
}
