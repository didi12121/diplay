# Open CarLife Wired Provider — Phase 9.1 Compatibility Probe

`carlife-provider/` is DiPlay's third projection backend: **Android CarLife
(Baidu CarLife+) over USB AOA**, built on the public CarLife V2.0 vehicle SDK.

## Source & license

| | |
|---|---|
| Source baseline | `fgrcwp/apollo-DuerOS` → `CarLife-Android-Vehicle-V2.0/carlife-sdk` |
| Original implementation | Baidu Apollo-DuerOS public CarLife / CarLife+ source |
| License | **Apache License 2.0** (see `carlife-provider/LICENSE`) |
| Attribution | `carlife-provider/NOTICE` (required notices preserved) |

The upstream `com.baidu.carlife.*` sources are imported unmodified in
substance (build files modernized only). The upstream repo's legacy signing
material (`vehicle-app/*.keystore`) is **NOT** copied — those are unsafe
legacy credentials and DiPlay signs with its own keys.

Wireless CarLife+ is **NOT in scope**: upstream explicitly did not open source
the wireless integration (production access requires Baidu cooperation).

## Modernization applied (build only)

- AGP 9.3 / JDK 25 / compileSdk 37 / minSdk 28 (upstream: AGP-era 30 / Kotlin 1.5)
- `kotlin-android-extensions`, `kapt`(ARouter) removed (unused by this subset)
- protobuf: protobuf-gradle-plugin **0.10.0** + `protobuf-javalite 3.25.5`
  (upstream: protoc 3.2.0 + javalite 3.0.0); `.proto` files unchanged
- `IRotationWatcher.aidl` → hand-written equivalent stub (AGP 9 no longer
  compiles AIDL; wire format unchanged)
- `@JvmDefault` annotations dropped (deprecated-error on Kotlin 2.x) with
  `-Xjvm-default=all` to keep the same bytecode shape
- One `String?` strictness fix (`versionName`)
- `AndroidManifest` package attribute → Gradle `namespace`

## Architecture

```
ProjectionManager
├── CarPlayProjectionBackend        (existing, unchanged)
├── CarLifeProjectionBackend  ← NEW (carlife-provider module)
│        ↓ CarLifeProvider seam (token-tagged callbacks)
│   CarLifeV2Provider → com.baidu.carlife.sdk (Apache-2.0)
│        ↓ USB AOA (CONNECTION_TYPE_AOA only)
└── CarLinkProjectionBackend        (existing; ICCOA provider still unavailable)
```

`CarLifeProjectionBackend` implements the shared `ProjectionBackend` with
honest state mapping — Connected is reported **only** at the real SDK
`CONNECTION_ESTABLISHED` callback:

| SDK / probe event | ProjectionState |
|---|---|
| AOA / protocol negotiating | Connecting |
| `CONNECTION_ESTABLISHED` | Connected (exactly once) |
| `onConnectionVersionNotSupprt` | Error (PROTOCOL_VERSION) |
| `onConnectionAuthenFailed` | Error (AUTHENTICATION_FAILED) |
| disconnect in progress | Disconnecting |
| detached | Ready |

Session identity: every connect attempt mints a `CarLifeSessionToken`
(separate from `CarLinkSessionToken`); callbacks are accepted only for the
current token and stale ones are dropped with a diagnostic — no ordering
guesswork.

Resources: `USB + AUDIO`, arbitrated through the existing `ProjectionManager`
(never bypassed). CarPlay holding USB blocks CarLife connect and vice versa
(`ProjectionResourceConflictException` / `takeover()`).

## Compatibility Probe states & blockers

Probe states: `USB_DEVICE_FOUND → AOA_SWITCH_REQUESTED → AOA_ATTACHED →
PROTOCOL_NEGOTIATING → PROTOCOL_ACCEPTED → AUTHENTICATING → ESTABLISHED`
plus `VERSION_REJECTED / AUTH_FAILED / DETACHED / ERROR`.

Blockers are reported precisely — never a vague "connection failed":
`NONE / NO_REAL_DEVICE / AOA_COMPATIBILITY / PROTOCOL_VERSION / CHANNEL_OR_AUTH / OTHER`.

Only `ESTABLISHED` (= real `CONNECTION_ESTABLISHED`) counts as success. AOA
attach or USB detection alone are **not** success.

## DEMO_CHANNEL policy

The probe initializes the SDK with the **public upstream sample
configuration** (demo channel `20029999`, sample protocol version `4`, and a
local `diplay-carlife-probe` cuid as the test-receiver identifier):

```
DEMO_CHANNEL — NOT FOR PRODUCTION — COMPATIBILITY UNVERIFIED
```

DiPlay will never invent a legitimate channel, forge Baidu authentication,
patch `authResult`, skip MD auth or modify the phone APK. If modern CarLife+
phones reject the demo channel, the probe reports `CHANNEL_OR_AUTH` and stops.

## Debug probe panel

Debug builds only (`FLAG_DEBUGGABLE`; release builds hide it): the DiPlay UI
has **CarLife (USB probe)** showing USB device, AOA state, real connection
state, protocol version, phone CarLife version, auth result, last error and
held resources. No user content, media payloads or secrets are shown or
stored.

## Phase 9.2 (next, only after ESTABLISHED)

Media integration maps CarLife raw callbacks into the shared pipeline —
`ProjectionVideoConfig/Frame` and `AudioAccessUnit` into `AndroidMediaSink` —
so CarPlay / CarLife / CarLink share one renderer. The SDK's own
`FrameDecoder` / `AudioTrack` player are NOT adopted as the final
architecture.

## Current probe status

| Check | Status |
|---|---|
| REAL_CARLIFE_PHONE_TESTED | **NO** (no real device available in this environment) |
| USB_DETECTED | NOT RUN |
| AOA_ATTACHED | NOT RUN |
| PROTOCOL_ACCEPTED | NOT RUN |
| AUTHENTICATION | NOT RUN |
| CONNECTION_ESTABLISHED | NOT RUN |
| CARLIFE_WIRED_PROTOCOL_COMPATIBLE | **UNVERIFIED** |
| CARLIFE_BLOCKER | **NO_REAL_DEVICE** |
