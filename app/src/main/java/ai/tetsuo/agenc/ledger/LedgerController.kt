package ai.tetsuo.agenc.ledger

import ai.tetsuo.agenc.data.LedgerHardwareReviewClaimResult
import ai.tetsuo.agenc.data.PendingLedgerResponseRecord
import ai.tetsuo.agenc.data.Prefs
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import ai.tetsuo.agenc.ui.screens.AgentTransferRequest
import ai.tetsuo.agenc.ui.screens.LedgerAccountUi
import ai.tetsuo.agenc.ui.screens.LedgerUi
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Public, non-secret context for the Ledger account the user selected as AgenC's default.
 * Reading this object never opens Bluetooth and never grants signing authority.
 */
data class SelectedWalletContext(
    val accountKey: String,
    val derivationPath: List<Int>,
    val derivationPathLabel: String,
    val address: String,
    val deviceName: String?,
    val deviceAddress: String?,
)

internal enum class LedgerAutoForwardDecision {
    MANUAL,
    WAITING_FOR_DEFAULT,
    READY,
    EXPIRED,
    RECOVERY_MISMATCH,
    ALREADY_FORWARDED,
}

/**
 * Pure gate for automatically forwarding a Core-authored request to physical Ledger review.
 * A non-null [AgentTransferRequest] is not sufficient: only requests carrying Core's intent id,
 * an unexpired authorization, an exact durable recovery record, and the selected default wallet
 * can cross this boundary. Manual/scripted requests remain button-driven.
 */
internal fun planLedgerAutoForward(
    request: AgentTransferRequest?,
    selected: SelectedWalletContext?,
    recovery: PendingLedgerResponseRecord?,
    nowEpochMillis: Long,
): LedgerAutoForwardDecision {
    val intentId = request?.intentId ?: return LedgerAutoForwardDecision.MANUAL
    val responseNonce = request.responseNonce
        ?: return LedgerAutoForwardDecision.RECOVERY_MISMATCH
    val expiresAt = request.expiresAtEpochMillis
        ?: return LedgerAutoForwardDecision.RECOVERY_MISMATCH
    if (expiresAt <= nowEpochMillis) return LedgerAutoForwardDecision.EXPIRED
    val source = selected ?: return LedgerAutoForwardDecision.WAITING_FOR_DEFAULT
    val matches = recovery != null && recovery.receipt == null &&
        recovery.intentId == intentId &&
        recovery.responseNonce == responseNonce &&
        recovery.expiresAtEpochMillis == expiresAt &&
        recovery.sourceAddress == source.address &&
        recovery.recipientAddress == request.toBase58 &&
        recovery.lamports == request.lamports
    if (!matches) return LedgerAutoForwardDecision.RECOVERY_MISMATCH
    return if (recovery.hardwareReviewStarted) {
        LedgerAutoForwardDecision.ALREADY_FORWARDED
    } else {
        LedgerAutoForwardDecision.READY
    }
}

private data class LedgerAccountRecord(
    val spec: SolanaLedgerApp.AccountPath,
    val address: String,
    val balanceLamports: Long? = null,
)

private data class PendingSignedBroadcast(
    val signature: String,
    val transaction: ByteArray,
    val lastValidBlockHeight: Long,
)

/**
 * The state machine behind [ai.tetsuo.agenc.ui.screens.LedgerScreen] — the Android side of the
 * "agent stack" transfer flow:
 *
 *   Core ──typed clientAction {type:"ledger_solana_transfer_v1"}──▶ app ──BLE──▶ Ledger Flex
 *                                                                     ▲  human approves ON DEVICE
 *   Core ◀──typed, challenge-bound receipt── app ◀──persisted signed bytes──┘
 *                                                └──broadcasts the exact bytes via Solana RPC
 *
 * The controller owns the BLE transport + Solana APDU client + RPC and publishes [LedgerUi] for
 * the stateless screen. The private key never leaves the device and the human confirms the exact
 * amount/recipient on the Flex's own screen. A submitted receipt is deliberately not represented
 * as chain confirmation; Core receives `confirmed:false` after validating the typed receipt.
 */
class LedgerController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                android.util.Log.e(TAG, "ledger coroutine crashed", e)
            },
    ),
) {
    private val _ui = MutableStateFlow<LedgerUi>(LedgerUi.Idle)
    val ui: StateFlow<LedgerUi> = _ui.asStateFlow()

    private val _agentRequest = MutableStateFlow<AgentTransferRequest?>(null)
    val agentRequest: StateFlow<AgentTransferRequest?> = _agentRequest.asStateFlow()

    private var transport: LedgerBleTransport? = null
    private var app: SolanaLedgerApp? = null
    private var pubkey: ByteArray? = null
    private var deviceName: String = prefs.ledgerDeviceName ?: "Ledger"
    private var connectedDevice: LedgerBleTransport.DiscoveredDevice? = null
    private var scanJob: Job? = null
    private var accountJob: Job? = null
    private var balanceJob: Job? = null
    private var keepaliveJob: Job? = null
    @Volatile private var sendJob: Job? = null
    private val sendInFlight = AtomicBoolean(false)
    private val flowGeneration = AtomicLong(0)
    private val scanGeneration = AtomicLong(0)
    private val rpc = SolanaRpc()
    private var pendingBroadcast: PendingSignedBroadcast? = prefs.pendingLedgerBroadcast()?.let { saved ->
        runCatching {
            val signature = saved.signature
            require(Base58.decode(signature).size == 64)
            PendingSignedBroadcast(
                signature = signature,
                transaction = java.util.Base64.getDecoder().decode(saved.transactionBase64),
                lastValidBlockHeight = saved.lastValidBlockHeight,
            )
        }.getOrNull()
    }

    private val accountLock = Any()
    private var accountRecords: List<LedgerAccountRecord> = emptyList()
    private var discoveryCount: Int = prefs.ledgerDiscoveryCount.coerceIn(
        INITIAL_DISCOVERY_COUNT,
        SolanaLedgerApp.MAX_COMMON_ACCOUNT_COUNT,
    )

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

    /**
     * Read the user's persisted default wallet without touching BLE. SharedPreferences reads are
     * thread-safe, and a fresh immutable object is returned on every call so chat routing can use
     * this safely from any dispatcher — including immediately after a process restart.
     */
    fun selectedWalletContext(): SelectedWalletContext? {
        val accountKey = prefs.ledgerAccountPathKey ?: return null
        val path = SolanaLedgerApp.pathFromKey(accountKey) ?: return null
        val address = prefs.ledgerAccountAddress ?: return null
        val deviceAddress = prefs.ledgerDeviceAddress ?: return null
        val validAddress = runCatching { Base58.decode(address).size == 32 }.getOrDefault(false)
        if (!validAddress) return null
        return SelectedWalletContext(
            accountKey = accountKey,
            derivationPath = path.toList(),
            derivationPathLabel = SolanaLedgerApp.pathLabel(path),
            address = address,
            deviceName = prefs.ledgerDeviceName,
            deviceAddress = deviceAddress,
        )
    }

    /** Surface a fail-closed host/persistence error without starting any BLE or signing operation. */
    fun showError(message: String) {
        _ui.value = LedgerUi.Error(message)
    }

    /** Enter the flow — [request] non-null when the agent asked for a specific transfer. */
    fun open(request: AgentTransferRequest? = null) {
        flowGeneration.incrementAndGet()
        _agentRequest.value = request
        pendingBroadcast?.let {
            _ui.value = LedgerUi.BroadcastUncertain(it.signature)
            return
        }
        if (!hasPermissions()) {
            _ui.value = LedgerUi.NeedPermissions
            return
        }

        val t = transport
        val hasLiveAccounts = synchronized(accountLock) { accountRecords.isNotEmpty() }
        if (t?.state?.value is LedgerBleTransport.State.Ready && hasLiveAccounts) {
            publishConnected()
            startKeepalive()
            maybeAutoForwardAgentRequest()
            return
        }

        val directTransport = t ?: LedgerBleTransport(context).also { transport = it }
        val remembered = rememberedDevice(directTransport)
        if (remembered != null) {
            connectDevice(remembered)
            return
        }

        _ui.value = LedgerUi.Idle
        if (request != null) startScan()
    }

    /** The host activity reports the permission dialog outcome. */
    fun onPermissionsResult(granted: Boolean) {
        if (!granted) {
            _ui.value = LedgerUi.NeedPermissions
            return
        }
        val request = _agentRequest.value
        val t = transport ?: LedgerBleTransport(context).also { transport = it }
        val remembered = rememberedDevice(t)
        if (remembered != null) connectDevice(remembered) else if (request != null) startScan() else {
            _ui.value = LedgerUi.Idle
        }
    }

    private fun rememberedDevice(t: LedgerBleTransport): LedgerBleTransport.DiscoveredDevice? {
        val address = prefs.ledgerDeviceAddress ?: return null
        return t.rememberedDevice(address, prefs.ledgerDeviceName)
    }

    fun startScan() {
        if (!hasPermissions()) {
            _ui.value = LedgerUi.NeedPermissions
            return
        }
        val t = transport ?: LedgerBleTransport(context).also { transport = it }
        val generation = scanGeneration.incrementAndGet()
        scanJob?.cancel()
        _ui.value = LedgerUi.Scanning(emptyList())
        t.startScan()
        scanJob = scope.launch {
            combine(t.state, t.found) { state, devices -> state to devices }
                .takeWhile { (state, devices) ->
                    if (generation != scanGeneration.get()) return@takeWhile false
                    when (state) {
                        is LedgerBleTransport.State.Failed -> {
                            _ui.value = LedgerUi.Error(state.reason)
                            false
                        }
                        LedgerBleTransport.State.Scanning -> {
                            if (_ui.value is LedgerUi.Scanning) {
                                _ui.value = LedgerUi.Scanning(devices.map { it.name to it.address })
                            }
                            true
                        }
                        else -> true
                    }
                }
                .collect {}
        }
    }

    /** Connect a device selected from the scan picker. */
    fun connect(address: String) {
        val t = transport ?: return
        val dev = t.found.value.firstOrNull { it.address == address } ?: return
        connectDevice(dev)
    }

    private fun connectDevice(dev: LedgerBleTransport.DiscoveredDevice) {
        val t = transport ?: LedgerBleTransport(context).also { transport = it }
        deviceName = dev.name
        connectedDevice = dev
        scanGeneration.incrementAndGet()
        scanJob?.cancel()
        scanJob = null
        t.stopScan()
        keepaliveJob?.cancel()
        balanceJob?.cancel()
        val previousAccountJob = accountJob
        previousAccountJob?.cancel()
        _ui.value = LedgerUi.Connecting(dev.name)
        val generation = flowGeneration.get()
        accountJob = scope.launch {
            try {
                // Cancellation closes the previous attempt's shared GATT handle. Wait for that
                // cleanup before opening a new connection so a rapid second device tap cannot let
                // the old coroutine tear down the new device's GATT session.
                previousAccountJob?.join()
                t.connect(dev)
                val solana = ensureSolanaAppReady(t, dev).also { app = it }
                if (generation != flowGeneration.get()) return@launch

                val sameAssociation = prefs.ledgerDeviceAddress.equals(dev.address, ignoreCase = true)
                val savedSelection = if (sameAssociation) selectedWalletContext() else null
                val count = if (sameAssociation) {
                    prefs.ledgerDiscoveryCount.coerceIn(
                        INITIAL_DISCOVERY_COUNT,
                        SolanaLedgerApp.MAX_COMMON_ACCOUNT_COUNT,
                    )
                } else {
                    INITIAL_DISCOVERY_COUNT
                }
                discoveryCount = count
                _ui.value = LedgerUi.Connecting("reading Solana accounts")
                val records = deriveAccounts(solana, accountSpecs(count, savedSelection))
                if (records.isEmpty()) throw IOException("No Solana accounts could be read")
                if (generation != flowGeneration.get()) return@launch

                synchronized(accountLock) { accountRecords = records }
                prefs.setLedgerDeviceAssociation(dev.address, dev.name, count)

                val selected = if (savedSelection != null) {
                    records.firstOrNull {
                        it.spec.key == savedSelection.accountKey &&
                            it.address == savedSelection.address
                    }
                } else {
                    null
                }
                if (selected != null) {
                    if (!persistSelection(selected)) {
                        throw IOException("Couldn't durably restore the default Ledger account")
                    }
                } else {
                    // A remembered path that derives a different pubkey means the Ledger seed (or
                    // device association) changed. Never silently redirect the default to account 0.
                    prefs.clearLedgerAccountSelection()
                    pubkey = null
                }
                android.util.Log.i(TAG, "loaded ${records.size} Ledger Solana accounts")
                // If the remembered path no longer matches, keep the typed transfer locked in the
                // form but require a fresh explicit account-row selection before Send is enabled.
                publishConnected()
                startKeepalive()
                refreshBalances(records.map { it.spec.key }, generation)
                maybeAutoForwardAgentRequest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == flowGeneration.get()) {
                    _ui.value = LedgerUi.Error(e.message ?: "couldn't connect to the Ledger")
                }
            }
        }
    }

    private fun accountSpecs(
        count: Int,
        selected: SelectedWalletContext? = null,
    ): List<SolanaLedgerApp.AccountPath> {
        val specs = LinkedHashMap<String, SolanaLedgerApp.AccountPath>()
        SolanaLedgerApp.commonAccountPaths(count).forEach { specs[it.key] = it }
        selected?.let {
            val path = it.derivationPath
            val key = SolanaLedgerApp.pathKey(path)
            specs.putIfAbsent(key, SolanaLedgerApp.AccountPath(key, SolanaLedgerApp.pathLabel(path), path))
        }
        return specs.values.toList()
    }

    /** Derive public addresses serially; Ledger transport itself is intentionally single-flight. */
    private suspend fun deriveAccounts(
        solana: SolanaLedgerApp,
        specs: List<SolanaLedgerApp.AccountPath>,
        existing: Map<String, LedgerAccountRecord> = emptyMap(),
    ): List<LedgerAccountRecord> {
        val records = ArrayList<LedgerAccountRecord>(specs.size)
        for (spec in specs) {
            val cached = existing[spec.key]
            if (cached != null) {
                records += cached
                continue
            }
            try {
                val address = Base58.encode(solana.getPubkey(spec.path))
                records += LedgerAccountRecord(spec = spec, address = address)
            } catch (e: SolanaLedgerApp.ApduException) {
                // Some old Solana app versions do not accept every legacy derivation family.
                android.util.Log.w(TAG, "skipping unsupported path ${spec.label}: ${e.message}")
            }
        }
        return records
    }

    /**
     * Mirror DMK's explicit GetAppAndVersion → CloseApp → OpenApp lifecycle. Both CloseApp and
     * OpenApp intentionally reset the Ledger session, so every such command is followed by a fresh
     * authenticated GATT connection and verified with GetAppAndVersion.
     */
    private suspend fun ensureSolanaAppReady(
        t: LedgerBleTransport,
        dev: LedgerBleTransport.DiscoveredDevice,
    ): SolanaLedgerApp {
        var current = SolanaLedgerApp(t)
        var info = current.getAppAndVersion()
        if (info.name.equals(SOLANA_APP_NAME, ignoreCase = true)) return current

        if (!isDashboard(info.name)) {
            _ui.value = LedgerUi.Connecting("closing ${info.name} on $deviceName")
            commandThatResetsSession("CloseApp") { current.closeApp() }
            delay(APP_SWITCH_DELAY_MS)
            t.connect(dev)
            current = SolanaLedgerApp(t)
            info = current.getAppAndVersion()
            if (!isDashboard(info.name) && !info.name.equals(SOLANA_APP_NAME, ignoreCase = true)) {
                throw IOException("Couldn't close ${info.name} on the Ledger")
            }
        }

        if (!info.name.equals(SOLANA_APP_NAME, ignoreCase = true)) {
            _ui.value = LedgerUi.Connecting("opening Solana on $deviceName")
            commandThatResetsSession("OpenApp") { current.openApp(SOLANA_APP_NAME) }
            delay(APP_SWITCH_DELAY_MS)
            t.connect(dev)
            current = SolanaLedgerApp(t)
            info = current.getAppAndVersion()
        }

        if (!info.name.equals(SOLANA_APP_NAME, ignoreCase = true)) {
            throw IOException("Solana did not open on the Ledger (current app: ${info.name})")
        }
        return current
    }

    private suspend fun commandThatResetsSession(name: String, command: suspend () -> Unit) {
        try {
            command()
        } catch (e: SolanaLedgerApp.ApduException) {
            // A status word is a real device response (locked, rejected, unknown app), not the
            // expected transport reset. Preserve it so the UI tells the human what happened.
            throw e
        } catch (e: IOException) {
            // DMK marks these commands `triggersDisconnection`; some Android stacks report the
            // expected link reset before delivering the 0x9000 response. The reconnect + app-name
            // verification immediately after this call determines whether the command succeeded.
            android.util.Log.i(TAG, "$name reset the Ledger session: ${e.message}")
        }
    }

    private fun isDashboard(name: String): Boolean =
        name.equals("BOLOS", ignoreCase = true) ||
            name.equals("Dashboard", ignoreCase = true) ||
            name.isBlank()

    /** Select an already-derived account as the persisted default for chat and transfer flows. */
    fun selectDefaultAccount(accountKey: String) {
        val selected = synchronized(accountLock) {
            accountRecords.firstOrNull { it.spec.key == accountKey }
        } ?: return
        if (!persistSelection(selected)) {
            _ui.value = LedgerUi.Error("Couldn't durably save the default Ledger account")
            return
        }
        if (!bindSelectedSourceToTypedRequest(selected.address)) return
        publishConnected()
        startKeepalive()
        maybeAutoForwardAgentRequest()
    }

    private fun persistSelection(record: LedgerAccountRecord): Boolean {
        if (!prefs.setLedgerAccountSelection(record.spec.key, record.address)) return false
        pubkey = Base58.decode(record.address)
        return true
    }

    /**
     * A request that arrived before the user chose a default account has an intentionally blank
     * source in its durable recovery record. Bind that exact request to the explicit row selection
     * before auto-forwarding; a conflicting/non-matching record fails without touching BLE signing.
     */
    private fun bindSelectedSourceToTypedRequest(address: String): Boolean {
        val request = _agentRequest.value ?: return true
        val intentId = request.intentId ?: return true
        val responseNonce = request.responseNonce ?: run {
            _ui.value = LedgerUi.Error("Core's @ledger request has no response challenge")
            return false
        }
        val expiresAt = request.expiresAtEpochMillis
        val recovery = prefs.pendingLedgerResponse()
        val matches = expiresAt != null && recovery != null && recovery.receipt == null &&
            recovery.intentId == intentId &&
            recovery.responseNonce == responseNonce &&
            recovery.expiresAtEpochMillis == expiresAt &&
            recovery.recipientAddress == request.toBase58 &&
            recovery.lamports == request.lamports &&
            (recovery.sourceAddress.isEmpty() || recovery.sourceAddress == address)
        if (!matches) {
            _ui.value = LedgerUi.Error("AgenC couldn't bind this wallet to Core's request")
            return false
        }
        if (recovery.sourceAddress == address) return true
        if (!prefs.setPendingLedgerResponse(recovery.copy(sourceAddress = address))) {
            _ui.value = LedgerUi.Error("Couldn't durably bind this wallet to Core's request")
            return false
        }
        return true
    }

    /** Page the bounded common-account families upward without another BLE scan. */
    fun loadMore() {
        val connected = _ui.value as? LedgerUi.Connected ?: return
        if (connected.isLoadingMore || discoveryCount >= SolanaLedgerApp.MAX_COMMON_ACCOUNT_COUNT) return
        val targetCount = (discoveryCount + DISCOVERY_PAGE_SIZE)
            .coerceAtMost(SolanaLedgerApp.MAX_COMMON_ACCOUNT_COUNT)
        _ui.value = connected.copy(isLoadingMore = true)
        keepaliveJob?.cancel()
        balanceJob?.cancel()
        accountJob?.cancel()
        val generation = flowGeneration.get()
        accountJob = scope.launch {
            try {
                val solana = ensureReady()
                val existing = synchronized(accountLock) { accountRecords.associateBy { it.spec.key } }
                val selected = selectedWalletContext()
                val records = deriveAccounts(solana, accountSpecs(targetCount, selected), existing)
                if (generation != flowGeneration.get()) return@launch
                synchronized(accountLock) { accountRecords = records }
                discoveryCount = targetCount
                prefs.ledgerDiscoveryCount = targetCount
                publishConnected()
                startKeepalive()
                val newKeys = records.map { it.spec.key }.filterNot(existing::containsKey)
                refreshBalances(newKeys, generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == flowGeneration.get()) {
                    publishConnected()
                    _ui.value = LedgerUi.Error(e.message ?: "couldn't load more accounts")
                }
            }
        }
    }

    /**
     * Derive the three standard Solana path families at one exact index. Ledger has no enumerable
     * account registry, so this lets a user reach an account beyond the fast common-path scan
     * without forcing hundreds of slow BLE APDUs.
     */
    fun addAccountIndex(index: Int) {
        val connected = _ui.value as? LedgerUi.Connected ?: return
        if (connected.isLoadingMore || index !in 0..SolanaLedgerApp.MAX_LEDGER_ACCOUNT_INDEX) return
        val requested = SolanaLedgerApp.accountPathsAtIndex(index)
        val existingRecords = synchronized(accountLock) { accountRecords.toList() }
        val existing = existingRecords.associateBy { it.spec.key }
        val newSpecs = requested.filterNot { existing.containsKey(it.key) }
        if (newSpecs.isEmpty()) return
        _ui.value = connected.copy(isLoadingMore = true)
        keepaliveJob?.cancel()
        balanceJob?.cancel()
        accountJob?.cancel()
        val generation = flowGeneration.get()
        accountJob = scope.launch {
            try {
                val solana = ensureReady()
                val specs = existingRecords.map { it.spec } + newSpecs
                val records = deriveAccounts(solana, specs, existing)
                if (generation != flowGeneration.get()) return@launch
                synchronized(accountLock) { accountRecords = records }
                publishConnected()
                startKeepalive()
                refreshBalances(newSpecs.map { it.key }, generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == flowGeneration.get()) {
                    publishConnected()
                    _ui.value = LedgerUi.Error(e.message ?: "couldn't derive that account index")
                }
            }
        }
    }

    private fun publishConnected(isLoadingMore: Boolean = false) {
        val records = synchronized(accountLock) { accountRecords.toList() }
        if (records.isEmpty()) return
        val selectedKey = prefs.ledgerAccountPathKey
        _ui.value = LedgerUi.Connected(
            deviceName = deviceName,
            accounts = records.map {
                LedgerAccountUi(
                    key = it.spec.key,
                    pathLabel = it.spec.label,
                    address = it.address,
                    balanceLamports = it.balanceLamports,
                    isDefault = it.spec.key == selectedKey,
                )
            },
            selectedAccountKey = selectedKey,
            isLoadingMore = isLoadingMore,
            canLoadMore = discoveryCount < SolanaLedgerApp.MAX_COMMON_ACCOUNT_COUNT,
        )
    }

    /**
     * Forward an exact typed Core request as soon as BLE, Solana, and the persisted default account
     * are ready. This only starts the APDU flow; the transfer still requires physical approval on
     * the Ledger. Manual and local scripted flows retain the explicit phone Review button.
     */
    private fun maybeAutoForwardAgentRequest() {
        if (_ui.value !is LedgerUi.Connected || sendInFlight.get()) return
        val request = _agentRequest.value
        when (
            planLedgerAutoForward(
                request = request,
                selected = selectedWalletContext(),
                recovery = prefs.pendingLedgerResponse(),
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            LedgerAutoForwardDecision.MANUAL,
            LedgerAutoForwardDecision.WAITING_FOR_DEFAULT -> Unit

            LedgerAutoForwardDecision.READY -> {
                requireNotNull(request)
                send(request.toBase58, formatLamports(request.lamports))
            }

            LedgerAutoForwardDecision.EXPIRED -> {
                _ui.value = LedgerUi.Error("This @ledger request expired. Ask the agent again.")
            }

            LedgerAutoForwardDecision.RECOVERY_MISMATCH -> {
                _ui.value = LedgerUi.Error("AgenC couldn't verify durable recovery for this request")
            }

            LedgerAutoForwardDecision.ALREADY_FORWARDED -> {
                _ui.value = LedgerUi.Error(
                    "This @ledger request was already sent to physical review. " +
                        "AgenC will not ask the Ledger to sign it again; cancel it and send a new request.",
                )
            }
        }
    }

    /** Fetch balances after addresses are visible; failures are cosmetic and do not hide accounts. */
    private fun refreshBalances(keys: List<String>, generation: Long) {
        balanceJob?.cancel()
        balanceJob = scope.launch {
            for (key in keys) {
                if (generation != flowGeneration.get()) return@launch
                val address = synchronized(accountLock) {
                    accountRecords.firstOrNull { it.spec.key == key }?.address
                } ?: continue
                val balance = runCatching { rpc.balanceLamports(address) }.getOrNull() ?: continue
                synchronized(accountLock) {
                    accountRecords = accountRecords.map {
                        if (it.spec.key == key) it.copy(balanceLamports = balance) else it
                    }
                }
                if (_ui.value is LedgerUi.Connected) {
                    val loading = (_ui.value as LedgerUi.Connected).isLoadingMore
                    publishConnected(isLoadingMore = loading)
                }
            }
        }
    }

    /**
     * Keep the selected account's live BLE link warm with a benign GET_PUBKEY. It also verifies
     * that the device still derives the persisted address before a later send is allowed.
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (_ui.value !is LedgerUi.Connected) continue
                val solana = app ?: break
                val selected = selectedWalletContext() ?: break
                val matches = runCatching {
                    Base58.encode(solana.getPubkey(selected.derivationPath)) == selected.address
                }.getOrDefault(false)
                if (!matches) {
                    runCatching { transport?.close() }
                    app = null
                    pubkey = null
                    _ui.value = LedgerUi.Error(
                        "Ledger disconnected or the selected account changed. Reconnect before sending.",
                    )
                    break
                }
            }
        }
    }

    /** Ensure the transport is Ready, directly reconnecting the remembered device if it dropped. */
    private suspend fun ensureReady(): SolanaLedgerApp {
        val t = transport ?: LedgerBleTransport(context).also { transport = it }
        val existing = app
        if (existing != null && t.state.value is LedgerBleTransport.State.Ready) return existing
        val dev = connectedDevice ?: rememberedDevice(t) ?: throw IOException("Reconnect the Ledger")
        connectedDevice = dev
        deviceName = dev.name
        t.connect(dev)
        return ensureSolanaAppReady(t, dev).also { app = it }
    }

    /** Build the transfer, have the human approve it ON the Flex, then broadcast. */
    fun send(toBase58: String, amountSol: String) {
        pendingBroadcast?.let {
            _ui.value = LedgerUi.BroadcastUncertain(it.signature)
            return
        }
        val amount = runCatching { BigDecimal(amountSol.trim()) }.getOrNull()
        val lamports = runCatching {
            amount
                ?.movePointRight(9)
                ?.setScale(0, RoundingMode.UNNECESSARY)
                ?.longValueExact()
        }.getOrNull()
        if (lamports == null || lamports <= 0) {
            _ui.value = LedgerUi.Error("Enter a valid amount with at most 9 decimals")
            return
        }
        val selected = selectedWalletContext()
        if (selected == null) {
            _ui.value = LedgerUi.Error("Select a default Ledger account first")
            return
        }
        val typedAgentRequest = _agentRequest.value?.takeIf { it.intentId != null }
        if (typedAgentRequest != null) {
            if (typedAgentRequest.toBase58 != toBase58 || typedAgentRequest.lamports != lamports) {
                _ui.value = LedgerUi.Error("The transfer no longer matches Core's locked request")
                return
            }
            when (
                planLedgerAutoForward(
                    request = typedAgentRequest,
                    selected = selected,
                    recovery = prefs.pendingLedgerResponse(),
                    nowEpochMillis = System.currentTimeMillis(),
                )
            ) {
                LedgerAutoForwardDecision.READY -> Unit
                LedgerAutoForwardDecision.EXPIRED -> {
                    _ui.value = LedgerUi.Error("This @ledger request expired. Ask the agent again.")
                    return
                }
                LedgerAutoForwardDecision.ALREADY_FORWARDED -> {
                    _ui.value = LedgerUi.Error(
                        "This @ledger request was already sent to physical review. " +
                            "AgenC will not ask the Ledger to sign it again.",
                    )
                    return
                }
                else -> {
                    _ui.value = LedgerUi.Error("AgenC couldn't verify durable recovery for this request")
                    return
                }
            }
        }
        if (!sendInFlight.compareAndSet(false, true)) return
        val generation = flowGeneration.get()
        keepaliveJob?.cancel()
        _ui.value = LedgerUi.ConfirmOnDevice("preparing on $deviceName…")
        sendJob = scope.launch {
            try {
                val to = Base58.decode(toBase58)
                require(to.size == 32) { "recipient is not a valid Solana address" }
                val solana = ensureReady()
                if (generation != flowGeneration.get()) return@launch

                // Never trust a cached pubkey/path blindly: derive it again from the live Ledger and
                // require an exact match with the persisted public account before building/signing.
                val verifiedFrom = solana.getPubkey(selected.derivationPath)
                val verifiedAddress = Base58.encode(verifiedFrom)
                if (verifiedAddress != selected.address) {
                    throw IOException("The connected Ledger does not match the selected account")
                }
                if (selectedWalletContext()?.accountKey != selected.accountKey) {
                    throw IOException("The default Ledger account changed; review the transfer again")
                }
                pubkey = verifiedFrom

                val blockhash = rpc.latestBlockhash()
                if (generation != flowGeneration.get()) return@launch
                val message = SolanaTx.transferMessage(verifiedFrom, to, lamports, blockhash.bytes)
                val liveBalance = rpc.balanceLamports(verifiedAddress)
                if (generation != flowGeneration.get()) return@launch
                val liveFee = rpc.feeForMessageLamports(message)
                if (generation != flowGeneration.get()) return@launch
                if (liveBalance < liveFee || lamports > liveBalance - liveFee) {
                    throw IOException("insufficient balance for transfer plus network fee")
                }
                if (typedAgentRequest != null) {
                    val intentId = requireNotNull(typedAgentRequest.intentId)
                    val expiresAt = typedAgentRequest.expiresAtEpochMillis
                        ?: throw IOException("Core's @ledger request has no valid expiry")
                    if (expiresAt <= System.currentTimeMillis()) {
                        throw IOException("This @ledger request expired before signing")
                    }
                    when (
                        prefs.claimPendingLedgerHardwareReview(
                            intentId = intentId,
                            responseNonce = requireNotNull(typedAgentRequest.responseNonce),
                            expiresAtEpochMillis = expiresAt,
                            sourceAddress = verifiedAddress,
                            recipientAddress = toBase58,
                            lamports = lamports,
                        )
                    ) {
                        LedgerHardwareReviewClaimResult.CLAIMED -> Unit
                        LedgerHardwareReviewClaimResult.ALREADY_STARTED -> throw IOException(
                            "This @ledger request already crossed physical review; it will not be signed again",
                        )
                        LedgerHardwareReviewClaimResult.MISMATCH -> throw IOException(
                            "Core's durable @ledger request changed before physical review",
                        )
                        LedgerHardwareReviewClaimResult.PERSISTENCE_FAILED -> throw IOException(
                            "Couldn't durably lock this one-time Ledger review; nothing was sent to the device",
                        )
                    }
                }
                _ui.value = LedgerUi.ConfirmOnDevice(
                    "◎ ${formatLamports(lamports)} → ${toBase58.take(6)}…${toBase58.takeLast(6)}",
                )
                val signature = solana.signTransaction(selected.derivationPath, message)
                if (generation != flowGeneration.get()) return@launch
                val signedTransaction = SolanaTx.wrapSignedTx(signature, message)
                val expectedSignature = Base58.encode(signature)
                val pending = PendingSignedBroadcast(
                    expectedSignature,
                    signedTransaction,
                    blockhash.lastValidBlockHeight,
                )
                val persisted = prefs.setPendingLedgerBroadcast(
                    signature = expectedSignature,
                    transactionBase64 = java.util.Base64.getEncoder().encodeToString(signedTransaction),
                    lastValidBlockHeight = blockhash.lastValidBlockHeight,
                )
                if (!persisted) {
                    throw IOException("Couldn't durably save the signed transaction; it was not broadcast")
                }
                pendingBroadcast = pending
                _ui.value = LedgerUi.Broadcasting
                val txSig = broadcastExactTransaction(
                    pending = pending,
                    definitiveRpcErrorsCanClear = true,
                )
                if (generation != flowGeneration.get()) return@launch
                completeBroadcast(txSig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation == flowGeneration.get()) {
                    _ui.value = pendingBroadcast?.let {
                        LedgerUi.BroadcastUncertain(it.signature)
                    } ?: LedgerUi.Error(e.message ?: "transfer failed")
                }
            } finally {
                sendJob = null
                sendInFlight.set(false)
            }
        }
    }

    fun retry() {
        if (pendingBroadcast != null) {
            retryPendingBroadcast()
            return
        }
        val hasAccounts = synchronized(accountLock) { accountRecords.isNotEmpty() }
        if (hasAccounts && transport?.state?.value is LedgerBleTransport.State.Ready) {
            publishConnected()
            startKeepalive()
            maybeAutoForwardAgentRequest()
        } else {
            open(_agentRequest.value)
        }
    }

    /** Check/rebroadcast the exact same signed bytes; this path never asks the Ledger to sign. */
    fun retryPendingBroadcast() {
        val pending = pendingBroadcast ?: return
        if (!sendInFlight.compareAndSet(false, true)) return
        _ui.value = LedgerUi.Broadcasting
        sendJob = scope.launch {
            try {
                val signature = broadcastExactTransaction(
                    pending = pending,
                    definitiveRpcErrorsCanClear = false,
                )
                completeBroadcast(signature)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.value = if (pendingBroadcast != null) {
                    LedgerUi.BroadcastUncertain(pending.signature)
                } else {
                    LedgerUi.Error(e.message ?: "transaction failed")
                }
            } finally {
                sendJob = null
                sendInFlight.set(false)
            }
        }
    }

    private suspend fun broadcastExactTransaction(
        pending: PendingSignedBroadcast,
        definitiveRpcErrorsCanClear: Boolean,
    ): String {
        signatureResultIfKnown(pending.signature)?.let { return it }
        if (expirePendingBroadcastIfProven(pending)) {
            throw IOException("The signed transaction expired before reaching Solana. Ask again to create a fresh transfer.")
        }
        var ambiguousAttempt = !definitiveRpcErrorsCanClear
        repeat(2) { attempt ->
            try {
                val returned = rpc.sendTransaction(pending.transaction)
                if (returned != pending.signature) {
                    throw IOException(
                        "RPC returned a different transaction signature; expected ${pending.signature}",
                    )
                }
                return returned
            } catch (e: SolanaRpc.RpcResponseException) {
                signatureResultIfKnown(pending.signature)?.let { return it }
                if (expirePendingBroadcastIfProven(pending)) {
                    throw IOException(
                        "The signed transaction expired before reaching Solana. Ask again to create a fresh transfer.",
                        e,
                    )
                }
                if (!ambiguousAttempt && definitiveRpcErrorsCanClear) {
                    clearPendingBroadcast()
                    throw e
                }
                throw IOException(
                    "Transaction status is uncertain. Check or rebroadcast the same signature; do not sign again. ${pending.signature}",
                    e,
                )
            } catch (e: IOException) {
                ambiguousAttempt = true
                signatureResultIfKnown(pending.signature)?.let { return it }
                if (expirePendingBroadcastIfProven(pending)) {
                    throw IOException(
                        "The signed transaction expired before reaching Solana. Ask again to create a fresh transfer.",
                        e,
                    )
                }
                if (attempt == 0) {
                    delay(BROADCAST_STATUS_DELAY_MS)
                } else {
                    throw IOException(
                        "Transaction status is uncertain. Check or rebroadcast the same signature; do not sign again. ${pending.signature}",
                        e,
                    )
                }
            }
        }
        throw IOException("Transaction status is uncertain: ${pending.signature}")
    }

    /** Returns the signature when processed; throws and clears when the cluster proves failure. */
    private suspend fun signatureResultIfKnown(signature: String): String? {
        val status = runCatching { rpc.signatureStatus(signature) }.getOrNull() ?: return null
        if (status.error != null) {
            clearPendingBroadcast()
            throw IOException("Transaction failed on chain: ${status.error}")
        }
        return signature
    }

    /**
     * Safe abandonment requires two facts from the same RPC: the blockhash window is over and the
     * exact signature is still absent. Only then may a later @ledger request ask for a fresh sign.
     */
    private suspend fun expirePendingBroadcastIfProven(pending: PendingSignedBroadcast): Boolean {
        if (pending.lastValidBlockHeight == Long.MAX_VALUE) return false
        val currentHeight = runCatching { rpc.blockHeight() }.getOrNull() ?: return false
        if (currentHeight <= pending.lastValidBlockHeight) return false
        val lookup = runCatching { rpc.signatureStatus(pending.signature) }
        if (lookup.isFailure) return false
        // A non-null status means the exact transaction landed (or failed on-chain); normal status
        // handling remains authoritative and the signed bytes must not be abandoned here.
        if (lookup.getOrNull() != null) return false

        val recovery = prefs.pendingLedgerResponse()
        if (recovery != null && recovery.receipt == null) {
            val cancellation = LedgerTransferReceipt.cancelled(
                intentId = recovery.intentId,
                responseNonce = recovery.responseNonce,
                to = recovery.recipientAddress,
                lamports = recovery.lamports,
                from = recovery.sourceAddress,
                reason = "signed_transaction_expired_unsubmitted",
            ).toJson()
            if (!prefs.setPendingLedgerResponse(recovery.copy(receipt = cancellation))) {
                throw IOException("Couldn't durably save the expired transaction result")
            }
        }
        if (!clearPendingBroadcast()) {
            throw IOException("Couldn't durably retire the expired signed transaction")
        }
        return true
    }

    private fun completeBroadcast(signature: String) {
        val recovery = prefs.pendingLedgerResponse()
        if (recovery != null && recovery.receipt == null) {
            val receipt = LedgerTransferReceipt.submitted(
                intentId = recovery.intentId,
                responseNonce = recovery.responseNonce,
                from = recovery.sourceAddress,
                to = recovery.recipientAddress,
                lamports = recovery.lamports,
                signature = signature,
            ).toJson()
            if (!prefs.setPendingLedgerResponse(recovery.copy(receipt = receipt))) {
                throw IOException("Transfer submitted, but its recovery receipt could not be saved")
            }
        }
        if (!clearPendingBroadcast()) {
            throw IOException(
                "Transfer submitted and its receipt was saved, but signed-byte recovery " +
                    "could not be retired yet",
            )
        }
        _ui.value = LedgerUi.Done(signature)
    }

    private fun clearPendingBroadcast(): Boolean {
        if (!prefs.clearPendingLedgerBroadcast()) return false
        pendingBroadcast = null
        return true
    }

    /** Hide the flow and release GATT; the persisted device reconnects directly without scanning. */
    fun hide() {
        closeFlow()
    }

    /** Drop the live BLE session but keep the remembered device/default account association. */
    fun closeFlow() {
        flowGeneration.incrementAndGet()
        scanGeneration.incrementAndGet()
        sendJob?.cancel()
        accountJob?.cancel()
        balanceJob?.cancel()
        scanJob?.cancel()
        scanJob = null
        keepaliveJob?.cancel()
        runCatching { transport?.close() }
        transport = null
        app = null
        pubkey = null
        connectedDevice = null
        synchronized(accountLock) { accountRecords = emptyList() }
        _agentRequest.value = null
        _ui.value = LedgerUi.Idle
    }

    /**
     * Explicitly forget AgenC's Ledger association so another device can be selected. This does
     * not (and Android public APIs cannot reliably) remove the OS-level Bluetooth bond.
     */
    fun forgetLedger() {
        closeFlow()
        prefs.clearLedgerAssociation()
        discoveryCount = INITIAL_DISCOVERY_COUNT
        _ui.value = LedgerUi.Idle
    }

    companion object {
        private const val TAG = "Ledger"
        private const val SOLANA_APP_NAME = "Solana"
        private const val INITIAL_DISCOVERY_COUNT = 5
        private const val DISCOVERY_PAGE_SIZE = 5
        private const val KEEPALIVE_INTERVAL_MS = 8_000L
        private const val APP_SWITCH_DELAY_MS = 2_500L
        private const val BROADCAST_STATUS_DELAY_MS = 1_500L
        private fun formatLamports(lamports: Long): String =
            BigDecimal.valueOf(lamports, 9).stripTrailingZeros().toPlainString()

    }
}
