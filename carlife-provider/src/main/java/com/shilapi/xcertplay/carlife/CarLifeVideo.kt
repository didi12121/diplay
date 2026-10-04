// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.os.SystemClock
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.TransportListener
import java.io.Closeable

/**
 * Video seam types: the CarLife protocol layer speaks these; the DiPlay
 * projection layer maps them onto `ProjectionVideoConfig`/`ProjectionVideoFrame`
 * and the shared `AndroidMediaSink`. `com.baidu.carlife.*` never depends on
 * DiPlay rendering types.
 */
class CarLifeVideoConfig(
    /** Real coded size from CarlifeVideoEncoderInfo — never guessed. */
    val width: Int,
    val height: Int,
    val frameRate: Int,
    /**
     * Decoder config bytes when the protocol carries them. CarLife V2 sends
     * H.264 with in-band SPS/PPS (Annex-B), so this is empty by design —
     * never fabricate SPS/PPS/VPS or an avcC record.
     */
    val codecData: ByteArray = EMPTY_CODEC_DATA,
) {
    companion object {
        val EMPTY_CODEC_DATA: ByteArray = ByteArray(0)
    }
}

class CarLifeVideoFrame(
    /** Independent copy of the payload window (never an upstream pooled buffer). */
    val payload: ByteArray,
    val offset: Int,
    val length: Int,
    /** See [CarLifeVideoFraming.PTS_SOURCE] — CarLife carries no phone PTS. */
    val presentationTimeUs: Long,
    /** Standard Annex-B NAL analysis (IDR/SPS/PPS), not a size heuristic. */
    val keyFrame: Boolean,
)

/** Session-scoped video callbacks; every event carries its session token. */
interface CarLifeVideoListener {
    fun onVideoConfig(session: CarLifeSessionToken, config: CarLifeVideoConfig)
    fun onVideoFrame(session: CarLifeSessionToken, frame: CarLifeVideoFrame)
    fun onVideoStopped(session: CarLifeSessionToken)
}

/**
 * Framing helpers with the REAL CarLife V2 semantics (audited from
 * `FrameDecoder` / `RemoteDisplayRenderer`):
 *
 *  - payload window = `message.body[commandSize, commandSize + payloadSize)`
 *  - codec is H.264/AVC Annex-B; SPS/PPS are IN-BAND NAL units
 *  - the protocol carries NO frame timestamp; upstream decodes with
 *    `System.nanoTime()/1000` at decode time. DiPlay mirrors this with a
 *    host-receive monotonic clock and labels it
 *    [PTS_SOURCE] = HOST_RECEIVE_SYNTHETIC (never claimed as phone PTS).
 */
object CarLifeVideoFraming {
    const val PTS_SOURCE = "HOST_RECEIVE_SYNTHETIC"

    /** Host-receive monotonic timestamp (µs) used as decoder scheduling PTS. */
    fun hostReceiveTimeUs(clock: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1000 }): Long = clock()

    /**
     * Independent copy of the upstream payload window. The upstream
     * `CarLifeMessage` is pooled and recycled right after dispatch
     * (`MessageDispatcher.run` → `finally message.recycle()`), so a frame may
     * never hold a reference into `message.body`.
     */
    fun copyWindow(body: ByteArray, commandSize: Int, payloadSize: Int): ByteArray =
        body.copyOfRange(commandSize, commandSize + payloadSize)

    /**
     * Keyframe detection via standard H.264 Annex-B NAL parsing (the same
     * framing the upstream FrameDecoder uses): an access unit starting with
     * SPS (7), PPS (8) or IDR (5) is a keyframe. No size heuristics.
     */
    fun isKeyFrame(payload: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 4) return false
        val start: Int
        val nalIndex: Int
        when {
            length >= 5 &&
                payload[offset] == 0.toByte() && payload[offset + 1] == 0.toByte() &&
                payload[offset + 2] == 0.toByte() && payload[offset + 3] == 1.toByte() -> {
                start = offset + 4
            }
            length >= 4 &&
                payload[offset] == 0.toByte() && payload[offset + 1] == 0.toByte() &&
                payload[offset + 2] == 1.toByte() -> {
                start = offset + 3
            }
            else -> return false
        }
        nalIndex = payload[start].toInt() and 0x1f
        return nalIndex == 7 || nalIndex == 5 || nalIndex == 8
    }
}

/**
 * Bridges upstream video messages of ONE connection attempt to a
 * [CarLifeVideoListener]. The [CarLifeSessionToken] is fixed at construction
 * (attempt identity from the Phase 9.1.1 fence) — a late message of a dead
 * attempt can never be re-labelled as the new session.
 *
 * Payloads are copied out of the pooled upstream message BEFORE dispatch
 * returns (see [CarLifeVideoFraming.copyWindow]); the upstream
 * `message.recycle()` afterwards can therefore never corrupt a delivered
 * frame.
 */
class CarLifeVideoBridge(
    private val session: CarLifeSessionToken,
    private val listener: CarLifeVideoListener,
    private val clock: () -> Long = { CarLifeVideoFraming.hostReceiveTimeUs() },
) : TransportListener {

    private var width = 0
    private var height = 0

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE -> {
                val info = message.protoPayload as? CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
                    ?: return true
                width = info.width
                height = info.height
                listener.onVideoConfig(
                    session,
                    CarLifeVideoConfig(
                        width = info.width,
                        height = info.height,
                        frameRate = info.frameRate,
                    ),
                )
                return true
            }
            ServiceTypes.MSG_VIDEO_DATA -> {
                if (message.payloadSize <= 0) return true
                // Copy the payload window out of the pooled message NOW.
                val payload = CarLifeVideoFraming.copyWindow(message.body, message.commandSize, message.payloadSize)
                listener.onVideoFrame(
                    session,
                    CarLifeVideoFrame(
                        payload = payload,
                        offset = 0,
                        length = payload.size,
                        presentationTimeUs = clock(),
                        keyFrame = CarLifeVideoFraming.isKeyFrame(payload, 0, payload.size),
                    ),
                )
                return true
            }
            ServiceTypes.MSG_CMD_VIDEO_ENCODER_PAUSE,
            ServiceTypes.MSG_CMD_VIDEO_ENCODER_RESET,
            -> {
                listener.onVideoStopped(session)
                return false
            }
        }
        return false
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        listener.onVideoStopped(session)
    }
}

/**
 * Session-scoped sink handle: the projection-side video sink (and optional
 * decoder diagnostics) that belong to ONE [CarLifeSessionToken]. Closing it
 * tears down only that session's media path — never another session's.
 */
class CarLifeVideoSinkSession(
    val video: com.shilapi.xcertplay.projection.ProjectionVideoSink,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

/**
 * Supplies the projection-side video sink for one CarLife session.
 * [onDecoderDiagnostic] receives shared-decoder diagnostics (e.g.
 * "first frame rendered") so the probe can report the real render stage.
 */
fun interface CarLifeVideoSinkProvider {
    fun acquire(onDecoderDiagnostic: (String) -> Unit): CarLifeVideoSinkSession
}

/**
 * Maps the CarLife video seam onto DiPlay projection types so the shared
 * `AndroidMediaSink` renders CarLife video exactly like CarPlay/CarLink.
 * Session-scoped: one instance per attempt, disposed with its session.
 */
class CarLifeProjectionVideoAdapter(
    private val sink: com.shilapi.xcertplay.projection.ProjectionVideoSink,
) : CarLifeVideoListener {

    private var width = 0
    private var height = 0

    override fun onVideoConfig(session: CarLifeSessionToken, config: CarLifeVideoConfig) {
        width = config.width
        height = config.height
        sink.onVideoConfig(
            com.shilapi.xcertplay.projection.ProjectionVideoConfig(
                codec = com.shilapi.xcertplay.projection.ProjectionVideoCodec.H264,
                // CarLife V2 carries SPS/PPS in-band (Annex-B) — no avcC
                // record exists and none is fabricated.
                codecData = CarLifeVideoConfig.EMPTY_CODEC_DATA,
                width = config.width,
                height = config.height,
            ),
        )
    }

    override fun onVideoFrame(session: CarLifeSessionToken, frame: CarLifeVideoFrame) {
        sink.onVideoFrame(
            com.shilapi.xcertplay.projection.ProjectionVideoFrame(
                codec = com.shilapi.xcertplay.projection.ProjectionVideoCodec.H264,
                width = width,
                height = height,
                presentationTimeUs = frame.presentationTimeUs,
                keyFrame = frame.keyFrame,
                payload = frame.payload,
                offset = frame.offset,
                length = frame.length,
            ),
        )
    }

    override fun onVideoStopped(session: CarLifeSessionToken) {
        sink.onVideoStreamActive(false)
    }
}
