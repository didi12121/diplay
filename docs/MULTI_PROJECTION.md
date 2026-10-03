# Multi-projection architecture (Phase 1)

DiPlay is being evolved from a dedicated Apple CarPlay receiver into a general
**phone-projection host**. Phase 1 introduces the projection abstraction, keeps
CarPlay fully working behind a thin adapter, and adds an ICCOA **CarLink**
framework skeleton with a Mock protocol backend.

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

## Packages

| Package | Contents |
|---|---|
| `com.shilapi.xcertplay.projection` | Backend-neutral model: `ProjectionBackend`, `ProjectionManager`, `ProjectionState`, `ProjectionCapabilities`, `ProjectionResourceCoordinator`, video/audio/input sinks, `ProjectionHost` |
| `com.shilapi.xcertplay.projection.carplay` | `CarPlayProjectionBackend` (wraps the existing `CarPlayController`), `CarPlayStateMapping`, `CarPlayInputAdapter` |
| `com.shilapi.xcertplay.projection.media` | `ProjectionMediaMapping`, `ProjectionMediaSinkAdapter` — neutral frames into `AndroidMediaSink` |
| `com.shilapi.xcertplay.carlink` | `CarLinkProtocolAdapter` (SDK seam), `CarLinkController`, channels, `MockCarLinkProtocolAdapter`, `UnavailableCarLinkProtocolAdapter`, `CarLinkDiagnostics` |

## CarPlay is unchanged

`CarPlayController` keeps owning iAP2, MFi, AirPlay/RTSP, Bonjour, USB/Wi-Fi
bring-up and the CarPlay session. `CarPlayProjectionBackend` only:

* maps `CarPlayStatus` → `ProjectionState` (`CarPlayStateMapping`),
* maps unified `ProjectionTouchEvent` → normalized `AirPlayContact` (`CarPlayInputAdapter`,
  same math as `CarPlayTouchMapper`),
* mirrors lifecycle without driving it (the host activity still creates/starts/
  closes the controller exactly as before).

## CarLink status — what is real today

**No ICCOA CarLink SDK or public protocol implementation is bundled.** The
repository contains no ports, no handshakes, no certificates and nothing reverse
engineered. What exists is:

* the `CarLinkProtocolAdapter` interface an official SDK adapter must implement,
* channel/session/controller plumbing above that interface,
* `MockCarLinkProtocolAdapter` for tests and the experimental UI flow
  (discover/connect/stream fake state/disconnect),
* `UnavailableCarLinkProtocolAdapter` which honestly reports
  *"CarLink protocol provider unavailable"*.

The UI labels the feature **"CarLink framework (experimental)"** and states that
real-phone compatibility is unverified. It never claims Xiaomi CarWith / vivo
Jovi InCar / OPPO Car+ compatibility.

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
* `onVideoConfig` / `onVideoFrame` (codec, key-frame flag, payload window)
* `onAudioFrame` (channel role, timestamp, payload)
* `onMetadata` (now-playing / navigation)
* `sendTouch(ProjectionTouchEvent)` / `sendKey(ProjectionKeyEvent)`
* `onError(code, message, cause)`

Everything above the adapter — `ProjectionManager`, `AndroidMediaSink`,
the UI, resource arbitration, diagnostics — works unchanged.

## Resource arbitration

`ProjectionResourceCoordinator` guarantees Bluetooth / Wi-Fi / USB / Audio /
Microphone are owned by at most one backend. `ProjectionManager.connect()`
acquires the target backend's resources *before* switching and throws
`ProjectionResourceConflictException` when a live session owns them.

## Logging rules (CarLink)

`CarLinkDiagnostics` drops password/passphrase/token/certificate/key/auth
fragments and long hex blobs before logging. Never log Wi-Fi credentials,
tokens, certificates, private keys or user content.

## License

No new third-party dependencies were added. Existing license files are
untouched (`AGPL-3.0-only`; see `LICENSE`, `docs/licenses/`).
