# CarLife Wireless AP / Same-LAN Transport — Source Audit (Phase 9.2W-A)

Source of truth: the imported open CarLife V2.0 vehicle SDK
(`CarLife-Android-Vehicle-V2.0/carlife-sdk`, Apache-2.0, Baidu Apollo-DuerOS
public source) in `carlife-provider/`. Everything below was audited from that
source; where DiPlay changed anything, it is marked **[DiPlay]** and is
host-local (diagnostics/lifecycle only — no wire-protocol change).

The USB/AOA path (real-device proven) is untouched by this document.

---

## 1. UDP discovery behavior

`com.baidu.carlife.sdk.receiver.transport.wirless.WirlessAPProtocolTransport`
is a **client of an existing IP network** — it does NOT create a Wi-Fi hotspot.
It assumes the phone and the head unit already have IP connectivity (the
vivo joins the Xiaomi hotspot, or both join the same Wi-Fi LAN).

`connect()` binds a `DatagramSocket(7999)` and starts `WifiConnectThread`,
which loops on `DatagramSocket.receive()`:

- the datagram's **source address is the phone IP** (`mPacket.address`);
- when the context is not yet connected, the thread calls
  `WirlessConnector.startConnect(phoneIp)` and, on success, reports
  `onConnectionAttached()` into `GroupedProtocolTransport`;
- while connected, the thread stops itself (`isConnecting` gate).

**[DiPlay]** diagnostics-only callbacks (`WirlessTransportProbe`): UDP
listening, packet count + phone IP, TCP connecting/attached.

**[DiPlay]** lifecycle fix: the thread no longer sleeps after a stop request,
so `terminate()` ends the thread deterministically (no zombie discovery
thread surviving into the next session).

## 2. UDP port

`BOARDCAST_WIFI_PORT = 7999` (upstream constant, exported as
`WirlessAPProtocolTransport.UDP_DISCOVERY_PORT`). Never changed.

## 3. phone IP source

The source address of the UDP discovery datagram (see §1). Nothing else —
no mDNS, no scan, no phone app modification.

## 4. TCP channel ports

`com.baidu.carlife.sdk.receiver.transport.wirless.WirlessConnector` opens one
TCP socket per channel to the phone IP (upstream constants, verbatim):

| channel (MSG_CHANNEL_*) | port |
|---|---|
| CMD (1) | 7240 |
| VIDEO (2) | 8240 |
| AUDIO (3) | 9240 |
| AUDIO_TTS (4) | 9241 |
| AUDIO_VR (5) | 9242 |
| TOUCH (6) | 9340 |
| UPDATE (7) | 9440 |

## 5. Socket lifecycle (audited behavior)

`WirlessConnector.startConnect(host)` builds one `SocketCommunicator(channel,
host, port)` per channel. **The secondary `SocketCommunicator` constructor
opens `Socket(host, port)` immediately** (blocking TCP connect) and starts a
`SocketReader` thread per channel — i.e. `startConnect` performs an **eager
connect of all seven channels**, NOT lazy connect-on-read/write and NOT
"objects only". `startConnect` therefore reports failure as soon as one
channel cannot connect.

**[DiPlay]** robustness (host-local):

- a repeated discovery first closes any previous channel set — a
  re-discovery can never leak sockets/reader threads or mix stale messages;
- a partially failed connect closes everything it opened and returns `false`
  (no half-open channel set left behind);
- per-channel states ("connecting"/"connected"/"failed") are reported to the
  diagnostics probe.

`terminate()` (via `GroupedProtocolTransport.stopConnect/shutdown`) closes
every channel socket (`shutdownInput/shutdownOutput/close`), interrupts the
readers and clears the queue — session teardown closes **all** sockets
(7240/8240/9240/9241/9242/9340/9440); the next session creates fresh
communicators. No communicator of session A can deliver messages to session B
(the DiPlay provider additionally fences every callback by
`CarLifeSessionToken`).

UDP socket: `WirlessAPProtocolTransport.terminate()` closes the
`DatagramSocket`, which unblocks `receive()`; the discovery thread exits
deterministically. Reconnecting binds 7999 exactly once (double `connect()`
is a no-op while the socket exists).

## 6. Transport → protocol attach path

`GroupedProtocolTransport.configConnectType()` reads
`FEATURE_CONFIG_CONNECT_TYPE` (the ONLY read point) and builds exactly one
`ProtocolTransport`:

- `CONNECTION_TYPE_AOA (0x0002)` → `AOAProtocolTransport` (USB scanner starts
  in `connect()` → `scanner.scan()`)
- `CONNECTION_TYPE_HOTSPOT (0x0005)` → `WirlessAPProtocolTransport` (wireless
  AP / same LAN — this phase)
- `CONNECTION_TYPE_WIFIDIRECT (0x0009)` → `WirlessP2PProtocolTransport`
  (**not activated**)

When the TCP channel set attaches, `onConnectionAttached` builds the
`MessageDispatcher` and from there the SAME protocol layer runs as USB.

## 7. Shared protocol / auth reuse

After transport attach, `ConnectionEstablishHandler`, protocol version
negotiation, channel/authentication, CUID, statistics and the whole CarLife
message set run unchanged — there is NO wireless-specific auth logic and no
second message pipeline. The video bridge (`CarLifeVideoBridge`), touch uplink
(`CarLifeReceiver.onTouchEvent`) and the shared `AndroidMediaSink` are
transport-agnostic and reused verbatim.

**[DiPlay]** `CarLifeReceiver.configureConnectTypeWithoutStarting(type)` is
the safe reconfiguration seam: it fully stops the previous transport,
suppresses detach auto-reconnect, rebuilds ONLY the local transport
implementation and does NOT connect. Upstream `setConnectType()` (which
auto-connects) is never used. One process → one `CarLife.init` → one captured
receiver is preserved.

## 8. Permissions

Used by this path: `INTERNET` (TCP/UDP sockets) and `ACCESS_NETWORK_STATE`
(local network-type diagnostics). Both were already declared by the
`carlife-provider`/`shared` manifests — **no new permissions added**.

NOT used here (future Wi-Fi Direct/Bluetooth bootstrap only): `BLUETOOTH_*`,
`NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`. Not added.

## 9. Session fencing

- provider boundary: one armed attempt per `CarLifeSessionToken`; listeners
  and the wireless probe are bound per attempt instance; late callbacks of a
  dead attempt carry its token and are rejected by identity
  (`stale-touch-ignored` / `stale-*-ignored` diagnostics);
- **[DiPlay]** transport ownership: a WIFI_AP attempt's terminal state stops
  the provider attempt, so its UDP socket/thread and TCP channel set cannot
  survive into the next session (the proven USB AOA lifecycle is unchanged);
- resources are transport-scoped: USB_AOA → {USB, AUDIO}, WIFI_AP →
  {WIFI, AUDIO}; only one CarLife attempt may be active at a time.

## 10. Known limitations

- **Phone discovery compatibility is the main unknown**: modern CarLife+
  phone apps may not send the legacy UDP discovery datagram at all. If the
  listener runs and nothing arrives, the probe reports the precise blocker
  `NO_DISCOVERY_PACKET`. DiPlay must NOT invent discovery packets or patch the
  phone app.
- The eager all-channels TCP connect is upstream behavior: if the phone
  listens on only some ports, the channel set fails as a whole
  (`PHONE_DISCOVERED_TCP_FAILED`) — reported truthfully, not redesigned here.
- Default OS routing is used (no explicit `Network` binding). If real devices
  route TCP attempts into the wrong network, explicit Network binding is a
  later, separately-tested change.
- No video requirement in this phase: wireless success is
  `CONNECTION_ESTABLISHED`; video/touch follow through the shared pipeline and
  are verified separately.

## Wi-Fi Direct path — NOT IMPLEMENTED YET

`WirlessP2PProtocolTransport`, `InstantConnectionSetup`,
`BluetoothDeviceDiscover`, `WifiDirectManager` exist upstream but are
**NOT ACTIVATED** (`CarLifeTransport.WIFI_DIRECT` is `EXPERIMENTAL_DISABLED`).
Modern Android work still needed: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`/
`NEARBY_WIFI_DEVICES` runtime permissions, Wi-Fi Direct group lifecycle and
the RFCOMM bootstrap, plus their UX. That is a separate future phase
(9.2W-B+), not this one.
