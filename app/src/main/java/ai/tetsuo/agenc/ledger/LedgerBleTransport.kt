package ai.tetsuo.agenc.ledger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * BLE transport for Ledger hardware wallets (Nano X / Stax / Flex) — the Android port of the
 * desktop kit's DMK/BLE path (`AGENC_LEDGER_DMK_BLE=1`).
 *
 * Speaks Ledger's BLE framing protocol on top of a raw GATT connection:
 *  - Each device family advertises one GATT service (`13d63400-2c97-YY04-0000-4c6564676572`,
 *    `YY` = 00 Nano X / 60 Stax / 30 Flex) with three characteristics that share the service's
 *    UUID prefix: `…-0001-…` write-with-response, `…-0002-…` notify, `…-0003-…`
 *    write-no-response (preferred for sends when present — fewer round trips).
 *  - Connect sequence: `connectGatt` → `requestMtu(517)` → `discoverServices` → enable
 *    notifications (CCCD `0x2902`) → send the MTU-probe frame `[0x08,00,00,00,00]`; the device
 *    notifies back `[0x08,00,00,00,01, usableMtu]` and byte 5 is the per-frame byte budget used
 *    for APDU framing from then on.
 *  - APDU framing (both directions), tag `0x05`: first frame
 *    `[0x05, 0x00, 0x00, lenHi, lenLo, payload…]`, continuations `[0x05, seqHi, seqLo, payload…]`
 *    with seq starting at 1. Responses reassemble identically — the first frame carries the total
 *    length, and the reassembled buffer is trimmed to it.
 *
 * Design intent, matching the rest of the codebase: no ViewModel — this class is a plain
 * StateFlow-driven object meant to be held by a singleton in `data/` and observed by stateless
 * composables ([state] drives the connect UI, [found] drives the device picker). All Android GATT
 * callbacks (which arrive on binder threads) are bridged to coroutines with [CompletableDeferred]
 * per connect phase and a [Channel] for inbound notification frames; GATT writes are strictly
 * serialized (Android drops concurrent writes silently) by awaiting `onCharacteristicWrite`
 * before the next frame goes out. [exchange] is single-flight via a [Mutex] and waits up to 60s —
 * signing APDUs block until the human approves on the device.
 *
 * Permissions (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`) are requested by the UI layer before any
 * call here; this class only guards against [SecurityException] and surfaces it as a failure
 * instead of crashing.
 */
@SuppressLint("MissingPermission") // Permissions are requested by the UI layer before use.
class LedgerBleTransport(private val context: Context) {

    /** One scan hit — enough for a picker row ([name]) and for [connect] ([device]). */
    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val device: BluetoothDevice,
    )

    /** Transport lifecycle, exposed as a [StateFlow] so screens can render it statelessly. */
    sealed interface State {
        data object Disconnected : State
        data object Scanning : State
        data object Connecting : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _found = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val found: StateFlow<List<DiscoveredDevice>> = _found.asStateFlow()

    // ---- Connection state (volatile: GATT callbacks arrive on binder threads) ----------------

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    /** Per-frame byte budget reported by the device's MTU-probe response. */
    @Volatile private var usableMtu: Int = LEGACY_FRAME_SIZE
    @Volatile private var scanning = false

    // One CompletableDeferred per connect phase / in-flight write; completed by the callback.
    @Volatile private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var mtuChanged: CompletableDeferred<Unit>? = null
    @Volatile private var servicesDiscovered: CompletableDeferred<Unit>? = null
    @Volatile private var descriptorWritten: CompletableDeferred<Unit>? = null
    @Volatile private var writeDone: CompletableDeferred<Unit>? = null
    /** Completed when the system pairing/bond finishes (the Flex requires bonding). */
    @Volatile private var bonded: CompletableDeferred<Unit>? = null
    @Volatile private var bondReceiver: BroadcastReceiver? = null
    /** Inbound notification frames; recreated per connection, closed on disconnect. */
    @Volatile private var inbound: Channel<ByteArray>? = null

    /** Single-flight gate for [exchange] — one APDU on the wire at a time. */
    private val exchangeMutex = Mutex()

    // ---- Scanning -----------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull() // needs BLUETOOTH_CONNECT
                ?: "Ledger"
            _found.update { list ->
                if (list.any { it.address == device.address }) list
                else list + DiscoveredDevice(name = name, address = device.address, device = device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = State.Failed("BLE scan failed (code $errorCode)")
        }
    }

    /**
     * Starts scanning for Ledger devices, filtered to the three family service UUIDs so only
     * Ledgers show up. Results accumulate in [found] (deduped by MAC). No-op while already
     * scanning or connected.
     */
    fun startScan() {
        if (scanning || _state.value is State.Connecting || _state.value is State.Ready) return
        val scanner = try {
            adapterScannerOrNull()
        } catch (e: SecurityException) {
            _state.value = State.Failed("Bluetooth permission missing: ${e.message}")
            return
        }
        if (scanner == null) {
            _state.value = State.Failed("Bluetooth is unavailable or turned off")
            return
        }
        val filters = SERVICE_UUIDS.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _found.value = emptyList()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            _state.value = State.Scanning
        } catch (e: SecurityException) {
            _state.value = State.Failed("Bluetooth scan permission missing: ${e.message}")
        }
    }

    /** Stops an in-progress scan. [found] keeps its results so a picker can stay populated. */
    fun stopScan() {
        stopScanInternal()
        if (_state.value is State.Scanning) _state.value = State.Disconnected
    }

    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        try {
            adapterScannerOrNull()?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-scan; the OS has already stopped it.
        }
    }

    private fun adapterScannerOrNull() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    /**
     * Reconstruct a previously selected Ledger from its public Bluetooth address, without running
     * another BLE scan. Android keeps the system bond; [BluetoothDevice] is only a lightweight
     * remote-device handle and [connect] still performs the full authenticated GATT handshake.
     *
     * Returns null when Bluetooth is unavailable, the address is malformed, or CONNECT permission
     * was revoked. The caller can then fall back to the normal scan/picker flow.
     */
    fun rememberedDevice(address: String, rememberedName: String? = null): DiscoveredDevice? {
        val cleanAddress = address.trim()
        if (cleanAddress.isEmpty()) return null
        return try {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.takeIf { it.isEnabled } ?: return null
            val device = adapter.bondedDevices.firstOrNull {
                it.address.equals(cleanAddress, ignoreCase = true)
            } ?: adapter.getRemoteDevice(cleanAddress)
            val name = rememberedName?.takeIf { it.isNotBlank() }
                ?: runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Ledger"
            DiscoveredDevice(name = name, address = device.address, device = device)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    // ---- GATT callback ------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return // stale callback from a connection we already closed
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    connected?.complete(Unit)
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val error = IOException("Ledger disconnected (status $status)")
                    failPending(error)
                    _state.value = State.Failed("Disconnected (status $status)")
                }
                else ->
                    connected?.completeExceptionally(IOException("GATT connect failed (status $status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            // Non-fatal either way: the device-side MTU probe is the source of truth for framing.
            mtuChanged?.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered?.complete(Unit)
            else servicesDiscovered?.completeExceptionally(
                IOException("Service discovery failed (status $status)"),
            )
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) descriptorWritten?.complete(Unit)
            else descriptorWritten?.completeExceptionally(
                IOException("CCCD write failed (status $status)"),
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) writeDone?.complete(Unit)
            else writeDone?.completeExceptionally(IOException("GATT write failed (status $status)"))
        }

        // API 33+ delivers the value directly …
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            if (c.uuid == notifyChar?.uuid) inbound?.trySend(value.copyOf())
        }

        // … and pre-33 stashes it on the characteristic. Android 13 invokes BOTH overloads, so
        // gate this one to old platforms to avoid duplicating every frame.
        @Deprecated("Pre-API-33 delivery path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (g !== gatt) return
            @Suppress("DEPRECATION") val value = c.value ?: return
            if (c.uuid == notifyChar?.uuid) inbound?.trySend(value.copyOf())
        }
    }

    /** Fails every pending phase await and the inbound channel so suspended callers wake up. */
    private fun failPending(error: IOException) {
        connected?.completeExceptionally(error)
        mtuChanged?.completeExceptionally(error)
        servicesDiscovered?.completeExceptionally(error)
        descriptorWritten?.completeExceptionally(error)
        writeDone?.completeExceptionally(error)
        bonded?.completeExceptionally(error)
        inbound?.close(error)
    }

    /**
     * The Flex requires BLE bonding, so Android shows a system pairing popup on first connect. We
     * MUST wait for BOND_BONDED before subscribing to notifications / probing the MTU — those are
     * encrypted operations that otherwise race the pairing and time out (the exact symptom of
     * "Android pairs but the app times out"). No-op if the device is already bonded.
     */
    private suspend fun awaitBondingIfNeeded(device: BluetoothDevice) {
        if (bondStateOf(device) == BluetoothDevice.BOND_BONDED) return
        val done = CompletableDeferred<Unit>()
        bonded = done
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = bondDeviceOf(intent) ?: return
                if (d.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> bonded?.complete(Unit)
                    BluetoothDevice.BOND_NONE ->
                        if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            == BluetoothDevice.BOND_BONDING
                        ) {
                            bonded?.completeExceptionally(IOException("pairing was rejected on the phone"))
                        }
                }
            }
        }
        bondReceiver = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        }
        _state.value = State.Connecting
        // Kick off bonding if the stack hasn't started it yet (else the encrypted access triggers it).
        if (bondStateOf(device) == BluetoothDevice.BOND_NONE) {
            try { device.createBond() } catch (_: SecurityException) { /* UI layer holds the perms */ }
        }
        try {
            awaitPhase("device pairing", BOND_TIMEOUT_MS, done)
        } finally {
            unregisterBondReceiver()
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        bondReceiver = null
        bonded = null
    }

    private fun bondStateOf(device: BluetoothDevice): Int =
        try { device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }

    @Suppress("DEPRECATION")
    private fun bondDeviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    // ---- Connect ------------------------------------------------------------------------------

    /**
     * Connects to a scanned device and brings the Ledger BLE session to [State.Ready]:
     * GATT connect → MTU 517 → service discovery → notifications on → MTU-probe handshake.
     *
     * @throws IOException on any failure (state is set to [State.Failed] with the same reason).
     */
    suspend fun connect(device: DiscoveredDevice) {
        stopScanInternal()
        closeGattOnly() // drop any previous connection first
        _state.value = State.Connecting

        val connectedD = CompletableDeferred<Unit>()
        val mtuD = CompletableDeferred<Unit>()
        val servicesD = CompletableDeferred<Unit>()
        val descriptorD = CompletableDeferred<Unit>()
        connected = connectedD
        mtuChanged = mtuD
        servicesDiscovered = servicesD
        descriptorWritten = descriptorD
        inbound = Channel(Channel.UNLIMITED)

        try {
            // 1. GATT connect (direct, LE transport — autoConnect stalls for minutes on some OEMs).
            gatt = device.device.connectGatt(
                context, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            ) ?: throw IOException("connectGatt returned null")
            awaitPhase("GATT connection", CONNECT_TIMEOUT_MS, connectedD)

            // 1b. Bonding: the Flex pairs on first connect (system popup). Wait for it before any
            //     encrypted operation (notification subscribe / MTU probe), or they time out.
            awaitBondingIfNeeded(device.device)

            // 2. Request the max ATT MTU. The result is advisory — the device's own MTU probe
            //    (step 5) decides the frame size — so a refusal here is not fatal.
            val g = gatt ?: throw IOException("Connection lost")
            if (g.requestMtu(REQUESTED_MTU)) {
                try {
                    awaitPhase("MTU negotiation", PHASE_TIMEOUT_MS, mtuD)
                } catch (_: IOException) {
                    // Some stacks never deliver onMtuChanged; the probe will still tell us.
                }
            }

            // 3. Discover services and locate the family service + its three characteristics.
            // Clear Android's cached attribute table first — a stale cache is the usual reason the
            // notify characteristic comes back with an empty descriptor list (no CCCD), which then
            // breaks notification subscription and hangs the MTU probe.
            refreshGattCache(g)
            if (!g.discoverServices()) throw IOException("discoverServices() rejected")
            awaitPhase("service discovery", DISCOVERY_TIMEOUT_MS, servicesD)

            // Diagnostic: dump the real GATT table so characteristic/descriptor layout is not a guess.
            for (svc in g.services) {
                android.util.Log.i("LedgerBle", "svc ${svc.uuid}")
                for (ch in svc.characteristics) {
                    android.util.Log.i(
                        "LedgerBle",
                        "  char ${ch.uuid} props=0x${ch.properties.toString(16)} descs=${ch.descriptors.map { it.uuid }}",
                    )
                }
            }

            val service = SERVICE_UUIDS.firstNotNullOfOrNull { g.getService(it) }
                ?: throw IOException("No Ledger GATT service found (is this a Ledger device?)")
            val notify = service.getCharacteristic(charUuid(service.uuid, NOTIFY_GROUP))
                ?: throw IOException("Ledger notify characteristic missing")
            val writeNoRsp = service.getCharacteristic(charUuid(service.uuid, WRITE_CMD_GROUP))
            val writeWithRsp = service.getCharacteristic(charUuid(service.uuid, WRITE_GROUP))
            val write = writeNoRsp ?: writeWithRsp
                ?: throw IOException("Ledger write characteristic missing")
            notifyChar = notify
            writeChar = write
            writeType = if (writeNoRsp != null) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            // 4. Enable notifications: client-side subscription + the CCCD descriptor write.
            if (!g.setCharacteristicNotification(notify, true)) {
                throw IOException("setCharacteristicNotification failed")
            }
            // The Flex's notify characteristic doesn't always expose the 0x2902 CCCD by that exact
            // UUID in Android's discovered descriptor list (OEM GATT-cache quirk). Fall back to the
            // characteristic's sole descriptor; if there's genuinely none, the client-side
            // setCharacteristicNotification above is enough on many stacks, so proceed and let the
            // MTU probe be the real "did notifications turn on?" gate rather than hard-failing here.
            val cccd = notify.getDescriptor(CCCD_UUID) ?: notify.descriptors.firstOrNull()
            if (cccd != null) {
                writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                awaitPhase("notification enable", PHASE_TIMEOUT_MS, descriptorD)
            } else {
                // The verified layout always has a CCCD on the notify char; if it's genuinely absent
                // the client-side setCharacteristicNotification above is the fallback.
                android.util.Log.w("LedgerBle", "notify characteristic has no CCCD; relying on setCharacteristicNotification")
            }

            // 5. MTU-probe handshake: [0x08,00,00,00,00] out, [0x08,00,00,00,01, usableMtu] back.
            writeFrame(byteArrayOf(TAG_MTU, 0x00, 0x00, 0x00, 0x00))
            val probe = withTimeoutOrIo("MTU probe response", PHASE_TIMEOUT_MS) {
                var frame: ByteArray
                do {
                    frame = inbound?.receive() ?: throw IOException("Connection lost")
                } while (frame.isEmpty() || frame[0] != TAG_MTU)
                frame
            }
            if (probe.size < 6) throw IOException("Malformed MTU probe response (${probe.size} bytes)")
            val usable = probe[5].toInt() and 0xFF
            if (usable < MIN_FRAME_SIZE) throw IOException("Device reported unusable frame size $usable")
            usableMtu = usable

            _state.value = State.Ready
        } catch (e: CancellationException) {
            closeGattOnly()
            _state.value = State.Disconnected
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "Connection failed"
            closeGattOnly()
            _state.value = State.Failed(reason)
            throw if (e is IOException) e else IOException(reason, e)
        }
    }

    // ---- Exchange -----------------------------------------------------------------------------

    /**
     * Sends one APDU and suspends until the full response (including the trailing status word)
     * has been reassembled. Single-flight: concurrent callers queue on a [Mutex]. Waits up to
     * [EXCHANGE_TIMEOUT_MS] — a sign APDU legitimately blocks until the human approves (or
     * rejects) on the Ledger's screen.
     *
     * @throws IOException on transport errors, disconnects, or timeout.
     */
    suspend fun exchange(apdu: ByteArray): ByteArray = exchangeMutex.withLock {
        if (_state.value !is State.Ready) throw IOException("Transport not ready (${_state.value})")
        if (apdu.size > 0xFFFF) throw IOException("APDU too large (${apdu.size} bytes)")
        val channel = inbound ?: throw IOException("Connection lost")

        // Drop any stale frames from an earlier, timed-out exchange.
        while (channel.tryReceive().isSuccess) Unit

        try {
            for (frame in frameApdu(apdu, usableMtu)) writeFrame(frame)
            withTimeoutOrIo("APDU response", EXCHANGE_TIMEOUT_MS) { readResponse(channel) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "APDU exchange failed", e)
        }
    }

    /** Splits [apdu] into wire frames, each at most [frameSize] bytes including its header. */
    private fun frameApdu(apdu: ByteArray, frameSize: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        var seq = 0
        do {
            val headerLen = if (seq == 0) FIRST_HEADER_LEN else CONT_HEADER_LEN
            val take = minOf(apdu.size - offset, frameSize - headerLen)
            val frame = ByteArray(headerLen + take)
            frame[0] = TAG_APDU
            if (seq == 0) {
                // [0x05, 0x00, 0x00, lenHi, lenLo, payload…]
                frame[3] = (apdu.size ushr 8).toByte()
                frame[4] = apdu.size.toByte()
            } else {
                // [0x05, seqHi, seqLo, payload…], seq starts at 1
                frame[1] = (seq ushr 8).toByte()
                frame[2] = seq.toByte()
            }
            apdu.copyInto(frame, headerLen, offset, offset + take)
            offset += take
            seq++
            frames += frame
        } while (offset < apdu.size)
        return frames
    }

    /** Reassembles one tag-0x05 response: first frame carries the total length. */
    private suspend fun readResponse(channel: Channel<ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        var expected = -1
        var seq = 0
        while (expected < 0 || buffer.size() < expected) {
            val frame = channel.receive()
            if (frame.isEmpty() || frame[0] == TAG_MTU) continue // stray keep-alive / probe echo
            if (frame[0] != TAG_APDU) {
                throw IOException("Unexpected frame tag 0x%02x".format(frame[0]))
            }
            if (seq == 0) {
                if (frame.size < FIRST_HEADER_LEN) throw IOException("Short first response frame")
                expected = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
                buffer.write(frame, FIRST_HEADER_LEN, frame.size - FIRST_HEADER_LEN)
            } else {
                if (frame.size < CONT_HEADER_LEN) throw IOException("Short continuation frame")
                val gotSeq = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
                if (gotSeq != seq) throw IOException("Out-of-order frame: expected $seq, got $gotSeq")
                buffer.write(frame, CONT_HEADER_LEN, frame.size - CONT_HEADER_LEN)
            }
            seq++
        }
        val bytes = buffer.toByteArray()
        return if (bytes.size > expected) bytes.copyOf(expected) else bytes
    }

    // ---- Low-level GATT writes ----------------------------------------------------------------

    /**
     * Writes one wire frame and suspends until the stack confirms via `onCharacteristicWrite`.
     * Android silently drops overlapping GATT writes, so every frame is strictly serialized.
     */
    private suspend fun writeFrame(frame: ByteArray) {
        val g = gatt ?: throw IOException("Connection lost")
        val c = writeChar ?: throw IOException("Connection lost")
        val done = CompletableDeferred<Unit>()
        writeDone = done
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeCharacteristic(c, frame, writeType)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    throw IOException("writeCharacteristic rejected (code $status)")
                }
            } else {
                c.writeType = writeType
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(c)) throw IOException("writeCharacteristic rejected")
            }
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission missing: ${e.message}", e)
        }
        awaitPhase("GATT write", WRITE_TIMEOUT_MS, done)
    }

    /**
     * Force a re-read of the peer's attribute table by calling the hidden `BluetoothGatt.refresh()`
     * via reflection. Not public API, but the only reliable way to clear a stale GATT cache that
     * drops descriptors (the missing-CCCD symptom). Best-effort: a failure is non-fatal.
     */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching {
            val refresh = g.javaClass.getMethod("refresh")
            refresh.invoke(g) as? Boolean
        }.onFailure { android.util.Log.d("LedgerBle", "gatt.refresh() unavailable: ${it.message}") }
    }

    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeDescriptor(descriptor, value)
                if (status != BluetoothStatusCodes.SUCCESS) {
                    throw IOException("writeDescriptor rejected (code $status)")
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                if (!g.writeDescriptor(descriptor)) throw IOException("writeDescriptor rejected")
            }
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission missing: ${e.message}", e)
        }
    }

    // ---- Teardown -----------------------------------------------------------------------------

    /**
     * Tears everything down: stops scanning, drops the GATT connection, wakes any suspended
     * caller with an error, and resets [state] to [State.Disconnected]. Safe to call repeatedly.
     */
    fun close() {
        stopScanInternal()
        closeGattOnly()
        _state.value = State.Disconnected
    }

    /** Releases the GATT connection without touching [state] (callers set it themselves). */
    private fun closeGattOnly() {
        unregisterBondReceiver()
        val g = gatt
        gatt = null // detach first so late callbacks from this connection are ignored
        writeChar = null
        notifyChar = null
        usableMtu = LEGACY_FRAME_SIZE
        failPending(IOException("Transport closed"))
        connected = null
        mtuChanged = null
        servicesDiscovered = null
        descriptorWritten = null
        writeDone = null
        inbound = null
        if (g != null) {
            try {
                g.disconnect()
                g.close()
            } catch (_: SecurityException) {
                // Permission revoked; the connection dies with the process anyway.
            }
        }
    }

    // ---- Coroutine plumbing ---------------------------------------------------------------------

    private suspend fun <T> awaitPhase(
        what: String,
        timeoutMs: Long,
        deferred: CompletableDeferred<T>,
    ): T = withTimeoutOrIo(what, timeoutMs) { deferred.await() }

    private suspend fun <T> withTimeoutOrIo(
        what: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw IOException("Timed out waiting for $what (${timeoutMs}ms)")
    }

    private companion object {
        /** Ledger BLE GATT service UUIDs, one per device family. */
        val SERVICE_UUIDS = listOf(
            UUID.fromString("13d63400-2c97-0004-0000-4c6564676572"), // Nano X
            UUID.fromString("13d63400-2c97-6004-0000-4c6564676572"), // Stax
            UUID.fromString("13d63400-2c97-3004-0000-4c6564676572"), // Flex
        )

        /** Client Characteristic Configuration Descriptor — enables notifications. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Characteristic UUIDs = the family service UUID with the 4th group swapped in.
        // Verified against a real Flex GATT dump (2026-07-03):
        //   ...-0001-... props 0x10 NOTIFY (with CCCD)   ...-0002-... props 0x08 WRITE
        //   ...-0003-... props 0x04 WRITE_NO_RESPONSE
        const val NOTIFY_GROUP = 0x0001L // notify (carries the CCCD)
        const val WRITE_GROUP = 0x0002L // write-with-response
        const val WRITE_CMD_GROUP = 0x0003L // write-no-response (preferred when present)

        /**
         * The 4th UUID group is the top 16 bits of the least-significant half; the service's own
         * group there is 0000, so OR-ing the group in yields the characteristic UUID.
         */
        fun charUuid(service: UUID, group: Long): UUID =
            UUID(service.mostSignificantBits, service.leastSignificantBits or (group shl 48))

        const val TAG_APDU: Byte = 0x05
        const val TAG_MTU: Byte = 0x08
        const val FIRST_HEADER_LEN = 5 // tag + seq(2, =0) + length(2)
        const val CONT_HEADER_LEN = 3 // tag + seq(2)

        const val REQUESTED_MTU = 517 // max ATT MTU on Android
        const val LEGACY_FRAME_SIZE = 20 // safe default until the device reports its budget
        const val MIN_FRAME_SIZE = 8 // sanity floor for the device-reported frame size

        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val PHASE_TIMEOUT_MS = 5_000L
        const val BOND_TIMEOUT_MS = 60_000L // the human must accept the system pairing popup
        const val WRITE_TIMEOUT_MS = 5_000L
        /** Sign APDUs block until the human approves on the device — give them a full minute. */
        const val EXCHANGE_TIMEOUT_MS = 60_000L
    }
}
