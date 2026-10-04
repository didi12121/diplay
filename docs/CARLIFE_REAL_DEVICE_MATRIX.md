# CarLife Real Device Matrix

Last updated: Phase 9.2a

## Device matrix

| | Head unit (vehicle side) | Phone (CarLife side) |
|---|---|---|
| Brand | Xiaomi | vivo |
| Model | UNKNOWN / TODO(USER) | UNKNOWN / TODO(USER) |
| Android version | UNKNOWN / TODO(USER) | UNKNOWN / TODO(USER) |
| CarLife app | DiPlay CarLife probe (debug APK) | UNKNOWN / TODO(USER) |

## Transport & protocol

| | |
|---|---|
| Transport | USB AOA |
| VID:PID (phone as AOA accessory) | 18d1:2d01 |
| Protocol version | 4 |
| Channel | 20029999 (DEMO_CHANNEL — NOT FOR PRODUCTION) |
| Auth | **PASS** |
| Connection | **PASS** (CONNECTION_ESTABLISHED) |
| Phone UI evidence | "智能车载 / 已连接 我的汽车2" + 断开投屏 / 截屏 / 录屏 |

## Feature results

| Feature | Status |
|---|---|
| USB_DETECTED | PASS |
| AOA_ATTACHED | PASS |
| PROTOCOL_ACCEPTED | PASS |
| AUTHENTICATION | PASS |
| CONNECTION_ESTABLISHED | PASS |
| Video | PENDING real-device test (pipeline ready in Phase 9.2a) |
| Audio | NOT TESTED (Phase 9.2c) |
| Touch | NOT TESTED (Phase 9.2b) |

## Known diagnostics quirks

- `phone CarLife protocol version` shows `0 (not reported)` on this vivo unit
  while `connection=3 / auth=accepted`. The field is the CarLife protocol
  version from `ProtocolVersionMatch` (`CarLifeContext.carlifeVersion`), NOT a
  phone app version; 0 means the phone did not report one. It is a
  diagnostics field issue, not a connection blocker.

## Reproducing the probe

See `docs/CARLIFE_PROBE.md` (policy) and the debug panel
**CarLife (USB probe)** in the DiPlay debug APK.
