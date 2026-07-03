package ai.tetsuo.agenc.ledger

import ai.tetsuo.agenc.ui.screens.AgentTransferRequest
import ai.tetsuo.agenc.ui.screens.LedgerUi
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state machine behind [ai.tetsuo.agenc.ui.screens.LedgerScreen] — the Android side of the
 * "agent stack" transfer flow:
 *
 *   agent (Mac) ──elicitation {type:"solana_transfer"}──▶ app ──BLE──▶ Ledger Flex
 *                                                          ▲  human approves ON DEVICE
 *   agent ◀──elicitation answer = tx signature── app ◀──signed tx──┘ (app broadcasts via RPC)
 *
 * The controller owns the BLE transport + Solana APDU client + RPC and publishes [LedgerUi] for
 * the stateless screen. The private key never leaves the device and the human confirms the exact
 * amount/recipient on the Flex's own screen — the app and the agent are both untrusted here.
 */
class LedgerController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                android.util.Log.e("Ledger", "ledger coroutine crashed", e)
            },
    ),
) {
    private val _ui = MutableStateFlow<LedgerUi>(LedgerUi.Idle)
    val ui: StateFlow<LedgerUi> = _ui.asStateFlow()

    private val _agentRequest = MutableStateFlow<AgentTransferRequest?>(null)
    val agentRequest: StateFlow<AgentTransferRequest?> = _agentRequest.asStateFlow()

    /** Fires with the base58 tx signature after a successful broadcast (answers the elicitation). */
    var onSigned: ((String) -> Unit)? = null

    private var transport: LedgerBleTransport? = null
    private var app: SolanaLedgerApp? = null
    private var pubkey: ByteArray? = null
    private var deviceName: String = "Ledger"
    private var connectedDevice: LedgerBleTransport.DiscoveredDevice? = null
    private var scanJob: Job? = null
    private var keepaliveJob: Job? = null
    /** Agent/demo transfer: auto-connect to the first Ledger found + go straight to on-device review. */
    private var autoConnect = false
    private val rpc = SolanaRpc()
    private val path = SolanaLedgerApp.DEFAULT_PATH

    /** Runtime permissions BLE needs on this API level (requested by the host activity). */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Enter the flow — [request] non-null when the agent asked for a specific transfer. */
    fun open(request: AgentTransferRequest? = null) {
        _agentRequest.value = request
        if (!hasPermissions()) {
            _ui.value = LedgerUi.NeedPermissions
            return
        }
        val t = transport
        val pk = pubkey
        if (request != null && t != null && pk != null &&
            t.state.value is LedgerBleTransport.State.Ready
        ) {
            // Still connected from the pairing step — go straight to on-device review.
            _ui.value = LedgerUi.Connected(deviceName, Base58.encode(pk), null)
            startKeepalive()
            maybeAutoSubmit()
            return
        }
        _ui.value = LedgerUi.Idle
        if (request != null) {
            // Agent/demo transfer: auto scan + connect to the Flex so the transfer "just appears".
            autoConnect = true
            startScan()
        }
    }

    /** Agent/demo transfer: push the prefilled transfer straight to the Flex for approval. */
    private fun maybeAutoSubmit() {
        val req = _agentRequest.value ?: return
        send(req.toBase58, req.lamports / 1_000_000_000.0)
    }

    /** The host activity reports the permission dialog outcome. */
    fun onPermissionsResult(granted: Boolean) {
        _ui.value = if (granted) LedgerUi.Idle else LedgerUi.NeedPermissions
        if (granted) startScan()
    }

    fun startScan() {
        if (!hasPermissions()) {
            _ui.value = LedgerUi.NeedPermissions
            return
        }
        val t = transport ?: LedgerBleTransport(context).also { transport = it }
        _ui.value = LedgerUi.Scanning(emptyList())
        t.startScan()
        scanJob?.cancel()
        scanJob = scope.launch {
            t.found.collect { devices ->
                if (autoConnect && devices.isNotEmpty()) {
                    autoConnect = false
                    connect(devices.first().address)
                    return@collect
                }
                if (_ui.value is LedgerUi.Scanning) {
                    _ui.value = LedgerUi.Scanning(devices.map { it.name to it.address })
                }
            }
        }
    }

    fun connect(address: String) {
        val t = transport ?: return
        val dev = t.found.value.firstOrNull { it.address == address } ?: return
        deviceName = dev.name
        connectedDevice = dev
        scanJob?.cancel()
        t.stopScan()
        _ui.value = LedgerUi.Connecting(dev.name)
        scope.launch {
            try {
                t.connect(dev)
                val a = SolanaLedgerApp(t).also { app = it }
                val pk = getPubkeyOpeningAppIfNeeded(a, t, dev)
                pubkey = pk
                val address58 = Base58.encode(pk)
                android.util.Log.i("Ledger", "account address = $address58")
                _ui.value = LedgerUi.Connected(dev.name, address58, balanceSol = null)
                startKeepalive() // the Flex idle-disconnects (~20s); keep the link warm
                maybeAutoSubmit() // agent/demo transfer → straight to on-device review
                // Balance is cosmetic — fetch it after the account card is already visible.
                runCatching { rpc.balanceLamports(address58) }.getOrNull()?.let { lamports ->
                    if (_ui.value is LedgerUi.Connected) {
                        _ui.value = LedgerUi.Connected(dev.name, address58, lamports / 1_000_000_000.0)
                    }
                }
            } catch (e: Throwable) {
                _ui.value = LedgerUi.Error(e.message ?: "couldn't connect to the Ledger")
            }
        }
    }

    /**
     * The Flex drops the BLE link after ~20s of silence (GATT_CONN_TIMEOUT), which would wipe the
     * transfer form and make a later Review do nothing. Keep the link warm with a benign, prompt-free
     * GET_PUBKEY every few seconds while the account card is showing (skipped during confirm/broadcast).
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(8_000)
                if (_ui.value !is LedgerUi.Connected) continue // don't fight the sign exchange
                val a = app ?: break
                if (runCatching { a.getPubkey(path) }.isFailure) break // link died — stop nudging
            }
        }
    }

    /** Ensure the transport is Ready, reconnecting (bond is cached, so it's quick) if it dropped. */
    private suspend fun ensureReady(): SolanaLedgerApp {
        val t = transport
        val existing = app
        if (t != null && existing != null && t.state.value is LedgerBleTransport.State.Ready) return existing
        val dev = connectedDevice ?: throw java.io.IOException("reconnect the Ledger")
        val tt = t ?: LedgerBleTransport(context).also { transport = it }
        tt.connect(dev)
        val a = SolanaLedgerApp(tt).also { app = it }
        // Make sure the Solana app is open on the freshly re-dialed link.
        pubkey = getPubkeyOpeningAppIfNeeded(a, tt, dev)
        return a
    }

    /**
     * GET_PUBKEY, auto-opening the Solana app if the Flex is on the dashboard (the "agent stack"
     * open-app APDU). Opening an app can reset the BLE link on the Flex, so if the retry hits a
     * transport error we reconnect once and try again.
     */
    private suspend fun getPubkeyOpeningAppIfNeeded(
        a: SolanaLedgerApp,
        t: LedgerBleTransport,
        dev: LedgerBleTransport.DiscoveredDevice,
    ): ByteArray {
        try {
            return a.getPubkey(path)
        } catch (e: SolanaLedgerApp.ApduException) {
            if (!a.isAppNotOpen(e.sw)) throw e
        }
        // On the dashboard → launch Solana, give it a moment, then retry (reconnecting if the
        // app switch dropped the BLE link).
        _ui.value = LedgerUi.Connecting("opening Solana on ${deviceName}")
        runCatching { a.openApp("Solana") }
        kotlinx.coroutines.delay(3500)
        return try {
            a.getPubkey(path)
        } catch (_: java.io.IOException) {
            t.connect(dev)
            val a2 = SolanaLedgerApp(t).also { app = it }
            a2.getPubkey(path)
        }
    }

    /** Build the transfer, have the human approve it ON the Flex, then broadcast. */
    fun send(toBase58: String, amountSol: Double) {
        val lamports = Math.round(amountSol * 1_000_000_000.0)
        if (lamports <= 0) {
            _ui.value = LedgerUi.Error("enter an amount greater than 0")
            return
        }
        keepaliveJob?.cancel() // the sign exchange owns the link now
        scope.launch {
            try {
                val to = Base58.decode(toBase58)
                require(to.size == 32) { "recipient is not a valid Solana address" }
                // The link may have idle-dropped since connect; re-establish it (bond is cached).
                _ui.value = LedgerUi.ConfirmOnDevice("preparing on ${deviceName}…")
                val a = ensureReady()
                val from = pubkey ?: throw java.io.IOException("reconnect the Ledger")
                val blockhash = rpc.latestBlockhash()
                val message = SolanaTx.transferMessage(from, to, lamports, blockhash)
                _ui.value = LedgerUi.ConfirmOnDevice(
                    "◎ ${"%.4f".format(amountSol)} → ${toBase58.take(6)}…${toBase58.takeLast(6)}",
                )
                val signature = a.signTransaction(path, message) // blocks on the human's approval
                _ui.value = LedgerUi.Broadcasting
                val txSig = rpc.sendTransaction(SolanaTx.wrapSignedTx(signature, message))
                _ui.value = LedgerUi.Done(txSig)
                onSigned?.invoke(txSig)
            } catch (e: Throwable) {
                _ui.value = LedgerUi.Error(e.message ?: "transfer failed")
            }
        }
    }

    fun retry() {
        // From an error, fall back to the closest sensible entry point.
        _ui.value = if (pubkey != null && app != null) {
            LedgerUi.Connected(deviceName, Base58.encode(pubkey!!), null)
        } else {
            LedgerUi.Idle
        }
    }

    /**
     * Hide the flow but KEEP the BLE link alive (keepalive keeps pinging), so pairing in one step
     * and transferring in a later step is instant instead of re-scanning. Clears the pending request.
     */
    fun hide() {
        _agentRequest.value = null
        autoConnect = false
    }

    /** Leave the flow: drop BLE and reset (the Flex only allows one BLE central at a time). */
    fun closeFlow() {
        scanJob?.cancel()
        keepaliveJob?.cancel()
        runCatching { transport?.close() }
        transport = null
        app = null
        pubkey = null
        connectedDevice = null
        _agentRequest.value = null
        onSigned = null
        _ui.value = LedgerUi.Idle
    }

    companion object {
        /**
         * Parse an agent elicitation question into a transfer request. The agent asks by embedding
         * JSON: {"type":"solana_transfer","to":"<base58>","lamports":123,"note":"..."} — anywhere
         * in the question text. Returns null when the question isn't a transfer ask. The values are
         * UNTRUSTED (they prefill the form); the human's on-device review is the real gate.
         */
        fun parseTransferRequest(question: String?): AgentTransferRequest? {
            if (question.isNullOrEmpty()) return null
            val start = question.indexOf('{')
            val end = question.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(
                    question.substring(start, end + 1),
                ) as? kotlinx.serialization.json.JsonObject ?: return null
                val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (type != "solana_transfer") return null
                val to = (obj["to"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
                val lamports = (obj["lamports"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toLongOrNull() ?: return null
                if (to.length !in 32..44 || lamports <= 0) return null
                val note = (obj["note"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                AgentTransferRequest(toBase58 = to, lamports = lamports, note = note)
            }.getOrNull()
        }
    }
}
