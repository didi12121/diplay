# CarLife V2 Video Path — Internal Audit (Phase 9.2a)

Audit source: `CarLife-Android-Vehicle-V2.0/carlife-sdk` (Apache-2.0,
Baidu Apollo-DuerOS public). Findings verified against real source, not
READMEs. Real-device context: Xiaomi (head unit) ↔ vivo (CarLife phone),
USB AOA, protocolVersion 4, CONNECTION_ESTABLISHED confirmed.

## 1. Video channel

`MSG_CHANNEL_VIDEO = 2` (`Constants`). Encoder control commands travel on
`MSG_CHANNEL_CMD = 1`.

## 2. Video service types (`ServiceTypes`)

| Service type | Hex | Direction | Meaning |
|---|---|---|---|
| `MSG_CMD_VIDEO_ENCODER_INIT` | 0x00018007 | HU→MD | vehicle display spec (width/height/framerate) |
| `MSG_CMD_VIDEO_ENCODER_INIT_DONE` | 0x00010008 | MD→HU | **EncoderInfo** (real width/height/framerate) |
| `MSG_CMD_VIDEO_ENCODER_START` | 0x00018009 | HU→MD | phone starts streaming |
| `MSG_CMD_VIDEO_ENCODER_PAUSE` | 0x0001800A | HU→MD | phone pauses |
| `MSG_CMD_VIDEO_ENCODER_RESET` | 0x0001800B | HU→MD | reset |
| `MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE(_DONE)` | 0x0001800C/0x0001000D | | fps change |
| `MSG_VIDEO_DATA` | 0x00020001 | MD→HU | **video access unit** |
| `MSG_VIDEO_HEARTBEAT` | 0x00020002 | | video channel heartbeat |

## 3. EncoderInfo transport

`MSG_CMD_VIDEO_ENCODER_INIT_DONE` payload = protobuf
`CarlifeVideoEncoderInfo` (`CarlifeVideoEncoderInfoProto`): `width`,
`height`, `frameRate`. Sent by the phone AFTER receiving the vehicle's
`MSG_CMD_VIDEO_ENCODER_INIT` (which carries the HU `DisplaySpec`). This is
the only source of coded size — never guess from screens.

## 4. width / height

From `CarlifeVideoEncoderInfo.width/.height` on INIT_DONE. Real-device
example flow: HU sends its DisplaySpec on `onConnectionEstablished`, phone
answers with its encoder size. Resolution changes come as a new INIT_DONE.

## 5. Codec

**H.264/AVC only** in the open V2 source: `FrameDecoder.createDecoder()` uses
`MediaFormat.MIMETYPE_VIDEO_AVC` unconditionally. No HEVC branch exists
anywhere in the video path. `REAL_CARLIFE_VIDEO_CODEC = H264`.

## 6. H.265 support

**No.** Not present in the open source. Not faked by DiPlay.

## 7. SPS/PPS source

**In-band** Annex-B NAL units inside `MSG_VIDEO_DATA` access units (NAL
type 7 = SPS, 8 = PPS). `FrameDecoder` configures MediaCodec with
`MediaFormat.createVideoFormat(AVC, w, h)` and NO csd-0/csd-1 — proving the
decoder expects in-band parameter sets.

## 8. SPS/PPS: independent message or in-band?

**In-band** (see 7). There is no separate codec-config message in the video
channel. DiPlay therefore maps `ProjectionVideoConfig.codecData = empty` and
NEITHER fabricates SPS/PPS nor an avcC record.

## 9. Frame payload framing

`CarLifeMessage.body[commandSize, commandSize + payloadSize)` — the payload
window sits after the command header inside the pooled message buffer.
Multiple input buffers may consume one frame (upstream loops `remaining`).

## 10. Annex-B?

**Yes.** `FrameDecoder.isKeyFrame` parses `00 00 00 01` / `00 00 01` start
codes; MediaCodec receives raw Annex-B bytes. No AVCC/length-prefixed
framing, no RTP.

## 11. PTS

**Not present in the protocol.** Upstream feeds MediaCodec with
`System.nanoTime() / 1000` at decode time. DiPlay mirrors this with
`SystemClock.elapsedRealtimeNanos()/1000` and labels it
`PTS_SOURCE = HOST_RECEIVE_SYNTHETIC` — never claimed as phone PTS.

## 12. Keyframe flag

**Not a protocol field.** Determined by standard H.264 Annex-B NAL parsing
(NAL 5 = IDR, 7 = SPS, 8 = PPS → keyframe access unit). DiPlay uses the same
standard parsing (`CarLifeVideoFraming.isKeyFrame`) — no size heuristics.

## 13. How FrameDecoder feeds MediaCodec

Own thread per decoder: `feedFrame(message)` → `message.acquire()` (pool
ref-count) → `LinkedBlockingQueue` → decode loop `getInputBuffer` →
`put(body, commandSize + consumed, inputSize)` → `queueInputBuffer(...,
System.nanoTime()/1000, 0)` → `frame.recycle()` after consumption;
`releaseOutputBuffer(index, true)` renders to its Surface. **DiPlay bypasses
this class entirely** (RAW_BRIDGE_MODE) and feeds the shared `AndroidMediaSink`
instead — one MediaCodec, one Surface owner.

## 14. Message buffer lifecycle

`CarLifeMessage` is **pooled** (`CarLifeMessage.obtain(...)`,
`acquire()`/`recycle()` ref-counting). `MessageDispatcher.run()`:

```
read message → context.onReceiveMessage(...) → finally message.recycle()
```

The message body is therefore recycled RIGHT AFTER dispatch returns.

## 15. Where message.recycle() happens

- receive path: `MessageDispatcher.run()` `finally` block (after all
  transport listeners return);
- send path: `MessageDispatcher.sendMessage()` `finally`;
- decode path (upstream only): `FrameDecoder` after consumption via
  `acquire()/recycle()` pairing.

**DiPlay consequence (buffer ownership):** the CarLife video seam COPIES the
exact payload window out of the pooled body inside `onReceiveMessage`
(`CarLifeVideoFraming.copyWindow`) and hands the copy to
`ProjectionVideoFrame`. No delivered frame ever references a pooled buffer;
regression-tested by
`CarLifeVideoMappingTest.upstreamMessageRecyclingNeverCorruptsDeliveredFrames`.

## DiPlay mapping summary

```
MSG_CMD_VIDEO_ENCODER_INIT_DONE → CarLifeVideoConfig(w,h,fps, codecData=∅)
                               → ProjectionVideoConfig(H264, w, h, codecData=∅)
MSG_VIDEO_DATA                  → copy window + synthetic PTS + NAL keyframe
                               → ProjectionVideoFrame(H264, w, h, pts, key, window)
MSG_CMD_VIDEO_ENCODER_PAUSE/RESET, detach → onVideoStopped(session)
```

Decoder reconfigure on size change uses the shared
`VideoJob.Config.sameDecoderSetupAs` semantics (codec+CSD+size); a new
INIT_DONE with different size produces a new `ProjectionVideoConfig` and the
shared decoder reconfigures.

Upstream bypass: `CONFIG_EXTERNAL_VIDEO_SINK` (host-local flag, never
phone-visible) makes `RemoteDisplayRenderer` skip FrameDecoder creation and
forward `MSG_VIDEO_DATA` to host bridges (`RAW_BRIDGE_MODE`).
