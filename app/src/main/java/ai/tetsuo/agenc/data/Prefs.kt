package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.LedgerTransferReceipt
import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PendingLedgerActionRecord(
    val sessionId: String,
    val requestId: String,
    val questionId: String,
    val intentId: String,
    val responseNonce: String,
    val to: String,
    val lamports: Long,
    val note: String? = null,
    val expiresAtEpochMillis: Long,
    val hostBinding: String,
)

@Serializable
data class LedgerIntentTombstone(
    val intentId: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class LedgerInboxState(
    val actions: List<PendingLedgerActionRecord> = emptyList(),
    val tombstones: List<LedgerIntentTombstone> = emptyList(),
)

data class PendingLedgerBroadcastRecord(
    val signature: String,
    val transactionBase64: String,
    val lastValidBlockHeight: Long,
)

/** Public recovery record for a phone-reviewed Ledger request; contains no signing secrets. */
@Serializable
data class PendingLedgerResponseRecord(
    val sessionId: String,
    val requestId: String,
    val questionId: String,
    val hostBinding: String,
    val intentId: String,
    val responseNonce: String,
    val expiresAtEpochMillis: Long,
    val sourceAddress: String,
    val recipientAddress: String,
    val lamports: Long,
    val receipt: String?,
    /**
     * Durable one-shot fence set immediately before Android sends SIGN_MESSAGE to the Ledger.
     * Once true it is monotonic for this intent: reconnect/process restore may recover signed
     * bytes, but must never ask the device to sign a second transaction for the same request.
     */
    val hardwareReviewStarted: Boolean = false,
)

/** Durable receipts awaiting an authoritative, digest-aware Core acknowledgement. */
@Serializable
data class LedgerReceiptOutboxState(
    val records: List<PendingLedgerResponseRecord> = emptyList(),
)

internal data class LedgerReceiptArchivePlan(
    val accepted: Boolean,
    val state: LedgerReceiptOutboxState,
)

private fun sameLedgerReceiptIdentity(
    first: PendingLedgerResponseRecord,
    second: PendingLedgerResponseRecord,
): Boolean =
    first.hostBinding == second.hostBinding &&
        first.sessionId == second.sessionId &&
        first.requestId == second.requestId &&
        first.intentId == second.intentId &&
        first.responseNonce == second.responseNonce

internal fun hasCorrelatedLedgerReceipt(record: PendingLedgerResponseRecord): Boolean {
    val parsed = record.receipt?.let(LedgerTransferReceipt::parse) ?: return false
    return parsed.intentId == record.intentId &&
        parsed.responseNonce == record.responseNonce &&
        parsed.to == record.recipientAddress &&
        parsed.lamports == record.lamports.toString() &&
        parsed.from == record.sourceAddress.takeIf { it.isNotBlank() }
}

/**
 * Append-only archive; exact replay is idempotent and a key conflict fails closed. Production is
 * intentionally uncapped: each entry is small public metadata, and dropping an unacknowledged
 * financial receipt is worse than gradual storage growth. Successful reconciliation compacts it.
 * [maxRecords] exists only for bounded-policy unit tests.
 */
internal fun planLedgerReceiptArchive(
    current: LedgerReceiptOutboxState,
    record: PendingLedgerResponseRecord,
    maxRecords: Int? = null,
): LedgerReceiptArchivePlan {
    if (!hasCorrelatedLedgerReceipt(record)) return LedgerReceiptArchivePlan(false, current)
    val existing = current.records.firstOrNull { sameLedgerReceiptIdentity(it, record) }
    if (existing != null) {
        return LedgerReceiptArchivePlan(existing == record, current)
    }
    if (maxRecords != null && current.records.size >= maxRecords) {
        return LedgerReceiptArchivePlan(false, current)
    }
    return LedgerReceiptArchivePlan(true, current.copy(records = current.records + record))
}

internal enum class LedgerHardwareReviewClaimResult {
    CLAIMED,
    ALREADY_STARTED,
    MISMATCH,
    PERSISTENCE_FAILED,
}

internal data class LedgerHardwareReviewClaimPlan(
    val result: LedgerHardwareReviewClaimResult,
    val updatedRecord: PendingLedgerResponseRecord? = null,
)

/** Pure transition used by [Prefs.claimPendingLedgerHardwareReview] and JVM contract tests. */
internal fun planLedgerHardwareReviewClaim(
    current: PendingLedgerResponseRecord?,
    intentId: String,
    responseNonce: String,
    expiresAtEpochMillis: Long,
    sourceAddress: String,
    recipientAddress: String,
    lamports: Long,
): LedgerHardwareReviewClaimPlan {
    val matches = current != null && current.receipt == null &&
        current.intentId == intentId &&
        current.responseNonce == responseNonce &&
        current.expiresAtEpochMillis == expiresAtEpochMillis &&
        current.sourceAddress == sourceAddress &&
        current.recipientAddress == recipientAddress &&
        current.lamports == lamports
    if (!matches) {
        return LedgerHardwareReviewClaimPlan(LedgerHardwareReviewClaimResult.MISMATCH)
    }
    if (current.hardwareReviewStarted) {
        return LedgerHardwareReviewClaimPlan(LedgerHardwareReviewClaimResult.ALREADY_STARTED)
    }
    return LedgerHardwareReviewClaimPlan(
        result = LedgerHardwareReviewClaimResult.CLAIMED,
        updatedRecord = current.copy(hardwareReviewStarted = true),
    )
}

/** Never let a stale UI snapshot roll a same-intent hardware-review fence back to false. */
internal fun preserveLedgerHardwareReviewFence(
    current: PendingLedgerResponseRecord?,
    incoming: PendingLedgerResponseRecord,
): PendingLedgerResponseRecord =
    if (current?.intentId == incoming.intentId && current.hardwareReviewStarted) {
        incoming.copy(hardwareReviewStarted = true)
    } else {
        incoming
    }

/**
 * A terminal same-intent receipt is append-only. Stale Compose callbacks may replay the exact
 * record, but cannot remove it, replace submitted with cancelled, or alter its locked fields.
 */
internal fun mergePendingLedgerResponse(
    current: PendingLedgerResponseRecord?,
    incoming: PendingLedgerResponseRecord,
): PendingLedgerResponseRecord? {
    val durable = preserveLedgerHardwareReviewFence(current, incoming)
    if (current?.intentId == durable.intentId && current.receipt != null) {
        return current.takeIf { durable == current }
    }
    return durable
}

/**
 * Plain (unencrypted) key-value store mirroring iOS `UserDefaults`.
 *
 * Holds non-secret persisted state: cached identity, the local↔daemon session-id map,
 * the local session registry, per-session transcript blobs, the active relay URL, the
 * current pairing, and the notification settings (the Android port of iOS `AppSettings`).
 * Secrets (bearer + relay ticket) live in [SecureStore] instead.
 *
 * Exposes JSON-string helpers so the higher layers can persist serialized blobs the same way
 * the Swift code persisted `Data` blobs into UserDefaults.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    // MARK: - Ledger association (public metadata only; private keys never leave the device)

    /** Bluetooth address of the Ledger the user explicitly associated with AgenC. */
    var ledgerDeviceAddress: String?
        get() = prefs.getString(KEY_LEDGER_DEVICE_ADDRESS, null)
        set(v) = putNullableString(KEY_LEDGER_DEVICE_ADDRESS, v)

    /** Last advertised device name, used only to label a direct reconnect. */
    var ledgerDeviceName: String?
        get() = prefs.getString(KEY_LEDGER_DEVICE_NAME, null)
        set(v) = putNullableString(KEY_LEDGER_DEVICE_NAME, v)

    /** Stable, non-secret derivation-path key for the user's default Ledger account. */
    var ledgerAccountPathKey: String?
        get() = prefs.getString(KEY_LEDGER_ACCOUNT_PATH, null)
        set(v) = putNullableString(KEY_LEDGER_ACCOUNT_PATH, v)

    /** Public Solana address derived for [ledgerAccountPathKey]. */
    var ledgerAccountAddress: String?
        get() = prefs.getString(KEY_LEDGER_ACCOUNT_ADDRESS, null)
        set(v) = putNullableString(KEY_LEDGER_ACCOUNT_ADDRESS, v)

    /** Number of indexes requested in each bounded common-account discovery family. */
    var ledgerDiscoveryCount: Int
        get() = prefs.getInt(KEY_LEDGER_DISCOVERY_COUNT, DEFAULT_LEDGER_DISCOVERY_COUNT)
        set(v) = prefs.edit().putInt(KEY_LEDGER_DISCOVERY_COUNT, v).apply()

    /** Atomically replace the public device association used for direct reconnect. */
    fun setLedgerDeviceAssociation(address: String, name: String, discoveryCount: Int) {
        prefs.edit()
            .putString(KEY_LEDGER_DEVICE_ADDRESS, address)
            .putString(KEY_LEDGER_DEVICE_NAME, name)
            .putInt(KEY_LEDGER_DISCOVERY_COUNT, discoveryCount)
            .apply()
    }

    /** Atomically pin path + public key so a process cannot observe a mixed selection snapshot. */
    fun setLedgerAccountSelection(pathKey: String, address: String): Boolean =
        prefs.edit()
            .putString(KEY_LEDGER_ACCOUNT_PATH, pathKey)
            .putString(KEY_LEDGER_ACCOUNT_ADDRESS, address)
            // A typed request may auto-forward immediately after selection, so the public source
            // binding must be durable before the hardware-review one-shot can be claimed.
            .commit()

    /** Persist one already-signed transaction so an ambiguous RPC can only rebroadcast same bytes. */
    fun setPendingLedgerBroadcast(
        signature: String,
        transactionBase64: String,
        lastValidBlockHeight: Long,
    ): Boolean =
        prefs.edit()
            .putString(KEY_LEDGER_PENDING_SIGNATURE, signature)
            .putString(KEY_LEDGER_PENDING_TRANSACTION, transactionBase64)
            .putLong(KEY_LEDGER_PENDING_LAST_VALID_HEIGHT, lastValidBlockHeight)
            // Irreversible boundary: bytes must be on disk before the RPC can receive them.
            .commit()

    fun pendingLedgerBroadcast(): PendingLedgerBroadcastRecord? {
        val signature = prefs.getString(KEY_LEDGER_PENDING_SIGNATURE, null) ?: return null
        val transaction = prefs.getString(KEY_LEDGER_PENDING_TRANSACTION, null) ?: return null
        return PendingLedgerBroadcastRecord(
            signature = signature,
            transactionBase64 = transaction,
            lastValidBlockHeight = prefs.getLong(
                KEY_LEDGER_PENDING_LAST_VALID_HEIGHT,
                Long.MAX_VALUE,
            ),
        )
    }

    fun clearPendingLedgerBroadcast(): Boolean =
        prefs.edit()
            .remove(KEY_LEDGER_PENDING_SIGNATURE)
            .remove(KEY_LEDGER_PENDING_TRANSACTION)
            .remove(KEY_LEDGER_PENDING_LAST_VALID_HEIGHT)
            .commit()

    /** Persist correlation before signing so process death cannot orphan a submitted transfer. */
    @Synchronized
    fun setPendingLedgerResponse(record: PendingLedgerResponseRecord): Boolean {
        val durable = mergePendingLedgerResponse(pendingLedgerResponse(), record) ?: return false
        return prefs.edit()
            .putString(KEY_LEDGER_RESPONSE_SESSION, durable.sessionId)
            .putString(KEY_LEDGER_RESPONSE_REQUEST, durable.requestId)
            .putString(KEY_LEDGER_RESPONSE_QUESTION, durable.questionId)
            .putString(KEY_LEDGER_RESPONSE_HOST, durable.hostBinding)
            .putString(KEY_LEDGER_RESPONSE_INTENT, durable.intentId)
            .putString(KEY_LEDGER_RESPONSE_NONCE, durable.responseNonce)
            .putLong(KEY_LEDGER_RESPONSE_EXPIRES_AT, durable.expiresAtEpochMillis)
            .putString(KEY_LEDGER_RESPONSE_SOURCE, durable.sourceAddress)
            .putString(KEY_LEDGER_RESPONSE_RECIPIENT, durable.recipientAddress)
            .putLong(KEY_LEDGER_RESPONSE_LAMPORTS, durable.lamports)
            .putBoolean(KEY_LEDGER_RESPONSE_HARDWARE_REVIEW_STARTED, durable.hardwareReviewStarted)
            .let { edit ->
                if (durable.receipt == null) edit.remove(KEY_LEDGER_RESPONSE_RECEIPT)
                else edit.putString(KEY_LEDGER_RESPONSE_RECEIPT, durable.receipt)
            }
            // Correlation and result receipt must survive process/power loss before signing/ACK.
            .commit()
    }

    fun pendingLedgerResponse(): PendingLedgerResponseRecord? {
        val sessionId = prefs.getString(KEY_LEDGER_RESPONSE_SESSION, null) ?: return null
        val requestId = prefs.getString(KEY_LEDGER_RESPONSE_REQUEST, null) ?: return null
        val questionId = prefs.getString(KEY_LEDGER_RESPONSE_QUESTION, null) ?: return null
        val hostBinding = prefs.getString(KEY_LEDGER_RESPONSE_HOST, null) ?: return null
        val intentId = prefs.getString(KEY_LEDGER_RESPONSE_INTENT, null) ?: return null
        val responseNonce = prefs.getString(KEY_LEDGER_RESPONSE_NONCE, null) ?: return null
        val expiresAt = prefs.getLong(KEY_LEDGER_RESPONSE_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return null
        val source = prefs.getString(KEY_LEDGER_RESPONSE_SOURCE, null) ?: return null
        val recipient = prefs.getString(KEY_LEDGER_RESPONSE_RECIPIENT, null) ?: return null
        val lamports = prefs.getLong(KEY_LEDGER_RESPONSE_LAMPORTS, 0L)
        if (lamports <= 0) return null
        return PendingLedgerResponseRecord(
            sessionId = sessionId,
            requestId = requestId,
            questionId = questionId,
            hostBinding = hostBinding,
            intentId = intentId,
            responseNonce = responseNonce,
            expiresAtEpochMillis = expiresAt,
            sourceAddress = source,
            recipientAddress = recipient,
            lamports = lamports,
            receipt = prefs.getString(KEY_LEDGER_RESPONSE_RECEIPT, null),
            hardwareReviewStarted = prefs.getBoolean(
                KEY_LEDGER_RESPONSE_HARDWARE_REVIEW_STARTED,
                false,
            ),
        )
    }

    /**
     * Atomically claim the irreversible hardware-review boundary for one exact typed request.
     * The commit happens before SIGN_MESSAGE. A crash after CLAIMED is therefore ambiguous and must
     * fail closed rather than presenting a fresh signing prompt for the same intent.
     */
    @Synchronized
    internal fun claimPendingLedgerHardwareReview(
        intentId: String,
        responseNonce: String,
        expiresAtEpochMillis: Long,
        sourceAddress: String,
        recipientAddress: String,
        lamports: Long,
    ): LedgerHardwareReviewClaimResult {
        val plan = planLedgerHardwareReviewClaim(
            current = pendingLedgerResponse(),
            intentId = intentId,
            responseNonce = responseNonce,
            expiresAtEpochMillis = expiresAtEpochMillis,
            sourceAddress = sourceAddress,
            recipientAddress = recipientAddress,
            lamports = lamports,
        )
        val updated = plan.updatedRecord ?: return plan.result
        return if (setPendingLedgerResponse(updated)) {
            LedgerHardwareReviewClaimResult.CLAIMED
        } else {
            LedgerHardwareReviewClaimResult.PERSISTENCE_FAILED
        }
    }

    private fun removePendingLedgerResponseFields(edit: SharedPreferences.Editor): SharedPreferences.Editor =
        edit
            .remove(KEY_LEDGER_RESPONSE_SESSION)
            .remove(KEY_LEDGER_RESPONSE_REQUEST)
            .remove(KEY_LEDGER_RESPONSE_QUESTION)
            .remove(KEY_LEDGER_RESPONSE_HOST)
            .remove(KEY_LEDGER_RESPONSE_INTENT)
            .remove(KEY_LEDGER_RESPONSE_NONCE)
            .remove(KEY_LEDGER_RESPONSE_EXPIRES_AT)
            .remove(KEY_LEDGER_RESPONSE_SOURCE)
            .remove(KEY_LEDGER_RESPONSE_RECIPIENT)
            .remove(KEY_LEDGER_RESPONSE_LAMPORTS)
            .remove(KEY_LEDGER_RESPONSE_RECEIPT)
            .remove(KEY_LEDGER_RESPONSE_HARDWARE_REVIEW_STARTED)

    fun clearPendingLedgerResponse(): Boolean =
        removePendingLedgerResponseFields(prefs.edit()).commit()

    fun ledgerReceiptOutboxState(): LedgerReceiptOutboxState {
        val raw = prefs.getString(KEY_LEDGER_RECEIPT_OUTBOX, null)
            ?: return LedgerReceiptOutboxState()
        return runCatching {
            ledgerJson.decodeFromString(LedgerReceiptOutboxState.serializer(), raw)
        }.getOrDefault(LedgerReceiptOutboxState())
    }

    /** Atomically release the active hardware slot only after the exact receipt is archived. */
    @Synchronized
    fun archivePendingLedgerReceipt(expected: PendingLedgerResponseRecord): Boolean {
        val authoritative = pendingLedgerResponse() ?: return false
        if (mergePendingLedgerResponse(authoritative, expected) != authoritative) return false
        val plan = planLedgerReceiptArchive(ledgerReceiptOutboxState(), authoritative)
        if (!plan.accepted) return false
        val edit = prefs.edit().putString(
            KEY_LEDGER_RECEIPT_OUTBOX,
            ledgerJson.encodeToString(LedgerReceiptOutboxState.serializer(), plan.state),
        )
        return removePendingLedgerResponseFields(edit).commit()
    }

    /** Remove only the exact receipt Core just accepted; same-key conflicts remain archived. */
    @Synchronized
    fun removeLedgerReceiptOutboxRecord(record: PendingLedgerResponseRecord): Boolean {
        val current = ledgerReceiptOutboxState()
        if (record !in current.records) return false
        val next = current.copy(records = current.records.filterNot { it == record })
        return prefs.edit()
            .putString(
                KEY_LEDGER_RECEIPT_OUTBOX,
                ledgerJson.encodeToString(LedgerReceiptOutboxState.serializer(), next),
            )
            .commit()
    }

    /** Durable capability inbox + financial idempotency tombstones, committed atomically. */
    @Synchronized
    fun ledgerInboxState(): LedgerInboxState {
        val raw = prefs.getString(KEY_LEDGER_INBOX, null) ?: return LedgerInboxState()
        return runCatching { ledgerJson.decodeFromString(LedgerInboxState.serializer(), raw) }
            .getOrDefault(LedgerInboxState())
    }

    @Synchronized
    fun setLedgerInboxState(state: LedgerInboxState): Boolean =
        prefs.edit()
            .putString(KEY_LEDGER_INBOX, ledgerJson.encodeToString(LedgerInboxState.serializer(), state))
            .commit()

    /** Logout drops old-host work but retains tombstones so a replay cannot be signed later. */
    @Synchronized
    fun clearLedgerInboxActions(): Boolean {
        val current = ledgerInboxState()
        return setLedgerInboxState(current.copy(actions = emptyList()))
    }

    /** Forget AgenC's association. Android's OS-level Bluetooth bond is intentionally untouched. */
    fun clearLedgerAssociation() {
        prefs.edit()
            .remove(KEY_LEDGER_DEVICE_ADDRESS)
            .remove(KEY_LEDGER_DEVICE_NAME)
            .remove(KEY_LEDGER_ACCOUNT_PATH)
            .remove(KEY_LEDGER_ACCOUNT_ADDRESS)
            .remove(KEY_LEDGER_DISCOVERY_COUNT)
            .apply()
    }

    /** Clear only the selected public account while retaining the remembered Ledger device. */
    fun clearLedgerAccountSelection() {
        prefs.edit()
            .remove(KEY_LEDGER_ACCOUNT_PATH)
            .remove(KEY_LEDGER_ACCOUNT_ADDRESS)
            .apply()
    }

    private fun putNullableString(key: String, value: String?) {
        prefs.edit().let { edit ->
            if (value.isNullOrBlank()) edit.remove(key) else edit.putString(key, value)
        }.apply()
    }

    // MARK: - Notification settings (Android port of iOS AppSettings notify toggles + intelligence)

    /** Pull the user back when the agent needs a decision (permission / input) while backgrounded. */
    var notifyAttention: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ATTENTION, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_ATTENTION, v).apply()

    /** Notify when a turn finishes while backgrounded. Enabled by default for remote-control work. */
    var notifyTurnDone: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_TURN, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_TURN, v).apply()

    /** Notify when a turn errors while backgrounded. */
    var notifyErrors: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ERRORS, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_ERRORS, v).apply()

    /**
     * Optional minimum turn duration before a "turn complete" notification fires. The default is
     * zero because a remote-control client must report every authoritative completion; users who
     * prefer fewer alerts can raise the threshold in Account > Notifications.
     */
    var turnDoneThresholdSeconds: Int
        get() = prefs.getInt(KEY_TURN_THRESHOLD, 0)
        set(v) = prefs.edit().putInt(KEY_TURN_THRESHOLD, v).apply()

    /**
     * Suppress non-urgent (turn-done) pushes inside the quiet window. Time-sensitive
     * needs-you / error pushes still fire, so a blocked turn always pulls you back.
     */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_QUIET_ENABLED, v).apply()

    /** Quiet window start hour, 0–23. */
    var quietStartHour: Int
        get() = prefs.getInt(KEY_QUIET_START, 22)
        set(v) = prefs.edit().putInt(KEY_QUIET_START, v).apply()

    /** Quiet window end hour, 0–23. When start == end the window is empty (never quiet). */
    var quietEndHour: Int
        get() = prefs.getInt(KEY_QUIET_END, 7)
        set(v) = prefs.edit().putInt(KEY_QUIET_END, v).apply()

    /** Sessions the user has muted: no turn-done / needs-you / error pushes for these ids. */
    var mutedSessions: Set<String>
        get() = prefs.getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_MUTED, v).apply()

    /** Toggle a session's mute state. */
    fun toggleMuted(sessionId: String) {
        val cur = mutedSessions.toMutableSet()
        if (!cur.remove(sessionId)) cur.add(sessionId)
        mutedSessions = cur
    }

    /**
     * Is [hour] inside the configured quiet window? Handles a window that wraps past midnight
     * (e.g. 22 → 7). An empty window (start == end) is never quiet.
     */
    fun isWithinQuietHours(
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): Boolean {
        if (!quietHoursEnabled || quietStartHour == quietEndHour) return false
        return if (quietStartHour < quietEndHour) {
            hour in quietStartHour until quietEndHour
        } else {
            // Wraps midnight: e.g. [22, 7) covers 22,23,0,1,…,6.
            hour >= quietStartHour || hour < quietEndHour
        }
    }

    companion object {
        private const val FILE_NAME = "agenc.prefs"

        // Keys mirroring the iOS UserDefaults keys.
        const val KEY_IDENTITY_NAME = "agenc.identity.name"
        const val KEY_IDENTITY_HANDLE = "agenc.identity.handle"
        const val KEY_IDENTITY_TIER = "agenc.identity.tier"
        const val KEY_IDENTITY_ACCOUNT_ID = "agenc.identity.account_id"
        const val KEY_RELAY_URL = "agenc.relay.url"
        const val KEY_IDMAP = "agenc.idmap.local"
        const val KEY_SESSIONS = "agenc.sessions.local"
        const val KEY_PAIRING = "agenc.pairing.current"

        // Ledger metadata is deliberately plain/public: BLE identity, derivation path, and pubkey.
        const val KEY_LEDGER_DEVICE_ADDRESS = "agenc.ledger.device.address"
        const val KEY_LEDGER_DEVICE_NAME = "agenc.ledger.device.name"
        const val KEY_LEDGER_ACCOUNT_PATH = "agenc.ledger.account.path"
        const val KEY_LEDGER_ACCOUNT_ADDRESS = "agenc.ledger.account.address"
        const val KEY_LEDGER_DISCOVERY_COUNT = "agenc.ledger.discovery.count"
        const val KEY_LEDGER_PENDING_SIGNATURE = "agenc.ledger.pending.signature"
        const val KEY_LEDGER_PENDING_TRANSACTION = "agenc.ledger.pending.transaction"
        const val KEY_LEDGER_PENDING_LAST_VALID_HEIGHT = "agenc.ledger.pending.last_valid_height"
        const val KEY_LEDGER_RESPONSE_SESSION = "agenc.ledger.response.session"
        const val KEY_LEDGER_RESPONSE_REQUEST = "agenc.ledger.response.request"
        const val KEY_LEDGER_RESPONSE_QUESTION = "agenc.ledger.response.question"
        const val KEY_LEDGER_RESPONSE_HOST = "agenc.ledger.response.host"
        const val KEY_LEDGER_RESPONSE_INTENT = "agenc.ledger.response.intent"
        const val KEY_LEDGER_RESPONSE_NONCE = "agenc.ledger.response.nonce"
        const val KEY_LEDGER_RESPONSE_EXPIRES_AT = "agenc.ledger.response.expires_at"
        const val KEY_LEDGER_RESPONSE_SOURCE = "agenc.ledger.response.source"
        const val KEY_LEDGER_RESPONSE_RECIPIENT = "agenc.ledger.response.recipient"
        const val KEY_LEDGER_RESPONSE_LAMPORTS = "agenc.ledger.response.lamports"
        const val KEY_LEDGER_RESPONSE_RECEIPT = "agenc.ledger.response.receipt"
        const val KEY_LEDGER_RESPONSE_HARDWARE_REVIEW_STARTED =
            "agenc.ledger.response.hardware_review_started"
        const val KEY_LEDGER_INBOX = "agenc.ledger.inbox.v1"
        const val KEY_LEDGER_RECEIPT_OUTBOX = "agenc.ledger.receipt_outbox.v1"
        const val DEFAULT_LEDGER_DISCOVERY_COUNT = 5

        // Notification settings keys (mirror the iOS AppSettings K.* keys).
        const val KEY_NOTIFY_ATTENTION = "agenc.notify.attention"
        const val KEY_NOTIFY_TURN = "agenc.notify.turn"
        const val KEY_NOTIFY_ERRORS = "agenc.notify.errors"
        const val KEY_TURN_THRESHOLD = "agenc.notify.turn.threshold"
        const val KEY_QUIET_ENABLED = "agenc.notify.quiet.enabled"
        const val KEY_QUIET_START = "agenc.notify.quiet.start"
        const val KEY_QUIET_END = "agenc.notify.quiet.end"
        const val KEY_MUTED = "agenc.notify.muted"

        /** Per-session persisted transcript blob key. */
        fun transcriptKey(sessionId: String): String = "agenc.tx.$sessionId"
    }
}
