# Multi-Projection Architecture — Phase 8.7

DiPlay is a **multi-protocol phone-projection host**. The existing Apple CarPlay
stack (`CarPlayController`: iAP2/MFi/AirPlay/RTSP/Bonjour/USB/Wi-Fi) is
unchanged and remains the reference implementation; ICCOA CarLink (Xiaomi
CarWith / vivo Jovi InCar / OPPO Car+) and future protocols (CarLife+, 亿连,
Android Auto, HiCar) plug in behind one neutral backend interface.

## Status

| Claim | Status |
|---|---|
| Architecture ready | **YES** |
| Shared Surface/video pipeline | **YES** (`ProjectionSessionActivity → ProjectionSurfaceHost → AndroidCarLinkMediaSinkProvider → ProjectionMediaSinkAdapter → AccessUnitMediaSink → AndroidMediaSink → MediaCodec`) |
| Shared raw audio pipeline | **YES** (protocol-neutral `AudioAccessUnit` → `AudioRenderer`/`AudioTrack`; CarPlay RTP adapted once in `AirPlayAudioAdapter`, CarLink raw never framed) |
| Resource arbitration | **YES** (resolved-device claims, takeover completion loop, leases released only on confirmed stop) |
| Official CarLink provider | **NOT IMPLEMENTED** — no ICCOA SDK ships in this repository |
| Real Xiaomi CarWith compatibility | **UNVERIFIED** (no device test has been run) |
| Real vivo Jovi InCar compatibility | **UNVERIFIED** (no device test has been run) |
| Real OPPO Car+ compatibility | **UNVERIFIED** (no device test has been run) |

Three claims are deliberately kept apart:

1. **Architecture ready** — the integration surface exists and is regression
   tested end to end.
2. **Protocol provider unavailable** — production builds register
   `UnavailableCarLinkProtocolAdapter`; the UI honestly shows "CarLink protocol
   provider unavailable" and offers no fake devices.
3. **Real compatibility unverified** — nothing here may be read as
   "works with real phones" until actual-device testing happens.

This project does **not** implement the ICCOA CarLink protocol, does not
reverse engineer CarWith/Jovi InCar, and ships no certificates, keys or
authentication credentials. No fake sockets, ports, handshakes or packet
formats are invented.

## Real media chain (Phase 8)

```
ProjectionSessionActivity (full-screen SurfaceView, bound to ONE backendId)
        │ push Surface events (owner-tagged: stale destroys ignored)
        ▼
ProjectionSurfaceHost (process-level display host)
        │ Listener callbacks (replay current surface on attach)
        ▼
AndroidCarLinkMediaSinkProvider (session-scoped, applicationContext)
        ▼
ProjectionMediaSinkAdapter (zero-copy payload windows)
        ▼
AccessUnitMediaSink (protocol-neutral renderer surface)
        ▼
AndroidMediaSink
   ├── VideoDecoder → MediaCodec → Surface   (real w/h + PTS from ProjectionVideoConfig/Frame)
   └── AudioRenderer → MediaCodec/AudioTrack  (AudioAccessUnit; PCM endianness declared)
             ▲                    ▲
   AirPlayAudioAdapter (CarPlay)  raw frames (CarLink)
```

Surface lifecycle: `surfaceCreated/Changed/Destroyed` are **owner-tagged** —
a late destroy callback from a replaced Activity can never clear a newer
surface. On Activity recreate the new surface re-attaches to the live session;
the protocol session is not torn down.

Audio boundary: CarPlay RTP is stripped **exactly once** in
`AirPlayAudioAdapter` (12-byte header + timestamp conversion); CarLink raw
AAC/Opus/PCM access units flow through unchanged — no fake RTP headers are ever
synthesized. `ProjectionSampleFormat` (`PCM_S16_LE`/`PCM_S16_BE`) declares PCM
byte order per stream; the renderer byte-swaps exactly once for BE, never for
LE. Protocol PTS reaches `MediaCodec.queueInputBuffer`; a clock is only the
fallback for streams without timestamps (CarPlay).

## Resource arbitration

| Rule | Behavior |
|---|---|
| `select()` | preference only — never stops or releases anything |
| `connect()` | resolves the actual target device FIRST (`resolveConnectDevice`), claims resources for THAT device, connects THAT device — no drift |
| explicit device vanished before connect | `CONNECT_FAILED` — never falls back to another phone; zero claims |
| USB CarLink | claims `USB + AUDIO` |
| Wireless CarLink | claims `WIFI + AUDIO` |
| nothing discovered + `connect(null)` | zero resource claims (UNKNOWN transport claims nothing); clear connect failure |
| live foreign session | `RESOURCE_CONFLICT` — `takeover()` is the only controlled switch |
| `takeover()` | Activated → start now; AwaitingStop → wait for `onTakeoverActivated` (no polling); Blocked → never start |
| second takeover while one is pending | `Blocked(TAKEOVER_IN_PROGRESS)` — the pending takeover and its listener are never replaced |
| `disconnect()` | releases leases only after the session is **actually** stopped; failing adapters release too (their session state is torn down) |
| FATAL SDK error | tears the session down, closes media sinks, releases leases, latches `Error` |
| RECOVERABLE SDK error | diagnostic only — session and leases stay alive |

## Session state semantics

- `initialize()/start()/connect()` advance the state machine only on real
  success — an adapter failure surfaces as `ProjectionState.Error` and is never
  overwritten by a healthy state.
- A **FATAL** error latches `Error`: a trailing `onSessionEnded` of the same
  failing session only completes cleanup/release and must not reset the state
  to Ready. The next `initialize`/`start`/`connect` clears the latch.
- **Stale session ends are isolated** (trailing-end guard / session epoch):
  sessions torn down non-normally may still deliver their `onSessionEnded`
  afterwards. Such a callback is consumed with a `stale-session-end-ignored`
  diagnostic and can never overwrite a newer attempt's state (Ready over
  Connecting), tear down a newer live session, release its lease or close its
  media sinks. An explicit `disconnect()` of the current session always works.

## What `OfficialCarLinkSdkAdapter` must implement

One class implementing `CarLinkProtocolAdapter` in a dedicated module. It is
responsible **only** for translating SDK callbacks into typed events:

```
initialize / dispose                      — SDK init/teardown
startDiscovery / stopDiscovery            — + onDeviceFound(CarLinkDevice)
connect(device) / disconnect()            — + onSessionStarted / onSessionEnded
onVideoConfig(ProjectionVideoConfig)      — codec, codecData, width, height
onVideoFrame(ProjectionVideoFrame)        — PTS, keyframe, payload window
onAudioStarted(CarLinkAudioFormat)        — streamId, role, codec, sampleRate,
                                            channels, sampleFormat (PCM endianness)
onAudioFrame(CarLinkAudioFrame)           — PTS + payload window
onAudioStopped(streamId)
sendTouch / sendKey                       — unified input uplink
onMetadata(ProjectionMetadata)            — now playing / navigation
onError(code, message, severity, cause)   — RECOVERABLE vs FATAL mapping
```

It is **not** responsible for: MediaCodec, AudioTrack, Activity, SurfaceView,
resource arbitration, or backend switching — the shared stack does all of that.

Then one registration line:

```kotlin
ProjectionHost.registerCarLink(OfficialCarLinkSdkAdapter(...), context = applicationContext)
```

## Developer verification harness (debug builds only)

`FLAG_DEBUGGABLE` builds expose "Developer: mock USB session" / "mock Wi-Fi
session" in the CarLink panel. They register the explicit mock provider and
stream a real H.264 test pattern (MediaCodec encoder) + PCM tone through the
whole chain (Surface → MediaCodec, access unit → AudioTrack), and show the
held resources (`USB, AUDIO` vs `WIFI, AUDIO`). Release builds never show or
register the mock — production always defaults to the Unavailable provider.

## Remaining blockers for real-device testing

1. **No official ICCOA CarLink SDK** — by design; only a legal provider can
   unblock real connections.
2. **Real phone compatibility unverified** — Xiaomi CarWith / vivo Jovi InCar /
   OPPO Car+ can only be called "targets", never "compatible", until devices
   are actually tested in Phase 9.
