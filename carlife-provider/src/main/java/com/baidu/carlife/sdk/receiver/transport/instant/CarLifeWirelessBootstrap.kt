// DiPlay host-local extension (Phase 9.2W-B1, host-local only).
// Source-grounded on the upstream instant-connection implementation
// (InstantConnectionSetup / BluetoothDeviceDiscover / BluetoothCommunicator,
// Apache-2.0 Baidu CarLife V2.0); no wire-protocol change.
//
// This is the MODERN CarLife Bluetooth bootstrap PROBE: RFCOMM
// (00001101-0000-1000-8000-00805F9B34FB) + the public wireless bootstrap
// messages, scoped to ONE session and deterministic target selection. It does
// NOT create a Wi-Fi Direct group (Phase 9.2W-B2), never sends
// MSG_WIRELESS_REQUEST_IP speculatively, and never invents protocol data.
package com.baidu.carlife.sdk.receiver.transport.instant

import android.bluetooth.BluetoothManager
import android.content.Context
import com.baidu.carlife.protobuf.CarlifeWirlessIpProto
import com.baidu.carlife.protobuf.CarlifeWirlessInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.communicator.BluetoothCommunicator
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/** Syntactic IP validation (no DNS, no guessing). */
internal object WirelessIpValidator {
    fun isValid(ip: String): Boolean {
        if (ip.isEmpty() || ip.length > 45) return false
        val v4 = ip.split(".")
        if (v4.size == 4) {
            return v4.all { part ->
                part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.all { it.isDigit() } &&
                    part.toInt() in 0..255
            }
        }
        // Loose but strict-shaped IPv6 check: hex groups and colons only.
        return ip.contains(":") &&
            ip.length >= 3 &&
            ip.all { it.isDigit() || it in "abcdefABCDEF:" } &&
            ip.split(":").all { it.length <= 4 }
    }
}

/**
 * Message-level bootstrap session over one RFCOMM [Communicator].
 *
 * Source-grounded handling ONLY (Phase B1):
 *  - MSG_WIRELESS_INFO_REQUEST   -> exactly ONE MSG_WIRELESS_INFO_RESPONSE
 *    advertising ONLY what B1 implements: TYPE_WIFI (phone hotspot mode) and
 *    FREQUENCY_2_4G - never TYPE_ALL (Wi-Fi Direct is not implemented).
 *  - MSG_WIRELESS_TARGET_INFO_REQUEST -> observed and reported only. This
 *    request wants the head unit's Wi-Fi Direct target name (upstream answers
 *    it with CONFIG_WIFI_DIRECT_NAME and then starts WifiDirectManager): B1
 *    neither fakes target data nor starts any P2P machinery.
 *  - MSG_WIRELESS_RESPONSE_IP    -> parses CarlifeWirlessIp.wirlessip and
 *    reports it ONLY when syntactically valid.
 *  - MSG_WIRELESS_MD_STATUS      -> observed and reported.
 *  - MSG_WIRELESS_REQUEST_IP is NEVER sent from here (upstream only sends it
 *    after Wi-Fi Direct is ready; B1 does not invent ordering).
 */
class CarLifeWirelessBootstrapSession(
    private val communicator: Communicator,
    private val listener: CarLifeWirelessBootstrap.Listener,
    private val advertisedType: Int = InstantConnectionSetup.TYPE_WIFI,
    private val advertisedFrequency: Int = InstantConnectionSetup.FREQUENCY_2_4G,
) {
    private val terminated = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "CarLifeBtBootstrap").also { it.isDaemon = true; it.start() }
    }

    private fun runLoop() {
        while (!terminated.get()) {
            var message: CarLifeMessage? = null
            try {
                message = communicator.read()
                dispatch(message)
            } catch (e: Exception) {
                if (!terminated.get()) {
                    listener.onBootstrapFailed("BT_RFCOMM_CLOSED")
                }
                break
            } finally {
                message?.recycle()
            }
        }
    }

    private fun dispatch(message: CarLifeMessage) {
        when (message.serviceType) {
            ServiceTypes.MSG_WIRELESS_INFO_REQUEST -> {
                listener.onBootstrapMessage("info-request")
                val response = CarLifeMessage.obtain(
                    Constants.MSG_CHANNEL_CMD,
                    ServiceTypes.MSG_WIRELESS_INFO_RESPONSE,
                )
                response.payload(
                    CarlifeWirlessInfoProto.CarlifeWirlessInfo.newBuilder()
                        .setWirlessType(advertisedType)
                        .setWifiFrequency(advertisedFrequency)
                        .build(),
                )
                communicator.write(response)
                response.recycle()
                listener.onBootstrapMessage("info-response-sent")
            }
            ServiceTypes.MSG_WIRELESS_TARGET_INFO_REQUEST -> {
                // OBSERVE ONLY - see class KDoc.
                listener.onBootstrapMessage("target-info-request")
            }
            ServiceTypes.MSG_WIRELESS_RESPONSE_IP -> {
                listener.onBootstrapMessage("response-ip")
                val ip = (message.protoPayload as? CarlifeWirlessIpProto.CarlifeWirlessIp)
                    ?.wirlessip ?: ""
                if (WirelessIpValidator.isValid(ip)) {
                    listener.onWirelessIp(ip)
                } else {
                    listener.onBootstrapFailed("PHONE_IP_INVALID")
                }
            }
            ServiceTypes.MSG_WIRELESS_MD_STATUS -> listener.onBootstrapMessage("md-status")
            else -> listener.onBootstrapMessage("other")
        }
    }

    /** Idempotent stop: closes the RFCOMM communicator and ends the loop. */
    fun terminate() {
        if (!terminated.getAndSet(true)) {
            runCatching { communicator.terminate() }
            thread?.interrupt()
            thread = null
        }
    }
}

/**
 * Session-scoped modern-wireless Bluetooth bootstrap (Phase 9.2W-B1).
 *
 * Deterministic target selection (section 10/11): exact-name match on BONDED
 * devices only - never the `isConnected()` reflection fallback, never a random
 * paired device, never a MAC address anywhere. The RFCOMM connect runs on the
 * SDK IO executor via [BluetoothDeviceDiscover]; [terminate] aborts the
 * in-flight socket and fences every late callback.
 */
class CarLifeWirelessBootstrap(
    private val context: CarLifeContext,
    private val targetName: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onBluetoothSearching(targetName: String) {}
        fun onBluetoothTargetFound(targetName: String) {}
        fun onBluetoothRfcommConnecting(targetName: String) {}
        fun onBluetoothRfcommConnected(targetName: String) {}
        /** kind: "info-request" / "info-response-sent" / "target-info-request" / "response-ip" / "md-status" / "other" */
        fun onBootstrapMessage(kind: String) {}
        fun onWirelessIp(ip: String) {}
        fun onBootstrapFailed(reason: String) {}
    }

    private val lock = Any()
    private var session: CarLifeWirelessBootstrapSession? = null
    private var discover: BluetoothDeviceDiscover? = null
    private val terminated = AtomicBoolean(false)

    fun start() {
        if (targetName.isBlank()) {
            listener.onBootstrapFailed("BT_TARGET_NOT_SELECTED")
            return
        }
        listener.onBluetoothSearching(targetName)
        // Deterministic pre-check: the target must be a BONDED device with an
        // EXACT name match. Permission failures are classified, not hidden.
        val bonded = bondedNames()
        if (bonded == null) {
            listener.onBootstrapFailed("BT_PERMISSION")
            return
        }
        if (!bonded.contains(targetName)) {
            listener.onBootstrapFailed("BT_TARGET_NOT_BONDED")
            return
        }
        listener.onBluetoothTargetFound(targetName)
        listener.onBluetoothRfcommConnecting(targetName)
        // Upstream deterministic selection: exact CONFIG_TARGET_BLUETOOTH_NAME
        // match on bonded devices (the reflection fallback is never reached).
        context.setConfig(Configs.CONFIG_TARGET_BLUETOOTH_NAME, targetName)
        val deviceDiscover = BluetoothDeviceDiscover(
            context,
            object : BluetoothDeviceDiscover.Callback {
                override fun onDeviceConnected(communicator: BluetoothCommunicator) {
                    if (terminated.get()) {
                        communicator.terminate()
                        return
                    }
                    listener.onBluetoothRfcommConnected(targetName)
                    val bootstrapSession = CarLifeWirelessBootstrapSession(communicator, listener)
                    synchronized(lock) {
                        if (terminated.get()) {
                            bootstrapSession.terminate()
                            return
                        }
                        session = bootstrapSession
                    }
                    bootstrapSession.start()
                }
            },
        )
        synchronized(lock) {
            if (terminated.get()) return
            discover = deviceDiscover
        }
        deviceDiscover.startDiscover()
    }

    /** Idempotent session teardown: RFCOMM, discovery callbacks, message loop. */
    fun terminate() {
        if (!terminated.getAndSet(true)) return
        val currentSession: CarLifeWirelessBootstrapSession?
        val currentDiscover: BluetoothDeviceDiscover?
        synchronized(lock) {
            currentSession = session
            session = null
            currentDiscover = discover
            discover = null
        }
        currentSession?.terminate()
        currentDiscover?.terminate()
    }

    /** Bonded device NAMES only (no MAC/address/serial); null = permission denied. */
    private fun bondedNames(): List<String>? = try {
        val manager = context.applicationContext
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.bondedDevices
            ?.mapNotNull { it.name }
            ?.sorted()
    } catch (e: SecurityException) {
        Logger.e(Constants.TAG, "CarLifeWirelessBootstrap bondedDevices permission denied")
        null
    } catch (e: Exception) {
        Logger.e(Constants.TAG, "CarLifeWirelessBootstrap bondedDevices exception ", e)
        null
    }
}
