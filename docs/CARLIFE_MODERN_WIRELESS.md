# CarLife Modern Wireless — Bluetooth Bootstrap + Phone Hotspot (Phase 9.2W-B1)

This document separates the THREE wireless stories and, in each, what is
**source-confirmed** (read from the imported public CarLife V2.0 SDK),
**real-device-confirmed** (observed on the Xiaomi + vivo pair), and **still
unverified**.

---

## 1. LEGACY AP MODE — `WIFI_AP` (kept, compatibility path)

**Source-confirmed:**
- `WirlessAPProtocolTransport` binds **UDP 7999** and waits for a discovery
  datagram; the datagram SOURCE address is the phone IP.
- `WirlessConnector` then eagerly TCP-connects all channels
  (CMD 7240 / VIDEO 8240 / AUDIO 9240 / TTS 9241 / VR 9242 / TOUCH 9340 /
  UPDATE 9440).

**Real-device-confirmed:**
- Xiaomi: UDP 7999 bind = PASS, `listening = true`.
- vivo modern CarLife+: `packets = 0` — the modern phone app does **not** send
  the legacy discovery datagram.

**Conclusion:** legacy UDP discovery is IMPLEMENTED AND WORKING but the modern
vivo app does not use it. It stays as a compatibility path and is never
silently replaced.

## 2. MODERN HOTSPOT MODE — `BT_HOTSPOT` (Phase 9.2W-B1)

Real-device evidence (vivo CarLife+ "无线连接" page) prescribes a
**Bluetooth-assisted** bootstrap: phone WLAN off → phone hotspot on → head
unit joins the phone hotspot → head-unit Bluetooth name confirmed → phone
connects to the head unit's Bluetooth.

**Source-confirmed (public V2 SDK):**
- RFCOMM SPP UUID `00001101-0000-1000-8000-00805F9B34FB`
  (`Constants.BLUETOOTH_COMMUNICATE_UUID`) — Classic Bluetooth, never BLE GATT.
- Bootstrap messages over `MSG_CHANNEL_CMD` with standard CarLifeMessage
  framing (`commandSize`/`payloadSize`/`protoPayload`):
  - `MSG_WIRELESS_INFO_REQUEST` → `MSG_WIRELESS_INFO_RESPONSE`
    (`CarlifeWirlessInfo.wirlessType/wifiFrequency`)
  - `MSG_WIRELESS_TARGET_INFO_REQUEST` → `MSG_WIRELESS_TARGET_INFO_RESPONSE`
    (`CarlifeWirlessTarget.wifiDeviceName`) — upstream answers with
    `CONFIG_WIFI_DIRECT_NAME` and then starts `WifiDirectManager`
  - `MSG_WIRELESS_REQUEST_IP` / `MSG_WIRELESS_RESPONSE_IP`
    (`CarlifeWirlessIp.wirlessip`)
  - `MSG_WIRELESS_MD_STATUS`
- `InstantConnectionSetup.TYPE_*`: `0` unsupported, `TYPE_WIFI=1` hotspot,
  `TYPE_WIFI_DIRECT=2` direct, `TYPE_ALL=3` both; `FREQUENCY_2_4G=0`,
  `FREQUENCY_5G=1`.

**Implemented in B1 (probe + minimum bootstrap):**
- Deterministic target selection: user-picked BONDED device **name**, exact
  match only (`CONFIG_TARGET_BLUETOOTH_NAME`); never the `isConnected()`
  reflection fallback (now safe-by-default `false`), never a random paired
  device. Names only — no MAC/address is displayed or stored.
- Session-scoped `CarLifeWirelessBootstrap` (attempt-bound callbacks; a late
  RFCOMM callback of a dead session can never mutate a newer one).
- `MSG_WIRELESS_INFO_REQUEST` → exactly ONE `MSG_WIRELESS_INFO_RESPONSE`
  advertising **`TYPE_WIFI` (HOTSPOT) + `FREQUENCY_2_4G`** — never `TYPE_ALL`
  (Wi-Fi Direct is NOT implemented, so it is NOT advertised).
- `MSG_WIRELESS_TARGET_INFO_REQUEST` is **observed and recorded only**
  (`MODERN_WIRELESS_PATH = WIFI_DIRECT_REQUIRED`): no fake target data is
  returned and `WifiDirectManager` is NEVER started.
- `MSG_WIRELESS_RESPONSE_IP` → `CarlifeWirlessIp.wirlessip` parsed and
  syntactically validated (no DNS) → `onWirelessIp(ip)` → the **existing**
  `WirlessConnector` TCP channel set → existing protocol/version/auth.
  No gateway guessing (192.168.43.1 etc. is NEVER assumed).
- `MSG_WIRELESS_REQUEST_IP` is NEVER sent from B1 (upstream sends it only
  after Wi-Fi Direct is ready; B1 does not invent ordering — real sequence
  first).
- TCP channels use an **explicit connect timeout of 4000 ms per channel**
  (`SocketCommunicator.CONNECT_TIMEOUT_MS`, recommended 3000–5000 ms window;
  framing untouched); a failed channel closes the whole set and is classified.
- BT hotspot mode does NOT bind UDP 7999 (TCP-direct to the protocol IP).
- Permissions: Android 12+ `BLUETOOTH_CONNECT` runtime grant (requested
  explicitly in the debug UI); legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`
  (`maxSdkVersion=30`); **no `BLUETOOTH_SCAN`, no location** — bonded lookup +
  RFCOMM only. API 33+ dynamic receiver uses `RECEIVER_NOT_EXPORTED`.
- Session stop closes RFCOMM (including an in-flight connect), stops discovery
  callbacks, unregisters the receiver idempotently (start/stop/start safe),
  closes TCP and fences every late callback.

**Still unverified (the point of the real test):**
- What the modern vivo actually sends after RFCOMM connects (scenario A: INFO
  negotiation then `MSG_WIRELESS_RESPONSE_IP` — ideal; B: TARGET_INFO_REQUEST
  → Wi-Fi Direct path; C: RFCOMM silent; D: RFCOMM connect fails).
- Whether modern CarLife+ accepts `TYPE_WIFI` + 2.4 GHz as advertised.
- Whether the phone provides the IP in phone-hotspot mode.

## 3. WIFI DIRECT — future Phase 9.2W-B2 (NOT IMPLEMENTED)

`WifiDirectManager` / `InstantConnectionSetup`'s P2P flow exist upstream but
are **not activated**: no P2P group creation, no peer discovery, no
`discoverable()`. If the real phone messages clearly require that path, the
probe records `MODERN_WIRELESS_PATH = WIFI_DIRECT_REQUIRED` and stops there.

---

## Diagnostics (no MAC/SSID/payloads)

`transport: BT_HOTSPOT`, Bluetooth `permission / target(name) / bonded /
rfcomm`, bootstrap `infoRequest / infoResponse / advertisedType=HOTSPOT /
targetInfoRequest / requestIpSent(always false in B1) / responseIpReceived /
path`, `phoneIp` (protocol-provided only), TCP channel states, protocol/auth.
States: `BT_WAITING_PERMISSION → BT_TARGET_REQUIRED → BT_TARGET_FOUND →
BT_RFCOMM_CONNECTING → BT_RFCOMM_CONNECTED → BT_WAITING_WIRELESS_INFO →
BT_WIRELESS_INFO_NEGOTIATED → BT_TARGET_INFO_REQUESTED → BT_WAITING_PHONE_IP
→ BT_PHONE_IP_RECEIVED → BT_TCP_CONNECTING → BT_TRANSPORT_ATTACHED →
PROTOCOL_* → ESTABLISHED` — **ESTABLISHED is the only success**.

Failure classification (section 35): `BT_PERMISSION / BT_TARGET_NOT_SELECTED /
BT_TARGET_NOT_BONDED / BT_RFCOMM_CONNECT_FAILED / BT_BOOTSTRAP_SILENT /
WIRELESS_INFO_NEGOTIATION_FAILED / WIFI_DIRECT_REQUIRED / PHONE_IP_NOT_PROVIDED
/ TCP_CONNECT_FAILED / PROTOCOL_VERSION / CHANNEL_OR_AUTH / NONE`.
