package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.AgentSummary
import ai.tetsuo.agenc.model.Event
import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.model.InitialMessage
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.LedgerSolanaTransferClientAction
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PermissionDecisionAction
import ai.tetsuo.agenc.model.PermissionDecisionState
import ai.tetsuo.agenc.model.PendingLedgerInteraction
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.model.TranscriptItem
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.net.JsonRpcConnection
import ai.tetsuo.agenc.net.JsonValue
import ai.tetsuo.agenc.net.PortalEventAdapter
import ai.tetsuo.agenc.net.jsonArray
import ai.tetsuo.agenc.net.jsonObject
import ai.tetsuo.agenc.net.jv
import ai.tetsuo.agenc.notif.NotificationCoordinator
import ai.tetsuo.agenc.notif.NotificationDeliveryPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-flight connection-attempt ownership, used only while [DaemonHub.mutex] is held.
 *
 * A target change invalidates the current generation and returns its not-yet-published socket for
 * closure. Completing an older attempt can therefore never clear or publish over a newer attempt.
 */
internal class ConnectionAttemptRegistry<T> {
    internal data class Attempt<T>(
        val generation: Long,
        val endpoint: String,
        val relayMode: Boolean,
        val resource: T,
    )

    private var generation: Long = 0
    private var current: Attempt<T>? = null

    val isRunning: Boolean get() = current != null

    fun claim(endpoint: String, relayMode: Boolean, create: (String, Boolean) -> T): Attempt<T>? {
        if (current != null) return null
        return Attempt(
            generation = generation,
            endpoint = endpoint,
            relayMode = relayMode,
            resource = create(endpoint, relayMode),
        ).also { current = it }
    }

    /** Invalidates every token from the previous generation and releases its resource for closure. */
    fun invalidate(): T? {
        generation += 1
        val resource = current?.resource
        current = null
        return resource
    }

    /** Consume [attempt] only when it still owns the current generation. */
    fun complete(attempt: Attempt<T>): Boolean {
        if (current !== attempt || attempt.generation != generation) return false
        current = null
        return true
    }
}

/** Pure wire builder: Ledger authority is never smuggled through free-form answer text. */
internal fun elicitationResponsePayload(
    questionId: String,
    answer: String?,
    clientResult: LedgerTransferReceipt?,
): JsonValue.Obj {
    require((answer == null) != (clientResult == null)) {
        "exactly one elicitation response channel is required"
    }
    val fields = linkedMapOf<String, JsonValue>(
        "answers" to jsonObject(
            questionId to jsonObject(
                "answers" to jsonArray(
                    if (answer == null) emptyList() else listOf(answer.jv()),
                ),
            ),
        ),
    )
    if (clientResult != null) {
        fields["clientResult"] = JsonValue.parse(clientResult.toJson()) as? JsonValue.Obj
            ?: error("Couldn't encode the validated Ledger result")
    }
    return jsonObject(fields)
}

/**
 * Direct port of the iOS app's DaemonHub.swift — the connection orchestrator.
 *
 * One shared daemon connection, multiplexed across sessions. Dials the relay (frames wrapped in a
 * `{t:"data"}` envelope) or the local daemon (raw JSON-RPC); runs `initialize`, lists sessions,
 * attaches per-session stores, routes incoming Portal notifications to the right store by sessionId,
 * and HANDLES `event.agent_status` (on idle/done/completed/stopped it flips the open session's
 * lifecycle to Done; on error it surfaces an Error event).
 *
 * Observable state for the UI:
 *  - [state] — the connection state
 *  - [sessions] — the sessions list (local registry merged with daemon-reported sessions)
 *  - [agents] — the agents list (agent.list projection)
 *
 * Per-session [SessionStore]s are vended by [storeFor] / [attach]; the UI observes each store's
 * own StateFlows (items / lifecycle / pendingPermission / pendingElicitation).
 *
 * Everything mutating internal state runs on a single-threaded confinement guarded by [mutex]; the
 * notification pump drives ingestion from one coroutine, matching the Swift actor/MainActor model.
 */
class DaemonHub(
    private var endpoint: String,
    private val cookie: String,
    private var relayMode: Boolean,
    private val prefs: Prefs,
    private val notifications: NotificationCoordinator? = null,
    // SupervisorJob + a logging handler: one crashed child (a bad frame in the pump, a failed
    // deliver) must NOT cancel the whole hub scope — with a plain Job() the pump, reconnect loop,
    // and every future scope.launch silently die together and the app looks "connected but dead".
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                android.util.Log.e("DaemonHub", "hub coroutine crashed", e)
            },
    ),
) {
    enum class ElicitationResponseOutcome {
        Resolved,
        AlreadySettled,
        InFlight,
        TransportFailed,
    }

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Failed(val reason: String) : ConnectionState
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentSummary>>(emptyList())
    val agents: StateFlow<List<AgentSummary>> = _agents.asStateFlow()

    private val _daemonProtocol = MutableStateFlow("")
    val daemonProtocol: StateFlow<String> = _daemonProtocol.asStateFlow()

    private val _daemonModel = MutableStateFlow("")
    val daemonModel: StateFlow<String> = _daemonModel.asStateFlow()

    private val _daemonProvider = MutableStateFlow("")
    val daemonProvider: StateFlow<String> = _daemonProvider.asStateFlow()

    private val _accountIdentity = MutableStateFlow<Identity?>(null)
    /** Authenticated identity reported by the connected Core. */
    val accountIdentity: StateFlow<Identity?> = _accountIdentity.asStateFlow()

    /** Capability-routed Ledger request, independent of whichever chat happens to be open. */
    private val _pendingLedgerInteraction = MutableStateFlow<PendingLedgerInteraction?>(null)
    val pendingLedgerInteraction: StateFlow<PendingLedgerInteraction?> =
        _pendingLedgerInteraction.asStateFlow()
    private val ledgerInteractionLock = Any()
    private val ledgerInteractionQueue = ArrayDeque<PendingLedgerInteraction>()

    @Volatile private var conn: JsonRpcConnection? = null
    private var pumpJob: Job? = null
    /** Parent for the three post-handshake refreshes and reconnect reattach pass. */
    private var metadataRefreshJob: Job? = null
    private var reconnectJob: Job? = null
    private var statusSessionRefreshJob: Job? = null
    private val mutex = Mutex()
    // Set true while a connect() is mid-handshake so a second caller can't open a parallel socket.
    @Volatile private var connecting = false
    /** In-flight sockets are registered before their first suspend so reconfigure can close them. */
    private val connectionAttempts = ConnectionAttemptRegistry<JsonRpcConnection>()
    /** False after logout: no reconnect path may silently fall back to a local daemon. */
    @Volatile private var connectionsEnabled = true
    private var forcedReconnectRunning = false

    /** Route ids belong to physical sockets; pairing them avoids cross-generation attach races. */
    private val connectionClientIds = ConcurrentHashMap<JsonRpcConnection, String>()

    // Thread-safe: these maps are read/written from BOTH the notification pump (Dispatchers.Default)
    // and UI-thread callbacks (send/approve/attach). ConcurrentHashMap prevents the get/put races and
    // ConcurrentModificationExceptions the plain HashMaps caused during live sessions + reconnects.
    private val stores = ConcurrentHashMap<String, SessionStore>()
    /** Single-flight attach/hydration state, scoped to the current physical socket generation. */
    private val sessionAttachMutexes = ConcurrentHashMap<String, Mutex>()
    private val attachedConnectionFor = ConcurrentHashMap<String, JsonRpcConnection>()
    private val hydratedConnectionFor = ConcurrentHashMap<String, JsonRpcConnection>()

    // MARK: - Notification intelligence (Android port of iOS DaemonHub notify state)

    /** The session displayed by ChatHost; opening it clears that session's posted notifications. */
    @Volatile var currentlyOpenSessionId: String? = null

    /**
     * Immediate Activity visibility, updated from MainActivity.onStart/onStop. ProcessLifecycleOwner
     * deliberately delays ON_STOP, which created a race where a fast completion just after pressing
     * Home was misclassified as foreground and never notified.
     */
    @Volatile private var appInForeground: Boolean = false

    val isAppInForeground: Boolean get() = appInForeground

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    /** Per-session turn-start wall-clock (millis) — drives the turn-done threshold + "· ran 2m". */
    private val turnStartedAt = ConcurrentHashMap<String, Long>()

    /** Per-session last agent message text — the turn-done body preview. Absence = no message yet. */
    private val lastAgentMessage = ConcurrentHashMap<String, String>()

    /** Local UI session id ↔ daemon `session_<uuid>`. */
    private var daemonIdFor: MutableMap<String, String> = ConcurrentHashMap()
    private var localIdFor: MutableMap<String, String> = ConcurrentHashMap()
    private val idMapStore = SessionIdMap(prefs)

    /** daemon sessionId -> agentId (from session.list), used to load a session's history. */
    private val agentIdForSession = ConcurrentHashMap<String, String>()

    /** Messages typed while disconnected — flushed on (re)connect so nothing is dropped. */
    private val pendingSends = mutableListOf<PendingSend>()

    private data class PendingSend(
        val sessionId: String,
        val displayText: String,
        val agentText: String,
        val images: List<String>,
    )

    private val _permissionDecisions =
        MutableStateFlow<Map<String, PermissionDecisionState>>(emptyMap())
    val permissionDecisions: StateFlow<Map<String, PermissionDecisionState>> =
        _permissionDecisions.asStateFlow()
    private val permissionDecisionsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val elicitationResponsesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val messageEditsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val agentStatusTracker = AgentStatusDeliveryTracker()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadSessions()
        loadIdMap()
        restoreLedgerInbox()
    }

    /** Get-or-create the [SessionStore] for a local session id. */
    fun storeFor(sessionId: String, title: String): SessionStore =
        stores.getOrPut(sessionId) { SessionStore(sessionId, title, prefs) }

    // MARK: - id map persistence

    private fun loadIdMap() {
        daemonIdFor = ConcurrentHashMap(idMapStore.load())
        localIdFor = ConcurrentHashMap(SessionIdMap.inverse(daemonIdFor))
    }

    private fun persistIdMap() = idMapStore.save(daemonIdFor)

    // MARK: - Local session registry

    /** Insert a session if we don't know it yet (newest first). */
    fun ensureSession(id: String, title: String) {
        if (_sessions.value.any { it.id == id }) return
        val next = listOf(
            SessionSummary(
                id = id, title = title, lifecycle = SessionLifecycle.Idle,
                cwd = "", subtitle = "", updated = "now",
            ),
        ) + _sessions.value
        _sessions.value = next
        persistSessions()
    }

    /** Register a brand-new app-created session carrying its working directory. No-op if known. */
    fun registerSession(summary: SessionSummary) {
        if (_sessions.value.any { it.id == summary.id }) return
        _sessions.value = listOf(summary) + _sessions.value
        persistSessions()
    }

    private fun bumpSession(id: String, transform: (SessionSummary) -> SessionSummary) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val s = transform(list.removeAt(i))
        list.add(0, s)
        _sessions.value = list
        persistSessions()
    }

    private fun setSessionLifecycle(id: String, lc: SessionLifecycle) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].lifecycle == lc) return
        list[i] = list[i].copy(lifecycle = lc)
        _sessions.value = list
    }

    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        stores.remove(id)
        sessionAttachMutexes.remove(id)
        attachedConnectionFor.remove(id)
        hydratedConnectionFor.remove(id)
        agentStatusTracker.clearSession(id)
        unbindDaemonSession(id)
        prefs.remove(Prefs.transcriptKey(id))
        persistSessions()
    }

    fun renameSession(id: String, title: String) {
        bumpSession(id) { it.copy(title = title) }
    }

    private fun persistSessions() {
        val arr = _sessions.value.map {
            mapOf(
                "id" to it.id, "title" to it.title, "cwd" to it.cwd,
                "subtitle" to it.subtitle, "updated" to it.updated,
            )
        }
        prefs.setString(SESSIONS_KEY, json.encodeToString(sessionsSerializer, arr))
    }

    private fun loadSessions() {
        val raw = prefs.getString(SESSIONS_KEY) ?: return
        val arr = runCatching { json.decodeFromString(sessionsSerializer, raw) }.getOrNull() ?: return
        _sessions.value = arr.map {
            SessionSummary(
                id = it["id"] ?: UUID.randomUUID().toString(),
                title = it["title"] ?: "Chat",
                lifecycle = SessionLifecycle.Idle,
                cwd = it["cwd"] ?: "",
                subtitle = it["subtitle"] ?: "",
                updated = it["updated"] ?: "",
            )
        }
    }

    // MARK: - Connection

    suspend fun connect() {
        if (!connectionsEnabled) return
        // Snapshot the target and register the socket before the first suspension. Reconfigure can
        // now invalidate + close this exact attempt instead of merely resetting `connecting` while
        // an untracked handshake continues in the background.
        val attempt = mutex.withLock {
            if (!connectionsEnabled || conn != null || connecting) return
            val claimed = connectionAttempts.claim(endpoint, relayMode) { targetUrl, targetRelay ->
                JsonRpcConnection(targetUrl, relayMode = targetRelay)
            } ?: return
            connecting = true
            _state.value = ConnectionState.Connecting
            claimed
        }
        val c = attempt.resource
        val nextClientId = "agenc-android-" + UUID.randomUUID().toString()
        try {
            c.connect()
            val initResult = c.initializeHandshake(clientName = "agenc-portal", authCookie = cookie)
            val daemonProtocol =
                initResult["protocol"]?.get("version")?.stringValue ?: "1.0.0"
            // Publish and start all connection-owned work under the same lock. disconnect() cannot
            // invalidate the socket between publication and job registration, which would otherwise
            // leave a new pump/metadata job alive after logout.
            val accepted = mutex.withLock {
                val ownsAttempt = connectionAttempts.complete(attempt)
                if (!ownsAttempt) {
                    false
                } else {
                    connecting = false
                    if (!connectionsEnabled || conn != null) {
                        false
                    } else {
                        connectionClientIds[c] = nextClientId
                        conn = c
                        attachedConnectionFor.clear()
                        hydratedConnectionFor.clear()
                        _daemonProtocol.value = daemonProtocol
                        _state.value = ConnectionState.Connected
                        startPump(c)
                        flushPendingSends()
                        metadataRefreshJob?.cancel()
                        metadataRefreshJob = scope.launch {
                            launch { refreshDaemonInfo() }
                            launch { refreshAgents() }
                            launch {
                                refreshSessions()
                                reattachStores(c)
                            }
                        }
                        true
                    }
                }
            }
            if (!accepted) {
                runCatching { c.close() }
                return
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { c.close() }
            connectionClientIds.remove(c)
            mutex.withLock {
                if (connectionAttempts.complete(attempt)) {
                    connecting = false
                    if (connectionsEnabled && conn == null) {
                        _state.value = ConnectionState.Disconnected
                    }
                }
            }
            throw e // never swallow cancellation — the caller's scope is going down
        } catch (e: Throwable) {
            runCatching { c.close() }
            connectionClientIds.remove(c)
            mutex.withLock {
                // A stale socket may fail because reconfigure deliberately closed it. Only the
                // attempt that still owns this generation may clear state or schedule recovery.
                if (connectionAttempts.complete(attempt)) {
                    connecting = false
                    _state.value = ConnectionState.Failed("can't reach your computer")
                    // A locally-rendered send must never sit behind fake typing dots through an
                    // unbounded reconnect loop. The composer prevents new offline sends; this
                    // terminates any send that raced the disconnect and reports it in transcript.
                    failPendingSends("Core is offline; your message wasn't delivered")
                    // A failed initial handshake has no pump to start recovery, so schedule it here.
                    if (connectionsEnabled) scheduleReconnect()
                }
            }
        }
    }

    suspend fun refreshSessions() {
        val c = conn?.takeIf { connectionsEnabled } ?: return
        val result = runCatching { c.send("session.list", jsonObject()) }.getOrNull() ?: return
        val raw = result["sessions"]?.arrayValue ?: emptyList()
        val remote = raw.mapNotNull { sessionSummary(it) }
        mutex.withLock {
            if (!connectionsEnabled || conn !== c) return
            for (s in raw) {
                val sid = s["sessionId"]?.stringValue
                val aid = s["agentId"]?.stringValue
                if (sid != null && aid != null) agentIdForSession[sid] = aid
            }
            // session.terminate is Core's archive/delete semantic. Closed sessions stay in the
            // daemon's in-memory list until restart, so remove those rows instead of projecting them
            // back as Idle.
            val closedDaemonIds = raw.asSequence()
                .filter { it["status"]?.stringValue == "closed" }
                .mapNotNull { it["sessionId"]?.stringValue }
                .toSet()
            val closedLocalIds = closedDaemonIds.mapTo(mutableSetOf()) { daemonId ->
                localIdFor[daemonId] ?: daemonId
            }
            val list = _sessions.value
                .filterNot { it.id in closedDaemonIds || it.id in closedLocalIds }
                .toMutableList()
            for (r in remote.filterNot { it.id in closedDaemonIds }) {
                // A daemon session already bound to an app-created row must refresh that LOCAL row.
                // session.list is discovery-only: never create a daemon->daemon self binding here
                // and never overwrite the inverse of an existing local->daemon binding.
                val localId = localIdFor[r.id]
                val reconciled = if (localId != null) r.copy(id = localId) else r
                val aliases = if (localId != null) setOf(localId, r.id) else setOf(r.id)
                val first = list.indexOfFirst { it.id in aliases }
                if (first < 0) {
                    list.add(reconciled)
                } else {
                    list[first] = reconciled
                    // Clean up a stale daemon-id row left by the old self-binding behavior.
                    for (i in list.lastIndex downTo 0) {
                        if (i != first && list[i].id in aliases) list.removeAt(i)
                    }
                }
            }
            _sessions.value = list
            persistSessions()
        }
    }

    suspend fun refreshAgents() {
        val c = conn?.takeIf { connectionsEnabled } ?: return
        val result = runCatching { c.send("agent.list", jsonObject()) }.getOrNull() ?: return
        val refreshed = (result["agents"]?.arrayValue ?: emptyList()).mapNotNull { agentSummary(it) }
        mutex.withLock {
            if (!connectionsEnabled || conn !== c) return
            _agents.value = refreshed
        }
    }

    private fun clientIdFor(c: JsonRpcConnection): String =
        connectionClientIds[c] ?: throw JsonRpcConnection.ConnError.Closed

    /** Restore notification routing after every reconnect before the user interacts again. */
    private suspend fun reattachStores(c: JsonRpcConnection) {
        for (localId in stores.keys.toList()) {
            if (!connectionsEnabled || conn !== c) return
            attachAndHydrate(localId, c)
        }
    }

    /** Daemon identity via auth.whoami (real app-server method). */
    suspend fun refreshDaemonInfo() {
        val c = conn?.takeIf { connectionsEnabled } ?: return
        val r = runCatching { c.send("auth.whoami", jsonObject()) }.getOrNull() ?: return
        val model = r["model"]?.stringValue ?: r["identity"]?.get("model")?.stringValue
        val provider = r["provider"]?.stringValue ?: r["identity"]?.get("provider")?.stringValue
        val raw = r["identity"]
        val identity = if (r["authenticated"]?.boolValue == true && raw != null) {
            val email = raw["email"]?.stringValue.orEmpty()
            val handle = raw["handle"]?.stringValue
                ?.takeIf { it.isNotBlank() }
                ?: email
            val displayName = raw["displayName"]?.stringValue
                ?.takeIf { it.isNotBlank() }
                ?: handle.substringBefore('@').takeIf { it.isNotBlank() }
                ?: "Account"
            val reportedTier = (
                raw["plan"]?.stringValue ?: r["subscriptionTier"]?.stringValue
            )?.let { plan ->
                Tier.entries.firstOrNull { it.name.equals(plan, ignoreCase = true) }
            }
            Identity(
                displayName = displayName,
                handle = handle,
                tier = reportedTier ?: Tier.free,
                accountId = raw["accountId"]?.stringValue?.takeIf { it.isNotBlank() },
                tierVerified = reportedTier != null,
            )
        } else {
            null
        }
        mutex.withLock {
            if (!connectionsEnabled || conn !== c) return
            if (model != null) _daemonModel.value = model
            if (provider != null) _daemonProvider.value = provider
            // Runtime identity is a live projection, never the phone bearer cache. Keeping the two
            // stores separate prevents Core account A from becoming phone account B after restart.
            _accountIdentity.value = identity
        }
    }

    suspend fun stopAgent(agentId: String) {
        val c = conn ?: return
        runCatching { c.send("agent.stop", jsonObject("agentId" to agentId.jv())) }
        refreshAgents()
    }

    suspend fun agentLogs(agentId: String): List<String> {
        val c = conn ?: return emptyList()
        val result = runCatching {
            c.send("agent.logs", jsonObject("agentId" to agentId.jv()))
        }.getOrNull() ?: return emptyList()
        return result["lines"]?.stringArray ?: emptyList()
    }

    fun setPermissionMode(mode: String, sessionId: String) {
        scope.launch {
            val did = knownDaemonSession(sessionId) ?: return@launch
            runCatching {
                conn?.send(
                    "session.setPermissionMode",
                    jsonObject("sessionId" to did.jv(), "mode" to mode.jv()),
                )
            }
        }
    }

    /** Interrupt the running turn for this session. */
    fun cancelTurn(sessionId: String) {
        scope.launch {
            val did = knownDaemonSession(sessionId) ?: return@launch
            runCatching {
                conn?.send("session.cancelTurn", jsonObject("sessionId" to did.jv()))
            }
        }
    }

    /** Answer an agent's user-input request so the turn can continue (elicitation.respond). */
    fun respondElicitation(sessionId: String, requestId: String, questionId: String, answer: String) {
        scope.launch { respondElicitationAwait(sessionId, requestId, questionId, answer) }
    }

    /** Awaitable variant used by notification actions; true only after Core accepts the response. */
    suspend fun respondElicitationAwait(
        sessionId: String,
        requestId: String,
        questionId: String,
        answer: String,
    ): Boolean = respondElicitationOutcome(sessionId, requestId, questionId, answer) ==
        ElicitationResponseOutcome.Resolved

    /** Send the typed receipt outside generic answer text so Core can validate its challenge. */
    suspend fun respondLedgerElicitationOutcome(
        sessionId: String,
        requestId: String,
        questionId: String,
        receipt: LedgerTransferReceipt,
    ): ElicitationResponseOutcome = respondElicitationOutcomeInternal(
        sessionId = sessionId,
        requestId = requestId,
        questionId = questionId,
        answer = null,
        clientResult = receipt,
    )

    /**
     * Correlated outcome used by Ledger receipts. A submitted transaction must never be signed a
     * second time merely because the relay acknowledgement was lost: after reconnect, resending
     * the exact receipt can resolve the still-pending request. [AlreadySettled] is intentionally
     * inconclusive until Core exposes a digest-aware acknowledgement journal.
     */
    suspend fun respondElicitationOutcome(
        sessionId: String,
        requestId: String,
        questionId: String,
        answer: String,
    ): ElicitationResponseOutcome = respondElicitationOutcomeInternal(
        sessionId = sessionId,
        requestId = requestId,
        questionId = questionId,
        answer = answer,
        clientResult = null,
    )

    private suspend fun respondElicitationOutcomeInternal(
        sessionId: String,
        requestId: String,
        questionId: String,
        answer: String?,
        clientResult: LedgerTransferReceipt?,
    ): ElicitationResponseOutcome {
        if (!elicitationResponsesInFlight.add(requestId)) {
            // Another coroutine is still waiting for Core's authoritative answer. It may yet fail
            // at the transport boundary, so callers must retain durable recovery state and retry.
            return ElicitationResponseOutcome.InFlight
        }
        val did = daemonIdFor[sessionId] ?: sessionId
        var source: JsonRpcConnection? = null
        return try {
            val c = conn ?: throw JsonRpcConnection.ConnError.Closed
            source = c
            val result = c.send(
                "elicitation.respond",
                jsonObject(
                    "sessionId" to did.jv(),
                    "requestId" to requestId.jv(),
                    "kind" to "request_user_input".jv(),
                    "response" to elicitationResponsePayload(questionId, answer, clientResult),
                ),
            )
            if (result["resolved"]?.boolValue == true) {
                stores[sessionId]?.resolveElicitation(requestId)
                notifications?.clearAttention(sessionId, requestId)
                ElicitationResponseOutcome.Resolved
            } else {
                ElicitationResponseOutcome.AlreadySettled
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: JsonRpcConnection.ConnError.Rpc) {
            val alreadyConsumed = error.dataCode == "INVALID_ARGUMENT" &&
                error.rpcMessage == "AgenC daemon elicitation request is not pending: $requestId"
            if (alreadyConsumed) {
                // This can also mean timeout/cancellation/restart. It is not proof that Core
                // accepted this exact receipt; the caller must retain durable Ledger recovery.
                ElicitationResponseOutcome.AlreadySettled
            } else {
                android.util.Log.w("DaemonHub", "Couldn't respond to elicitation", error)
                ElicitationResponseOutcome.TransportFailed
            }
        } catch (error: Throwable) {
            android.util.Log.w("DaemonHub", "Couldn't respond to elicitation", error)
            handleInteractionFailure(error, source)
            ElicitationResponseOutcome.TransportFailed
        } finally {
            elicitationResponsesInFlight.remove(requestId)
        }
    }

    /**
     * Consume one globally delivered action after Compose has persisted/retained it. A bounded FIFO
     * prevents simultaneous sessions from overwriting each other; replay is deduplicated by both
     * request and Core-generated intent id.
     */
    fun consumeLedgerInteraction(requestId: String, intentId: String) {
        synchronized(ledgerInteractionLock) {
            val inbox = prefs.ledgerInboxState()
            val retained = inbox.actions.filterNot { action ->
                action.requestId == requestId && action.intentId == intentId
            }
            if (retained.size != inbox.actions.size &&
                !prefs.setLedgerInboxState(inbox.copy(actions = retained))
            ) {
                android.util.Log.e("DaemonHub", "Couldn't durably consume Ledger inbox action")
                return
            }
            ledgerInteractionQueue.removeAll { pending ->
                pending.elicitation.id == requestId &&
                    pending.elicitation.clientAction?.intentId == intentId
            }
            _pendingLedgerInteraction.value = ledgerInteractionQueue.firstOrNull()
        }
    }

    private fun enqueueLedgerInteraction(interaction: PendingLedgerInteraction) {
        val action = interaction.elicitation.clientAction ?: return
        synchronized(ledgerInteractionLock) {
            val now = System.currentTimeMillis()
            val inbox = prefs.ledgerInboxState()
            val activeIntentIds = inbox.actions.mapTo(mutableSetOf()) { it.intentId }
            val tombstones = inbox.tombstones.filter {
                it.expiresAtEpochMillis > now || it.intentId in activeIntentIds
            }
            if (tombstones.any { it.intentId == action.intentId }) return
            // Core enforces one request per human turn; this cap only protects a phone connected to
            // many concurrently-running sessions from unbounded local memory growth.
            if (inbox.actions.size >= MAX_PENDING_LEDGER_INTERACTIONS) {
                android.util.Log.w("DaemonHub", "Ledger request queue is full; retaining oldest requests")
                return
            }
            val record = interaction.toRecord()
            val nextInbox = LedgerInboxState(
                actions = inbox.actions + record,
                tombstones = tombstones + LedgerIntentTombstone(
                    intentId = action.intentId,
                    expiresAtEpochMillis = action.expiresAtEpochMillis,
                ),
            )
            if (!prefs.setLedgerInboxState(nextInbox)) {
                android.util.Log.e("DaemonHub", "Couldn't durably receive Ledger action")
                return
            }
            ledgerInteractionQueue.addLast(interaction)
            if (_pendingLedgerInteraction.value == null) {
                _pendingLedgerInteraction.value = interaction
            }
        }
    }

    private fun restoreLedgerInbox() {
        val inbox = prefs.ledgerInboxState()
        synchronized(ledgerInteractionLock) {
            ledgerInteractionQueue.clear()
            inbox.actions.mapTo(ledgerInteractionQueue) { it.toInteraction() }
            _pendingLedgerInteraction.value = ledgerInteractionQueue.firstOrNull()
        }
    }

    private fun currentLedgerHostBinding(): String =
        PairingStore(prefs).current?.pairingId ?: LOCAL_LEDGER_HOST_BINDING

    private fun PendingLedgerInteraction.toRecord(): PendingLedgerActionRecord {
        val action = requireNotNull(elicitation.clientAction)
        return PendingLedgerActionRecord(
            sessionId = sessionId,
            requestId = elicitation.id,
            questionId = elicitation.questionId,
            intentId = action.intentId,
            responseNonce = action.responseNonce,
            to = action.to,
            lamports = action.lamports,
            note = action.note,
            expiresAtEpochMillis = action.expiresAtEpochMillis,
            hostBinding = hostBinding,
        )
    }

    private fun PendingLedgerActionRecord.toInteraction(): PendingLedgerInteraction {
        val action = LedgerSolanaTransferClientAction(
            intentId = intentId,
            responseNonce = responseNonce,
            to = to,
            lamports = lamports,
            note = note,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        return PendingLedgerInteraction(
            sessionId = sessionId,
            hostBinding = hostBinding,
            elicitation = PendingElicitation(
                id = requestId,
                questionId = questionId,
                question = "Review and approve this Solana transfer on your Ledger device.",
                options = emptyList(),
                allowsOther = false,
                clientAction = action,
            ),
        )
    }

    /** Switch host: tear down and reconnect to the new endpoint (and transport mode). */
    suspend fun reconfigure(url: String, relay: Boolean = false) {
        val (sameTarget, socketsToClose) = mutex.withLock {
            connectionsEnabled = true
            if (url == endpoint && relay == relayMode) {
                true to emptyList()
            } else {
                endpoint = url
                relayMode = relay
                val inFlight = connectionAttempts.invalidate()
                val current = conn
                metadataRefreshJob?.cancel()
                metadataRefreshJob = null
                pumpJob?.cancel()
                pumpJob = null
                reconnectJob?.cancel()
                reconnectJob = null
                conn = null
                connecting = false
                attachedConnectionFor.clear()
                hydratedConnectionFor.clear()
                _sessions.value = emptyList()
                _agents.value = emptyList()
                _accountIdentity.value = null
                _state.value = ConnectionState.Disconnected
                false to listOfNotNull(current, inFlight).distinct()
            }
        }
        if (sameTarget) {
            // Initial Compose effects can call reconfigure and ensureConnected together. Reuse the
            // same snapshotted attempt instead of resetting it and opening a second relay socket.
            connect()
            return
        }
        socketsToClose.forEach { socket ->
            connectionClientIds.remove(socket)
            runCatching { socket.close() }
        }
        connect()
    }

    /** Close every transport and suppress automatic reconnect until a signed-in reconfigure. */
    suspend fun disconnect() {
        val socketsToClose = mutex.withLock {
            connectionsEnabled = false
            val inFlight = connectionAttempts.invalidate()
            // metadataRefreshJob is registered under this same lock at handshake publication, so a
            // concurrent successful connect cannot hide a freshly-created refresh job from logout.
            metadataRefreshJob?.cancel()
            metadataRefreshJob = null
            pumpJob?.cancel()
            pumpJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            val current = conn
            conn = null
            connecting = false
            forcedReconnectRunning = false
            attachedConnectionFor.clear()
            hydratedConnectionFor.clear()
            _accountIdentity.value = null
            _state.value = ConnectionState.Disconnected
            listOfNotNull(current, inFlight).distinct()
        }
        socketsToClose.forEach { socket ->
            connectionClientIds.remove(socket)
            runCatching { socket.close() }
        }
    }

    /** Remove account-scoped local projections after logout without mutating remote sessions. */
    suspend fun resetForSignOut() {
        disconnect()
        val transcriptIds = buildSet {
            addAll(_sessions.value.map { it.id })
            addAll(stores.keys)
            addAll(daemonIdFor.keys)
            addAll(daemonIdFor.values)
        }
        transcriptIds.forEach { prefs.remove(Prefs.transcriptKey(it)) }
        prefs.remove(Prefs.KEY_SESSIONS)
        prefs.remove(Prefs.KEY_IDMAP)
        prefs.remove(Prefs.KEY_MUTED)

        stores.clear()
        sessionAttachMutexes.clear()
        attachedConnectionFor.clear()
        hydratedConnectionFor.clear()
        daemonIdFor.clear()
        localIdFor.clear()
        agentIdForSession.clear()
        turnStartedAt.clear()
        lastAgentMessage.clear()
        agentStatusTracker.clear()
        permissionDecisionsInFlight.clear()
        elicitationResponsesInFlight.clear()
        messageEditsInFlight.clear()
        synchronized(pendingSends) { pendingSends.clear() }
        synchronized(ledgerInteractionLock) {
            prefs.clearLedgerInboxActions()
            ledgerInteractionQueue.clear()
            _pendingLedgerInteraction.value = null
            // Keep intent tombstones through their original expiry so an old-host replay cannot be
            // accepted after signing into or pairing with a different Core.
        }
        _permissionDecisions.value = emptyMap()
        _sessions.value = emptyList()
        _agents.value = emptyList()
        _daemonProtocol.value = ""
        _daemonModel.value = ""
        _daemonProvider.value = ""
        _accountIdentity.value = null
        currentlyOpenSessionId = null
        notifications?.clearAll()
    }

    // MARK: - id binding

    private fun knownDaemonSession(localId: String): String? {
        val d = daemonIdFor[localId]
        if (d != null) {
            // Only a binding to a REAL daemon session is usable.
            if (d.startsWith("session_") || d.startsWith("conv-")) return d
            unbindDaemonSession(localId)
            return null
        }
        // Daemon-native ids self-map: `session_<uuid>` and `conv-<id>`.
        if (localId.startsWith("session_") || localId.startsWith("conv-")) {
            daemonIdFor[localId] = localId; localIdFor[localId] = localId
            persistIdMap()
            return localId
        }
        return null
    }

    private fun bindDaemonSession(localId: String, daemonId: String) {
        val previousDaemon = daemonIdFor.put(localId, daemonId)
        if (previousDaemon != null && previousDaemon != daemonId) {
            localIdFor.remove(previousDaemon, localId)
        }
        if (localId != daemonId && daemonIdFor[daemonId] == daemonId) {
            daemonIdFor.remove(daemonId)
        }
        val previousLocal = localIdFor.put(daemonId, localId)
        if (previousLocal != null && previousLocal != localId &&
            daemonIdFor[previousLocal] == daemonId
        ) {
            daemonIdFor.remove(previousLocal)
        }
        persistIdMap()
    }

    private fun unbindDaemonSession(localId: String) {
        daemonIdFor.remove(localId)?.let { daemonId -> localIdFor.remove(daemonId, localId) }
        persistIdMap()
    }

    // MARK: - attach / detach

    suspend fun attach(sessionId: String, store: SessionStore) {
        // Register first: if another coroutine is already connecting, the successful handshake's
        // reattach pass can see and hydrate this store instead of leaving an empty chat behind.
        stores[sessionId] = store
        ensureSession(sessionId, store.title)
        coroutineScope {
            // Local decode and socket handshake are independent; whichever produces renderable
            // history first can update the UI without waiting for the other.
            val cachedHistory = async { store.loadPersistedIfNeeded() }
            val socket = async { ensureConnected() }
            cachedHistory.await()
            socket.await()
        }
        val c = conn
        if (c == null) {
            if (store.currentItems().isEmpty()) store.markHistoryFailed()
            return
        }
        attachAndHydrate(sessionId, c)
    }

    /**
     * Attach notification routing and hydrate history once per session/socket generation. Calls
     * from chat-open and reconnect share the same mutex, so they cannot duplicate relay requests.
     */
    private suspend fun attachAndHydrate(localId: String, connection: JsonRpcConnection) {
        if (!connectionsEnabled || conn !== connection) return
        val store = stores[localId] ?: return
        store.loadPersistedIfNeeded()
        val prepared = mutex.withLock {
            if (!connectionsEnabled || conn !== connection || stores[localId] !== store) return
            val daemonId = knownDaemonSession(localId)
            if (daemonId == null) {
                // A never-started app draft is authoritatively empty until its first send creates Core.
                store.markHistoryReady()
                return
            }
            daemonId to sessionAttachMutexes.getOrPut(localId) { Mutex() }
        }
        val (daemonId, sessionMutex) = prepared
        sessionMutex.withLock {
            if (!connectionsEnabled || conn !== connection) return@withLock
            val needsAttach = attachedConnectionFor[localId] !== connection
            val needsHistory = hydratedConnectionFor[localId] !== connection
            if (!needsAttach && !needsHistory) return@withLock
            if (needsHistory) store.markHistoryLoading()

            val (attachResult, historyLoaded) = coroutineScope {
                val attachRequest = if (needsAttach) {
                    async {
                        runCatching {
                            connection.send(
                                "session.attach",
                                jsonObject(
                                    "sessionId" to daemonId.jv(),
                                    "clientId" to clientIdFor(connection).jv(),
                                ),
                            )
                        }
                    }
                } else {
                    null
                }
                val historyRequest = if (needsHistory) {
                    async { loadTranscript(localId, daemonId, connection) }
                } else {
                    null
                }
                attachRequest?.await() to historyRequest?.await()
            }

            mutex.withLock applyResults@{
                if (!connectionsEnabled || conn !== connection) return@applyResults
                if (attachResult?.isSuccess == true) {
                    attachedConnectionFor[localId] = connection
                } else if (attachResult?.isFailure == true) {
                    android.util.Log.w(
                        "DaemonHub",
                        "Couldn't attach session $daemonId",
                        attachResult.exceptionOrNull(),
                    )
                }
                if (historyLoaded == true) {
                    hydratedConnectionFor[localId] = connection
                    store.markHistoryReady()
                } else if (needsHistory) {
                    store.markHistoryFailed()
                }
            }
        }
    }

    private suspend fun loadTranscript(
        localId: String,
        daemonId: String,
        connection: JsonRpcConnection,
    ): Boolean {
        val r = try {
            connection.send("session.transcript", jsonObject("sessionId" to daemonId.jv()))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            android.util.Log.w("DaemonHub", "Couldn't load transcript $daemonId", error)
            return false
        }
        val msgs = (r["messages"]?.arrayValue ?: emptyList()).mapNotNull { m ->
            val role = m["role"]?.stringValue ?: return@mapNotNull null
            val text = m["text"]?.stringValue ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            InitialMessage(role, text)
        }
        // Empty is a valid authoritative result. A unique id also allows a later reconnect to seed
        // a session that was empty on the previous connection.
        mutex.withLock {
            if (!connectionsEnabled || conn !== connection) return false
            stores[localId]?.ingest(
                Event(
                    "transcript:$daemonId:${UUID.randomUUID()}",
                    null,
                    EventMsg.SessionConfigured(msgs),
                ),
            ) ?: return false
        }
        return true
    }

    fun detach(@Suppress("UNUSED_PARAMETER") sessionId: String) {
        // Intentionally retain opened stores for the process lifetime. They are the live transcript
        // cache and notification target; discarding one here caused every reopen to decode/fetch the
        // full history again and made off-screen events disappear. Authoritative delete still clears
        // the store through deleteSession().
    }

    /**
     * Stop/archive the daemon session first, then remove its local projection only after Core
     * acknowledges `status=closed`. A never-started local draft has no daemon id and is removed
     * locally without making up a backend mutation.
     */
    suspend fun terminateSession(sessionId: String): Result<Unit> {
        val daemonId = knownDaemonSession(sessionId)
        if (daemonId != null) {
            ensureConnected()
            val c = conn ?: return Result.failure(
                IllegalStateException("Core is offline. The session wasn't deleted."),
            )
            val result = try {
                // Let an active message.send finish promptly before the serialized terminate call.
                try {
                    c.send(
                        "session.cancelTurn",
                        jsonObject(
                            "sessionId" to daemonId.jv(),
                            "reason" to "session_deleted".jv(),
                        ),
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // No active turn, an older Core, or a transient cancel failure: terminate is
                    // still the authoritative operation and reports its own failure below.
                }
                c.send(
                    "session.terminate",
                    jsonObject(
                        "sessionId" to daemonId.jv(),
                        "reason" to "deleted_from_android".jv(),
                    ),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                return Result.failure(
                    IllegalStateException("Core couldn't delete this session. Try again.", error),
                )
            }
            if (result["sessionId"]?.stringValue != daemonId ||
                result["status"]?.stringValue != "closed"
            ) {
                return Result.failure(
                    IllegalStateException("Core didn't confirm the session was deleted."),
                )
            }
            agentIdForSession.remove(daemonId)
        }

        synchronized(pendingSends) { pendingSends.removeAll { it.sessionId == sessionId } }
        turnStartedAt.remove(sessionId)
        lastAgentMessage.remove(sessionId)
        notifications?.clearAttention(sessionId)
        prefs.mutedSessions = prefs.mutedSessions - sessionId
        deleteSession(sessionId)
        return Result.success(Unit)
    }

    // MARK: - send

    /**
     * Send one user message. [agentText] may include trusted, app-generated routing context while
     * [text] remains the exact text rendered in the transcript. This is used by the mobile
     * `@ledger` bridge so derivation metadata reaches Core without leaking a large control block
     * into the chat UI. Core receives the original text as `metadata.displayUserMessage`.
     */
    fun send(
        text: String,
        imageURLs: List<String> = emptyList(),
        sessionId: String,
        agentText: String = text,
    ) {
        ensureSession(sessionId, "New chat")
        bumpSession(sessionId) { s ->
            val title = if (s.title == "New chat" || s.title.isEmpty()) {
                (if (text.isEmpty()) "Image" else text).take(48)
            } else {
                s.title
            }
            s.copy(
                title = title,
                subtitle = if (text.isEmpty()) "Sent an image" else text,
                updated = "now",
                lifecycle = SessionLifecycle.Running,
            )
        }
        val n = imageURLs.size
        val shown = when {
            n == 0 -> text
            text.isEmpty() -> "🖼 $n image${if (n == 1) "" else "s"}"
            else -> "$text\n🖼 $n image${if (n == 1) "" else "s"}"
        }
        stores[sessionId]?.ingest(
            Event(UUID.randomUUID().toString(), null, EventMsg.UserMessage(shown)),
        )
        dispatchSend(sessionId, text, agentText, imageURLs)
    }

    /**
     * Replace a user prompt using Core's authoritative conversation rewind, then run the revised
     * prompt as a new turn. The local transcript is untouched until Core confirms the rewind.
     */
    fun reviseMessage(
        sessionId: String,
        messageOrdinal: Int,
        messageId: String,
        revisedText: String,
    ) {
        if (revisedText.isBlank() || !messageEditsInFlight.add(sessionId)) return
        val store = stores[sessionId] ?: run {
            messageEditsInFlight.remove(sessionId)
            return
        }
        val originalPrompt = store.currentItems()
            .filterIsInstance<TranscriptItem.User>()
            .firstOrNull { it.msgId == messageId }
            ?.text
        val titleWasGeneratedFromPrompt = originalPrompt != null &&
            _sessions.value.firstOrNull { it.id == sessionId }?.title == originalPrompt.take(48)
        store.markRunning()
        setSessionLifecycle(sessionId, SessionLifecycle.Running)
        scope.launch {
            try {
                val daemonId = knownDaemonSession(sessionId)
                if (daemonId != null) {
                    ensureConnected()
                    val c = conn ?: throw JsonRpcConnection.ConnError.Closed
                    val result = c.send(
                        "session.rewindConversationToMessage",
                        jsonObject(
                            "sessionId" to daemonId.jv(),
                            "messageOrdinal" to messageOrdinal.jv(),
                        ),
                    )
                    if (result["sessionId"]?.stringValue != daemonId ||
                        result["ok"]?.boolValue != true
                    ) {
                        val detail = result["message"]?.stringValue
                            ?: "Core couldn't rewind this message."
                        throw IllegalStateException(detail)
                    }
                }

                if (!store.rewindBeforeUserMessage(messageId, messageOrdinal)) {
                    throw IllegalStateException(
                        "This message changed before the edit completed. Reopen the chat and try again.",
                    )
                }
                val revisedPrompt = revisedText.trim()
                if (titleWasGeneratedFromPrompt) {
                    val revisedTitle = revisedPrompt.take(48)
                    store.title = revisedTitle
                    bumpSession(sessionId) { it.copy(title = revisedTitle) }
                }
                send(text = revisedPrompt, sessionId = sessionId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                android.util.Log.w("DaemonHub", "Couldn't revise message", error)
                reportDeliveryFailure(
                    sessionId,
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "Couldn't edit this message. Try again.",
                )
                if (error is JsonRpcConnection.ConnError.Timeout ||
                    error is JsonRpcConnection.ConnError.Closed
                ) {
                    forceReconnect()
                }
            } finally {
                messageEditsInFlight.remove(sessionId)
            }
        }
    }

    // MARK: - Demo (scripted voice → Ledger transfer flow)

    /**
     * Register the pre-seeded demo chat (a short prior conversation) if it isn't there yet, and
     * return its summary so the caller can open it. Idempotent — safe to call on every launch.
     */
    @Volatile private var demoFreshStart = true

    fun ensureDemoSession(): SessionSummary {
        val summary = _sessions.value.firstOrNull { it.id == ai.tetsuo.agenc.Demo.SESSION_ID }
            ?: SessionSummary(
                id = ai.tetsuo.agenc.Demo.SESSION_ID,
                title = ai.tetsuo.agenc.Demo.SESSION_TITLE,
                lifecycle = SessionLifecycle.Idle,
                cwd = ai.tetsuo.agenc.Demo.SESSION_CWD,
                subtitle = ai.tetsuo.agenc.Demo.SEED.lastOrNull()?.second ?: "",
                updated = "now",
            )
        // Always float the demo chat to the TOP so it's the first thing on camera.
        _sessions.value = listOf(summary) + _sessions.value.filterNot { it.id == summary.id }
        persistSessions()
        val store = storeFor(summary.id, summary.title)
        // Fresh start each launch (clean retakes), and re-seed any time the store is empty.
        if (demoFreshStart || store.currentItems().isEmpty()) {
            demoFreshStart = false
            store.reseed(ai.tetsuo.agenc.Demo.SEED)
        }
        return summary
    }

    /** Show a recorded voice message in the transcript and flip the turn to "thinking". */
    fun postVoice(sessionId: String, seconds: Int, transcript: String) {
        ensureSession(sessionId, "New chat")
        bumpSession(sessionId) {
            it.copy(subtitle = "Voice message", updated = "now", lifecycle = SessionLifecycle.Running)
        }
        stores[sessionId]?.ingest(
            Event(UUID.randomUUID().toString(), null, EventMsg.UserVoice(seconds, transcript)),
        )
        stores[sessionId]?.ingest(
            Event(UUID.randomUUID().toString(), null, EventMsg.TurnStarted("demo")),
        )
    }

    /** Post a local assistant reply and end the turn (demo success message). */
    fun postAgentReply(sessionId: String, text: String) {
        val s = stores[sessionId] ?: return
        s.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.AgentMessage(text)))
        s.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.TurnComplete("demo", text)))
        setSessionLifecycle(sessionId, SessionLifecycle.Idle)
    }

    private fun dispatchSend(
        sessionId: String,
        displayText: String,
        agentText: String,
        images: List<String>,
    ) {
        if (_state.value !is ConnectionState.Connected || conn == null) {
            synchronized(pendingSends) {
                pendingSends.add(PendingSend(sessionId, displayText, agentText, images))
            }
            scope.launch { connect() }
            return
        }
        scope.launch { deliver(sessionId, displayText, agentText, images) }
    }

    private fun imageBlock(url: String): JsonValue = jsonObject(
        "type" to "image_url".jv(),
        "image_url" to jsonObject("url" to url.jv()),
    )

    private fun contentBlocks(text: String, images: List<String>): List<JsonValue> {
        val blocks = mutableListOf<JsonValue>()
        if (text.isNotEmpty()) {
            blocks.add(jsonObject("type" to "text".jv(), "text" to text.jv()))
        }
        blocks.addAll(images.map { imageBlock(it) })
        return blocks
    }

    private fun messageSendParams(
        sessionId: String,
        displayText: String,
        agentText: String,
        images: List<String>,
    ): JsonValue.Obj {
        val params = mutableMapOf<String, JsonValue>(
            "sessionId" to sessionId.jv(),
            "content" to jsonArray(contentBlocks(agentText, images)),
            "clientMessageId" to UUID.randomUUID().toString().jv(),
        )
        if (agentText != displayText) {
            params["metadata"] = jsonObject("displayUserMessage" to displayText.jv())
        }
        return jsonObject(params)
    }

    private suspend fun deliver(
        sessionId: String,
        displayText: String,
        agentText: String,
        images: List<String>,
    ) {
        val c = conn
        if (c == null) {
            // No request was attempted, so this is the one case where redelivery is unambiguous.
            synchronized(pendingSends) {
                pendingSends.add(PendingSend(sessionId, displayText, agentText, images))
            }
            connect()
            return
        }
        // RESUME: a chat already bound to a daemon session continues the SAME agent via message.send.
        val did = knownDaemonSession(sessionId)
        if (did != null) {
            runCatching {
                c.send(
                    "session.attach",
                    jsonObject("sessionId" to did.jv(), "clientId" to clientIdFor(c).jv()),
                )
            }
            val sendResult = runCatching {
                c.send(
                    "message.send",
                    messageSendParams(did, displayText, agentText, images),
                    // Core acknowledges message.send only after the turn finishes. Keeping this
                    // request alive is also what lets ALLOW/DENY travel over the same connection.
                    timeout = null,
                )
            }
            if (sendResult.isSuccess) return
            val sendError = sendResult.exceptionOrNull()
            if (sendError is JsonRpcConnection.ConnError.Timeout ||
                sendError is JsonRpcConnection.ConnError.Closed
            ) {
                // The WebSocket may have accepted the mutation before the response was lost. Keep
                // the binding and reconnect, but do not execute message.send a second time.
                reportDeliveryFailure(
                    sessionId,
                    "couldn't confirm whether the agent received your message; it wasn't retried",
                )
                forceReconnect(c)
                return
            }
            if (sendError !is JsonRpcConnection.ConnError.Rpc) {
                reportDeliveryFailure(sessionId, "couldn't deliver your message")
                return
            }
            if (sendError.dataCode != RPC_AGENT_NOT_FOUND) {
                reportDeliveryFailure(sessionId, "couldn't deliver your message")
                return
            }
            // The daemon explicitly says this session has no live agent — rebind below.
            unbindDaemonSession(sessionId)
            stores[sessionId]?.ingest(
                Event(
                    "resumed:${UUID.randomUUID()}", null,
                    EventMsg.ContextCompacted(
                        summary = "Started a fresh session, the earlier conversation wasn't available on the agent",
                    ),
                ),
            )
        }
        // NEW chat (or a recreate after the bound session vanished).
        val routedMessage = agentText != displayText
        val objective = if (displayText.isEmpty()) "Please look at the attached image." else displayText
        val createParams = mutableMapOf<String, JsonValue>("objective" to objective.jv())
        // Android-created agents run in unattended permission mode. Pre-allow only the typed
        // mobile Ledger hand-off tool; its execution still requires an exact @ledger token in the
        // current root human turn, a Core-generated one-shot intent, Android validation, and final
        // physical approval on the Ledger. Without this narrow allow, the agent pauses on a generic
        // Mac-side permission before the phone can receive its own review request.
        createParams["unattendedAllow"] = jsonArray(
            listOf(REQUEST_LEDGER_TRANSFER_TOOL.jv()),
        )
        // A routed message must go through message.send so Core can show [displayText] while the
        // model receives [agentText]. Create a passive agent first; an empty initialContent prevents
        // agent.create from running the visible objective as turn one.
        if (routedMessage) createParams["initialContent"] = jsonArray(emptyList())
        val cwd = _sessions.value.firstOrNull { it.id == sessionId }?.cwd ?: ""
        if (cwd.isNotEmpty()) createParams["cwd"] = cwd.jv()
        val createResult = runCatching { c.send("agent.create", jsonObject(createParams)) }
        val newDid = createResult.getOrNull()?.get("sessionId")?.stringValue
        if (newDid == null) {
            val createError = createResult.exceptionOrNull()
            if (createError is JsonRpcConnection.ConnError.Timeout ||
                createError is JsonRpcConnection.ConnError.Closed
            ) {
                // agent.create may have committed before its response disappeared. Retrying could
                // create a second agent, so reconnect only and let session.list reveal the outcome.
                reportDeliveryFailure(
                    sessionId,
                    "couldn't confirm whether the agent started; the request wasn't retried",
                )
                forceReconnect(c)
            } else {
                reportDeliveryFailure(
                    sessionId,
                    "couldn't reach the agent, your message wasn't delivered",
                )
            }
            return
        }
        bindDaemonSession(sessionId, newDid)
        runCatching {
            c.send(
                "session.attach",
                jsonObject("sessionId" to newDid.jv(), "clientId" to clientIdFor(c).jv()),
            )
        }
        if (routedMessage) {
            val routedSendResult = runCatching {
                c.send(
                    "message.send",
                    messageSendParams(newDid, displayText, agentText, images),
                    timeout = null,
                )
            }
            if (routedSendResult.isFailure) {
                val routedSendError = routedSendResult.exceptionOrNull()
                val detail = if (routedSendError is JsonRpcConnection.ConnError.Timeout ||
                    routedSendError is JsonRpcConnection.ConnError.Closed
                ) {
                    forceReconnect(c)
                    "couldn't confirm whether the agent received your message; it wasn't retried"
                } else {
                    "the agent started, but your message wasn't delivered"
                }
                reportDeliveryFailure(sessionId, detail)
            }
        } else if (images.isNotEmpty()) {
            val imageSendResult = runCatching {
                c.send(
                    "message.send",
                    jsonObject(
                        "sessionId" to newDid.jv(),
                        "content" to jsonArray(images.map { imageBlock(it) }),
                        "clientMessageId" to UUID.randomUUID().toString().jv(),
                    ),
                    timeout = null,
                )
            }
            if (imageSendResult.isFailure) {
                val imageSendError = imageSendResult.exceptionOrNull()
                val detail = if (imageSendError is JsonRpcConnection.ConnError.Timeout ||
                    imageSendError is JsonRpcConnection.ConnError.Closed
                ) {
                    forceReconnect(c)
                    "couldn't confirm whether the agent received your image; it wasn't retried"
                } else {
                    "the agent started, but the image wasn't delivered"
                }
                reportDeliveryFailure(sessionId, detail)
            }
        }
    }

    private fun reportDeliveryFailure(sessionId: String, message: String) {
        val lifecycle = SessionLifecycle.Error(message)
        stores[sessionId]?.ingest(
            Event(UUID.randomUUID().toString(), null, EventMsg.Error(message)),
        )
        setSessionLifecycle(sessionId, lifecycle)
    }

    /** Tear down the current (possibly half-dead) connection and dial fresh. */
    private fun forceReconnect(source: JsonRpcConnection? = null) {
        scope.launch {
            val socketsToClose = mutex.withLock {
                if (!connectionsEnabled) return@launch
                if (forcedReconnectRunning) return@launch
                if (source != null && conn !== source) return@launch
                forcedReconnectRunning = true
                val current = conn
                val inFlight = connectionAttempts.invalidate()
                conn = null
                connecting = false
                metadataRefreshJob?.cancel()
                metadataRefreshJob = null
                pumpJob?.cancel()
                pumpJob = null
                _state.value = ConnectionState.Failed("reconnecting")
                listOfNotNull(current, inFlight).distinct()
            }
            socketsToClose.forEach { socket ->
                connectionClientIds.remove(socket)
                runCatching { socket.close() }
            }
            try {
                connect()
            } finally {
                mutex.withLock { forcedReconnectRunning = false }
            }
        }
    }

    private fun flushPendingSends() {
        val queued = synchronized(pendingSends) {
            val q = pendingSends.toList(); pendingSends.clear(); q
        }
        for (q in queued) {
            scope.launch { deliver(q.sessionId, q.displayText, q.agentText, q.images) }
        }
    }

    private fun failPendingSends(reason: String) {
        val sids = synchronized(pendingSends) {
            val s = pendingSends.map { it.sessionId }.toSet(); pendingSends.clear(); s
        }
        for (sid in sids) {
            stores[sid]?.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.Error(reason)))
            setSessionLifecycle(sid, SessionLifecycle.Error(reason))
        }
    }

    // MARK: - permission replies

    fun approve(approvalScope: String, sessionId: String, requestId: String) {
        scope.launch { approveAwait(approvalScope, sessionId, requestId) }
    }

    suspend fun approveAwait(
        approvalScope: String,
        sessionId: String,
        requestId: String,
    ): Boolean {
        val action = if (approvalScope == "session") {
            PermissionDecisionAction.AllowSession
        } else {
            PermissionDecisionAction.AllowOnce
        }
        return submitPermissionDecision(requestId, action) {
            val did = daemonIdFor[sessionId] ?: sessionId
            val c = conn ?: throw JsonRpcConnection.ConnError.Closed
            c.send(
                "tool.approve",
                jsonObject(
                    "sessionId" to did.jv(),
                    "requestId" to requestId.jv(),
                    "scope" to approvalScope.jv(),
                    // This explicit protocol flag promotes only this daemon
                    // session to bypassPermissions. A plain scope=session
                    // remains Core's narrower same-rule approval cache.
                    "allowAllToolsForSession" to (approvalScope == "session").jv(),
                ),
            )
            stores[sessionId]?.resolvePermission(requestId)
            notifications?.clearAttention(sessionId, requestId)
        }
    }

    fun deny(sessionId: String, requestId: String) {
        scope.launch { denyAwait(sessionId, requestId) }
    }

    suspend fun denyAwait(sessionId: String, requestId: String): Boolean =
        submitPermissionDecision(requestId, PermissionDecisionAction.Deny) {
            val did = daemonIdFor[sessionId] ?: sessionId
            val c = conn ?: throw JsonRpcConnection.ConnError.Closed
            c.send(
                "tool.deny",
                jsonObject("sessionId" to did.jv(), "requestId" to requestId.jv()),
            )
            stores[sessionId]?.resolvePermission(requestId)
            notifications?.clearAttention(sessionId, requestId)
        }

    private suspend fun submitPermissionDecision(
        requestId: String,
        action: PermissionDecisionAction,
        submit: suspend () -> Unit,
    ): Boolean {
        if (!permissionDecisionsInFlight.add(requestId)) return false
        setPermissionDecision(requestId, PermissionDecisionState.Submitting(action))
        val source = conn
        return try {
            submit()
            setPermissionDecision(requestId, null)
            true
        } catch (error: Throwable) {
            android.util.Log.w("DaemonHub", "Couldn't submit permission decision", error)
            setPermissionDecision(
                requestId,
                PermissionDecisionState.Failed("Couldn't send decision · Try again"),
            )
            handleInteractionFailure(error, source)
            false
        } finally {
            permissionDecisionsInFlight.remove(requestId)
        }
    }

    @Synchronized
    private fun setPermissionDecision(requestId: String, state: PermissionDecisionState?) {
        val next = _permissionDecisions.value.toMutableMap()
        if (state == null) next.remove(requestId) else next[requestId] = state
        _permissionDecisions.value = next
    }

    /** Keep the pending prompt visible unless the daemon acknowledged the decision. */
    private fun handleInteractionFailure(error: Throwable?, source: JsonRpcConnection?) {
        if (error is JsonRpcConnection.ConnError.Timeout ||
            error is JsonRpcConnection.ConnError.Closed
        ) {
            forceReconnect(source)
        }
    }

    // MARK: - notification pump

    private fun startPump(c: JsonRpcConnection) {
        pumpJob = scope.launch {
            c.notifications.collect { note ->
                if (note.method == "\$transport.closed") {
                    onPumpClosed(c)
                    return@collect
                }
                if (note.method == "event.agent_status") {
                    handleAgentStatus(note.params)
                    return@collect
                }
                val mapped = PortalEventAdapter.map(note) ?: return@collect
                val local = localIdFor[mapped.sessionId] ?: mapped.sessionId
                val ledgerRequest = (mapped.msg as? EventMsg.RequestUserInput)
                    ?.takeIf { it.pending.clientAction != null }
                if (ledgerRequest != null) {
                    enqueueLedgerInteraction(
                        PendingLedgerInteraction(
                            sessionId = local,
                            elicitation = ledgerRequest.pending,
                            hostBinding = currentLedgerHostBinding(),
                        ),
                    )
                }
                // Dedup replays by composite method+eventId so tool_request/permission_request stay distinct.
                val eid = note.params["eventId"]?.stringValue?.let { "${note.method}:$it" }
                    ?: UUID.randomUUID().toString()
                // Typed Ledger requests are rendered by the global signing overlay. Never also put
                // them in the generic chat input sheet, which could answer/bypass hardware review.
                if (ledgerRequest == null) {
                    stores[local]?.ingest(Event(eid, null, mapped.msg))
                }
                trackTurnTiming(local, mapped.msg)
                val store = stores[local]
                if (store != null) {
                    setSessionLifecycle(local, store.lifecycle.value)
                } else {
                    listLifecycleHint(mapped.msg)?.let { setSessionLifecycle(local, it) }
                }
                notifyIfBackgrounded(local, mapped.msg)
            }
        }
    }

    private fun handleAgentStatus(p: JsonValue) {
        val dsid = p["sessionId"]?.stringValue ?: return
        val eventId = p["eventId"]?.stringValue
        if (!agentStatusTracker.acceptEvent(dsid, eventId)) return
        val local = localIdFor[dsid] ?: dsid
        val wasUnknown = _sessions.value.none { it.id == local }
        if (wasUnknown) {
            ensureSession(local, p["objective"]?.stringValue?.take(60) ?: "Agent session")
            scheduleStatusSessionRefresh()
        }
        updateAgentStatus(p)
        val status = p["status"]?.stringValue ?: ""
        val runStatus = p["runStatus"]?.stringValue ?: ""
        val turnId = p["turnId"]?.stringValue?.takeIf { it.isNotBlank() }
        if (status == "error" || runStatus == "errored") {
            val m = p["message"]?.stringValue ?: "Agent error"
            val err = EventMsg.Error(m)
            stores[local]?.ingest(Event(eventId ?: UUID.randomUUID().toString(), null, err))
            setSessionLifecycle(local, SessionLifecycle.Error(m))
            turnStartedAt.remove(local)
            agentStatusTracker.clearRunning(local)
            notifyIfBackgrounded(local, err)
        } else if (
            (status in STOPPED_STATUSES || runStatus in STOPPED_RUN_STATUSES) &&
            status !in IDLE_STATUSES && runStatus !in IDLE_RUN_STATUSES
        ) {
            stores[local]?.markStopped()
            setSessionLifecycle(local, SessionLifecycle.Idle)
            turnStartedAt.remove(local)
            lastAgentMessage.remove(local)
            agentStatusTracker.clearRunning(local)
        } else if (status in IDLE_STATUSES || runStatus in IDLE_RUN_STATUSES) {
            // Core can replay a terminal status without its earlier running frame. A nonblank turnId
            // is authoritative, while the tracker suppresses the later progress/run-complete frames
            // Core may emit for that same turn even when they carry different eventIds.
            val s = stores[local]
            val wasInFlight = s?.lifecycle?.value?.isInFlight == true ||
                _sessions.value.firstOrNull { it.id == local }?.lifecycle?.isInFlight == true ||
                turnStartedAt.containsKey(local)
            if (agentStatusTracker.shouldComplete(local, turnId, wasInFlight)) {
                val complete = EventMsg.TurnComplete(
                    turnId = turnId.orEmpty(),
                    last = p["message"]?.stringValue?.takeIf { it.isNotBlank() },
                )
                s?.ingest(Event(eventId ?: UUID.randomUUID().toString(), null, complete))
                setSessionLifecycle(local, SessionLifecycle.Done)
                notifyIfBackgrounded(local, complete)
            }
        } else if (status in RUNNING_STATUSES || runStatus in RUNNING_RUN_STATUSES) {
            // Some Core runs expose only agent_status transitions (no replayed turn_started). Keep a
            // timestamp so their later idle/done transition is still an authoritative completion.
            if (!agentStatusTracker.markRunning(local, turnId)) return
            turnStartedAt.putIfAbsent(local, System.currentTimeMillis())
            stores[local]?.markRunning()
            setSessionLifecycle(local, SessionLifecycle.Running)
        }
    }

    private fun scheduleStatusSessionRefresh() {
        if (statusSessionRefreshJob?.isActive == true) return
        statusSessionRefreshJob = scope.launch {
            try {
                refreshSessions()
            } finally {
                statusSessionRefreshJob = null
            }
        }
    }

    private suspend fun onPumpClosed(source: JsonRpcConnection) {
        val wasCurrent = mutex.withLock {
            if (conn !== source) return@withLock false
            conn = null
            connecting = false
            _accountIdentity.value = null
            _state.value = ConnectionState.Failed("disconnected")
            if (connectionsEnabled) scheduleReconnect()
            true
        }
        connectionClientIds.remove(source)
        if (!wasCurrent) return
    }

    private fun scheduleReconnect() {
        if (!connectionsEnabled || reconnectJob != null) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && connectionsEnabled) {
                val delaySeconds = if (attempt < 6) minOf(8, 1 shl attempt).toLong() else 30L
                delay(delaySeconds * 1000L)
                connect()
                if (_state.value is ConnectionState.Connected) {
                    break
                }
                attempt += 1
                if (attempt == 6) {
                    failPendingSends("couldn't reach the agent, your message wasn't delivered")
                    failInFlightTurns("connection lost, reconnecting")
                }
            }
            reconnectJob = null
        }
    }

    private fun failInFlightTurns(reason: String) {
        for (store in stores.values.toList()) {
            if (store.lifecycle.value.isInFlight) {
                store.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.Error(reason)))
            }
        }
    }

    /** Reconnect immediately (e.g. when returning to the foreground) and re-attach open sessions. */
    suspend fun ensureConnected() {
        if (!connectionsEnabled || conn != null) return
        connect()
        // connect() returns early when another caller owns the in-flight handshake. Wait for that
        // owner to publish the socket (or finish its failed attempt) before an attach gives up.
        while (connectionsEnabled && conn == null && connecting) delay(16)
    }

    private fun listLifecycleHint(msg: EventMsg): SessionLifecycle? = when (msg) {
        is EventMsg.TurnStarted, is EventMsg.UserMessage,
        is EventMsg.AgentMessageDelta, is EventMsg.ThinkingDelta,
        is EventMsg.AgentMessage, is EventMsg.ToolCallStarted, is EventMsg.ToolProgress,
        is EventMsg.ToolCallCompleted -> SessionLifecycle.Running
        is EventMsg.RequestPermissions -> SessionLifecycle.AwaitingPermission
        is EventMsg.RequestUserInput -> SessionLifecycle.AwaitingInput
        is EventMsg.TurnComplete -> SessionLifecycle.Done
        is EventMsg.TurnAborted -> SessionLifecycle.Idle
        else -> null
    }

    private fun updateAgentStatus(p: JsonValue) {
        val id = p["agentId"]?.stringValue ?: return
        val list = _agents.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(
            status = p["status"]?.stringValue ?: list[i].status,
            needsAttention = p["needsAttention"]?.boolValue ?: list[i].needsAttention,
        )
        _agents.value = list
    }

    // MARK: - projections

    private fun agentSummary(v: JsonValue): AgentSummary? {
        val id = v["agentId"]?.stringValue ?: return null
        return AgentSummary(
            id = id,
            objective = v["objective"]?.stringValue ?: "",
            status = v["status"]?.stringValue ?: "",
            needsAttention = v["needsAttention"]?.boolValue ?: false,
        )
    }

    private fun sessionSummary(v: JsonValue): SessionSummary? {
        val id = v["sessionId"]?.stringValue ?: return null
        val objective = v["metadata"]?.get("objective")?.stringValue
            ?: v["title"]?.stringValue ?: ""
        val cwd = v["cwd"]?.stringValue ?: ""
        val title = if (objective.isNotEmpty()) {
            objective.take(60)
        } else {
            cwd.split("/").lastOrNull { it.isNotEmpty() } ?: id
        }
        return SessionSummary(
            id = id,
            title = title,
            lifecycle = lifecycleFrom(v["status"]?.stringValue ?: "idle"),
            cwd = cwd,
            subtitle = if (objective.isEmpty()) cwd else objective.take(80),
            updated = v["updated"]?.stringValue ?: v["createdAt"]?.stringValue ?: "",
        )
    }

    private fun lifecycleFrom(s: String): SessionLifecycle = when (s) {
        "running", "working" -> SessionLifecycle.Running
        "awaitingPermission" -> SessionLifecycle.AwaitingPermission
        "awaitingInput" -> SessionLifecycle.AwaitingInput
        "done" -> SessionLifecycle.Done
        "error" -> SessionLifecycle.Error("error")
        else -> SessionLifecycle.Idle
    }

    // MARK: - Notification intelligence (Android port of iOS DaemonHub notify methods)

    /**
     * Stamp a session's turn-start time on its first activity, capture the agent's last message for
     * the turn-done preview, and clear the start time at completion. Drives the turn-done threshold
     * and the "· ran 2m" body suffix. Independent of the notification gate so timing is always accurate.
     */
    private fun trackTurnTiming(local: String, msg: EventMsg) {
        when (msg) {
            is EventMsg.TurnStarted, is EventMsg.UserMessage -> {
                turnStartedAt[local] = System.currentTimeMillis()
                lastAgentMessage.remove(local)
            }
            is EventMsg.AgentMessage -> if (msg.text.isNotEmpty()) lastAgentMessage[local] = msg.text
            is EventMsg.AgentMessageDelta ->
                lastAgentMessage[local] =
                    if (msg.resetBuffer) msg.delta else (lastAgentMessage[local] ?: "") + msg.delta
            is EventMsg.TurnAborted, is EventMsg.Error -> turnStartedAt.remove(local)
            else -> Unit
        }
        // The very first activity of a turn might be a tool/thinking event before any turnStarted —
        // stamp a start so a duration still exists at completion.
        if (turnStartedAt[local] == null &&
            (msg is EventMsg.ToolCallStarted || msg is EventMsg.ThinkingDelta)
        ) {
            turnStartedAt[local] = System.currentTimeMillis()
        }
    }

    /** Elapsed seconds for this session's current turn, or null if no start was recorded. */
    private fun turnDuration(local: String): Int? {
        val start = turnStartedAt[local] ?: return null
        return maxOf(0, Math.round((System.currentTimeMillis() - start) / 1000.0).toInt())
    }

    /** "· ran 2m" / "· ran 45s" suffix for a turn-done body, or "" when there's no duration. */
    private fun durationSuffix(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return ""
        if (seconds < 60) return " · ran ${seconds}s"
        return " · ran ${seconds / 60}m"
    }

    private fun sessionTitle(local: String): String =
        _sessions.value.firstOrNull { it.id == local }?.title ?: "AgenC"

    /** The app is backgrounded once its only Activity leaves STARTED. */
    private fun isBackgrounded(): Boolean = !appInForeground

    /**
     * Fire a local notification for events the user cares about WHILE BACKGROUNDED, each gated by its
     * own toggle and an intelligence layer (per-session mute, an optional turn-done threshold, and
     * quiet hours). A completion synthesized from `agent_status` idle follows this same path.
     */
    private fun notifyIfBackgrounded(local: String, msg: EventMsg) {
        // Completion always consumes timing state, even when this notification class is disabled
        // (or this build has no coordinator). Otherwise a later turn inherits stale duration.
        if (msg is EventMsg.TurnComplete && (notifications == null || !prefs.notifyTurnDone)) {
            turnStartedAt.remove(local)
            return
        }
        val nc = notifications ?: return
        if (!isBackgrounded()) {
            // Still consume a completed turn's start time so a later foreground turn re-stamps cleanly.
            if (msg is EventMsg.TurnComplete) turnStartedAt.remove(local)
            return
        }

        // ChatHost remains composed when the Activity goes to the background, so an "open session"
        // marker is not a visibility signal. At this point the Activity is known to be backgrounded;
        // only an explicit per-session mute may suppress delivery.
        if (prefs.mutedSessions.contains(local)) {
            if (msg is EventMsg.TurnComplete) turnStartedAt.remove(local)
            return
        }

        val title = sessionTitle(local)
        when (msg) {
            is EventMsg.RequestPermissions -> if (prefs.notifyAttention) {
                // Needs-you — the turn is blocked on a decision (high-importance channel).
                nc.post(
                    title = "Approval needed · $title",
                    body = msg.pending.title,
                    sessionId = local,
                    requestId = msg.pending.id,
                    kind = NotificationCoordinator.Kind.PERMISSION,
                )
            }
            is EventMsg.RequestUserInput -> if (prefs.notifyAttention) {
                val isLedger = msg.pending.clientAction != null
                nc.post(
                    title = if (isLedger) "Ledger approval needed · $title" else "Input needed · $title",
                    body = if (isLedger) {
                        "Open AgenC to review the Solana transfer on your Ledger."
                    } else {
                        msg.pending.question
                    },
                    sessionId = local,
                    requestId = msg.pending.id,
                    questionId = msg.pending.questionId,
                    kind = if (isLedger) {
                        NotificationCoordinator.Kind.LEDGER
                    } else {
                        NotificationCoordinator.Kind.INPUT
                    },
                )
            }
            is EventMsg.TurnComplete -> {
                val duration = turnDuration(local)
                turnStartedAt.remove(local) // consume the start time for this turn
                if (!NotificationDeliveryPolicy.shouldPostCompletion(
                        backgrounded = true,
                        enabled = prefs.notifyTurnDone,
                        muted = false,
                        quietHours = prefs.isWithinQuietHours(),
                        durationSeconds = duration,
                        minimumDurationSeconds = prefs.turnDoneThresholdSeconds,
                    )
                ) {
                    return
                }
                val preview = (msg.last?.takeIf { it.isNotEmpty() } ?: lastAgentMessage[local])
                    ?: "Your agent finished a turn."
                val body = preview.take(140) + durationSuffix(duration)
                nc.post(
                    title = "Turn complete · $title",
                    body = body,
                    sessionId = local,
                    requestId = null,
                    kind = NotificationCoordinator.Kind.TURN_DONE,
                )
            }
            is EventMsg.Error -> if (prefs.notifyErrors) {
                turnStartedAt.remove(local)
                nc.post(
                    title = "Agent error · $title",
                    body = msg.message,
                    sessionId = local,
                    requestId = null,
                    kind = NotificationCoordinator.Kind.ERROR,
                )
            }
            else -> Unit
        }
    }

    companion object {
        private const val SESSIONS_KEY = Prefs.KEY_SESSIONS
        private val IDLE_STATUSES =
            setOf("idle", "done", "completed", "ready", "finished")
        private val IDLE_RUN_STATUSES = setOf("completed", "done", "idle")
        private val RUNNING_STATUSES = setOf("running", "working")
        private val RUNNING_RUN_STATUSES = setOf("running", "working")
        private val STOPPED_STATUSES = setOf("stopped")
        private val STOPPED_RUN_STATUSES = setOf("stopped")
        private const val RPC_AGENT_NOT_FOUND = "AGENT_NOT_FOUND"
        private const val REQUEST_LEDGER_TRANSFER_TOOL = "request_ledger_transfer"
        private const val MAX_PENDING_LEDGER_INTERACTIONS = 8
        private const val LOCAL_LEDGER_HOST_BINDING = "local-daemon"

        private val sessionsSerializer =
            ListSerializer(MapSerializer(String.serializer(), String.serializer()))
    }
}
