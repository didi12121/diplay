# Multi-projection architecture (Phase 1 + Phase 2 review fixes)

DiPlay is being evolved from a dedicated Apple CarPlay receiver into a general
**phone-projection host**. This document describes the architecture, exactly what
is real today, and what remains before real Xiaomi/vivo/OPPO phones can connect.

```
                    Projection Host (ProjectionHost / ProjectionManager)
                          │
          ┌───────────────┴───────────────┐
          │                               │
   CarPlayProjectionBackend        CarLinkProjectionBackend
          │                               │
    原 CarPlayController            CarLinkController
    (iAP2/MFi/AirPlay/…)                  │
          │                        CarLinkProtocolAdapter  ← SDK isolation seam
          │                               │
          └───────────────┬───────────────┘
                          │
                 Shared media layer
          (ProjectionVideoSink / ProjectionAudioSink / ProjectionInputSink)
                          │
                  ProjectionMediaSinkAdapter
                          │
                    AndroidMediaSink
               (MediaCodec / AudioTrack / mic uplink)
```

## Status — three distinct claims, do not mix them

| Claim | Status |
|---|---|
| **architecture ready** | Yes — backends, unified media/audio/input, resource arbitration, exception boundaries and UI selection are in place and unit tested. |
| **protocol provider unavailable** | Yes, and this is the production default: `ProjectionHost` registers `UnavailableCarLinkProtocolAdapter`, so the UI shows *"CarLink protocol provider unavailable"* with no devices and no fake CONNECTED state. |
| **real Xiaomi/vivo/OPPO compatibility** | **Unverified.** No phone has been tested against this code. The framework *targets* CarWith / Jovi InCar / Car+ over ICCOA CarLink; that is a roadmap, not a compatibility result. |

## Packages

| Package | Contents |
|---|---|
| `com.shilapi.xcertplay.projection` | Backend-neutral model: `ProjectionBackend`, `ProjectionManager` (+ takeover), `ProjectionState`, `ProjectionCapabilities`, `ProjectionResourceCoordinator`, video/audio/input sinks, `ProjectionHost` |
| `com.shilapi.xcertplay.projection.carplay` | `CarPlayProjectionBackend` (wraps the existing `CarPlayController`), `CarPlayStateMapping`, `CarPlayInputAdapter` |
| `com.shilapi.xcertplay.projection.media` | `ProjectionMediaMapping`, `ProjectionMediaSinkAdapter` — neutral frames into `AndroidMediaSink` |
| `com.shilapi.xcertplay.carlink` | `CarLinkProtocolAdapter` (SDK seam), `CarLinkController`, channels, media sink providers, `MockCarLinkProtocolAdapter`, `UnavailableCarLinkProtocolAdapter`, `CarLinkDiagnostics` |

## CarPlay is unchanged

`CarPlayController` keeps owning iAP2, MFi, AirPlay/RTSP, Bonjour, USB/Wi-Fi
bring-up and the CarPlay session. `CarPlayProjectionBackend` only:

* maps `CarPlayStatus` → `ProjectionState` (`CarPlayStateMapping`),
* maps unified `ProjectionTouchEvent` → normalized `AirPlayContact` (`CarPlayInputAdapter`,
  same math as `CarPlayTouchMapper`),
* mirrors lifecycle without driving it (the host activity still creates/starts/
  closes the controller exactly as before).

## Resource arbitration — selection ≠ stopping

`ProjectionManager` distinguishes four operations:

| Operation | Effect on a live session / resources |
|---|---|
| `select(mode)` | **Preference only.** Never stops a session, never releases resources. |
| `connect(device)` | Refused with `ProjectionResourceConflictException` while another backend's session is actually running. |
| `takeover(id)` | Controlled switch: request stop → **wait until actually stopped** → release → activate. Wrapped (host-owned) sessions answer "still stopping" and their host confirms via `onBackendSessionStopped`. |
| `disconnectBackend(id)` | Releases resources only after the underlying session is confirmed stopped. |

Shared hardware (Bluetooth / Wi-Fi / USB / Audio / Microphone) is owned by at
most one backend; claims survive until the *real* stop is confirmed — never
merely because a disconnect was requested or a backend was selected away.

CarLink claims are computed per session transport (`CarLinkCapabilities.resourcesFor`):
USB CarLink → USB + AUDIO; wireless → WIFI + AUDIO. Bluetooth/Microphone stay
reserved (off) until the official protocol requirements are known.

## CarLink status — what is real today

**No ICCOA CarLink SDK or public protocol implementation is bundled.** The
repository contains no ports, no handshakes, no certificates and nothing reverse
engineered. What exists is:

* the `CarLinkProtocolAdapter` interface an official SDK adapter must implement
  (typed audio started/frame/stopped lifecycle, typed video config/frame with
  codec/size/PTS/keyframe/buffer windows);
* channel/session/controller plumbing above that interface, with **session-scoped
  media sinks**: a session acquires `CarLinkMediaSinks` (→ `AndroidMediaSink`)
  when it starts and closes them when it ends;
* an **exception boundary**: adapter throws become `ProjectionState.Error`
  (backendId + code + message + cause) and clean up session/resource state;
* `MockCarLinkProtocolAdapter` — **tests and explicit developer harnesses only**
  (`ProjectionHost.registerCarLinkMock`); never a production default;
* `UnavailableCarLinkProtocolAdapter` — the production default, which honestly
  reports *"CarLink protocol provider unavailable"*.

## Plugging in an official SDK later

Implement `CarLinkProtocolAdapter` in a dedicated module (e.g. `carlink-sdk-adapter`)
so the closed-source AAR never leaks into `shared`:

```text
ProjectionBackend
      ↑
CarLinkProjectionBackend
      ↑
CarLinkController
      ↑
CarLinkProtocolAdapter        (interface in shared/carlink)
      ↑
OfficialCarLinkSdkAdapter     (new module: depends on the ICCOA SDK)
      ↑
ICCOA CarLink SDK
```

Required adapter surface (`CarLinkProtocolAdapter` + `CarLinkProtocolListener`):

* `initialize()` / `dispose()`
* `startDiscovery()` / `stopDiscovery()` + `onDeviceFound`
* `connect(device)` / `disconnect()` + `onSessionStarted` / `onSessionEnded`
* `onVideoConfig(ProjectionVideoConfig)` / `onVideoFrame(ProjectionVideoFrame)`
* `onAudioStarted(CarLinkAudioFormat)` / `onAudioFrame(CarLinkAudioFrame)` / `onAudioStopped(streamId)`
* `onMetadata` (now-playing / navigation)
* `sendTouch(ProjectionTouchEvent)` / `sendKey(ProjectionKeyEvent)`
* `onError(code, message, cause)`

Everything above the adapter — `ProjectionManager`, `AndroidMediaSink`, the UI,
resource arbitration, diagnostics — works unchanged once the adapter reports
real callbacks. What the adapter CANNOT fix by itself: real-device compatibility
testing (phones must actually be verified) and any protocol behaviors that turn
out to differ from this interface (then the interface evolves with the SDK).

## Logging rules (CarLink)

`CarLinkDiagnostics` drops password/passphrase/token/certificate/key/auth
fragments and long hex blobs before logging. Never log Wi-Fi credentials,
tokens, certificates, private keys or user content.

## License

No new third-party dependencies were added. Existing license files are
untouched (`AGPL-3.0-only`; see `LICENSE`, `docs/licenses/`).
