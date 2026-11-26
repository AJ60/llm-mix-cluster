package com.example.service.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.example.service.RpcService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Module 3 (Android Side): Wi-Fi Direct P2P Mesh Network Manager.
 *
 * Negotiates an autonomous 5GHz Wi-Fi Direct Group Owner (GO) so edge devices can form
 * a router-free ad-hoc cluster on the link-local 192.168.49.x subnet while preserving
 * 32-byte HMAC cluster authentication and CRC-8 QR code pairing.
 */
data class WifiDirectMeshState(
    val active: Boolean = false,
    val isGroupOwner: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val groupOwnerAddress: String = "192.168.49.1",
    val rpcPort: Int = 50052,
    val qrPayload: String = "",
    val crc8Hex: String = "",
    val statusMessage: String = "Idle — ready for router-free P2P mesh"
)

class WifiDirectMeshManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(WifiDirectMeshState())
    val state: StateFlow<WifiDirectMeshState> = _state.asStateFlow()

    fun initialize() {
        if (manager == null) {
            _state.update { it.copy(statusMessage = "Wi-Fi Direct hardware unavailable on this device") }
            return
        }
        channel = manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
    }

    /**
     * Creates an autonomous Wi-Fi Direct Group Owner on 192.168.49.1:50052 and builds
     * the CRC-8 authenticated QR payload for Master / peer scanning.
     */
    @SuppressLint("MissingPermission")
    fun startAutonomousGroupOwner(clusterSecret: String, rpcPort: Int = 50052) {
        val mgr = manager
        val ch = channel ?: run {
            initialize()
            channel
        }
        if (mgr == null || ch == null) {
            _state.update { it.copy(statusMessage = "WifiP2pManager unavailable") }
            return
        }

        val actionListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestGroupAndConnectionDetails(mgr, ch, clusterSecret, rpcPort)
            }

            override fun onFailure(reason: Int) {
                _state.update {
                    it.copy(
                        active = false,
                        statusMessage = "Wi-Fi Direct GO creation failed (code $reason)"
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ssid = "DIRECT-SC-${Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").take(10).ifEmpty { "NODE" }}"
            val pass = "SilicaP2P-${(100000..999999).random()}"
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pass)
                .enablePersistentMode(false)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
            mgr.createGroup(ch, config, actionListener)
        } else {
            mgr.createGroup(ch, actionListener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupAndConnectionDetails(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        clusterSecret: String,
        rpcPort: Int
    ) {
        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            val ssid = group?.networkName ?: "DIRECT-SC-SILICA"
            val pass = group?.passphrase ?: "silica-p2p-wpa3"
            mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                val goIp = info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                val encoded = buildQrPayload(ssid, pass, "$goIp:$rpcPort", clusterSecret)
                _state.update {
                    it.copy(
                        active = true,
                        isGroupOwner = info?.isGroupOwner ?: true,
                        ssid = ssid,
                        passphrase = pass,
                        groupOwnerAddress = goIp,
                        rpcPort = rpcPort,
                        qrPayload = encoded.first,
                        crc8Hex = encoded.second,
                        statusMessage = "5GHz P2P Group Owner active at $goIp:$rpcPort"
                    )
                }
                RpcService.logEngineEvent("Wi-Fi Direct P2P GO active: $ssid ($goIp:$rpcPort)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.update { WifiDirectMeshState(statusMessage = "Wi-Fi Direct P2P group closed") }
            }

            override fun onFailure(reason: Int) {
                _state.update { it.copy(active = false, statusMessage = "P2P teardown error ($reason)") }
            }
        })
    }

    companion object {
        /** Standard CRC-8 (polynomial 0x07) matching Master wifiDirectBridge.ts bit-for-bit. */
        fun computeCrc8(input: String): Int {
            var crc = 0x00
            for (b in input.toByteArray(Charsets.UTF_8)) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) {
                        ((crc shl 1) xor 0x07) and 0xFF
                    } else {
                        (crc shl 1) and 0xFF
                    }
                }
            }
            return crc
        }

        fun buildQrPayload(
            ssid: String,
            passphrase: String,
            endpoint: String,
            clusterSecret: String
        ): Pair<String, String> {
            val cleanSsid = ssid.replace("|", "").trim().ifEmpty { "DIRECT-SC-SILICA" }
            val cleanPass = passphrase.replace("|", "").trim().ifEmpty { "silica-p2p-wpa3" }
            val cleanEp = endpoint.replace("|", "").trim().ifEmpty { "192.168.49.1:50052" }
            val authBytes = RpcService.computeClusterAuthToken(clusterSecret)
            val authHex = authBytes.joinToString("") { "%02x".format(it) }
            val body = "SILICA_P2P_V1|$cleanSsid|$cleanPass|$cleanEp|$authHex"
            val crcHex = "%02X".format(computeCrc8(body))
            return Pair("$body|$crcHex", crcHex)
        }
    }
}
