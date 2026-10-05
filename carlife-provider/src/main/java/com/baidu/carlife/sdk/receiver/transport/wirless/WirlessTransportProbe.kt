// DiPlay host-local extension (Phase 9.2W-A, diagnostics only).
// Lives beside the upstream wirless transport sources; no wire-protocol
// behavior is affected. Upstream provenance: Apache-2.0 Baidu CarLife V2.0.
package com.baidu.carlife.sdk.receiver.transport.wirless

/**
 * Diagnostic-only callbacks from the wireless AP transport (UDP discovery +
 * TCP channel sockets). Host-local seam so DiPlay can report REAL wireless
 * progress (section 18/19 diagnostics) without reading transport internals.
 *
 * Every callback is best-effort and side-effect free on the protocol path;
 * a listener must never throw into the transport threads.
 */
interface WirlessTransportProbe {
    /** UDP discovery socket bound (the port is fixed at 7999 upstream). */
    fun onUdpListening(port: Int) {}

    /** UDP discovery thread exited and its socket is gone. */
    fun onUdpStopped() {}

    /** One discovery datagram was received from [fromIp] (the phone's IP). */
    fun onUdpPacketReceived(fromIp: String, total: Int) {}

    /** About to open the TCP channel sockets to [host] (the phone). */
    fun onTcpConnecting(host: String) {}

    /**
     * One TCP channel socket changed state. [channel] is the upstream
     * MSG_CHANNEL_* id; [state] is "connecting" / "connected" / "failed".
     */
    fun onTcpChannelState(channel: Int, port: Int, state: String, error: String?) {}

    /** TCP channel set complete: transport attached (protocol may now run). */
    fun onTransportAttached(host: String) {}
}
