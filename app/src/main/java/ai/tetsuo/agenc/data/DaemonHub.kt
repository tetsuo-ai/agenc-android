package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.AgentSummary
import ai.tetsuo.agenc.model.Event
import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.model.InitialMessage
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.net.JsonRpcConnection
import ai.tetsuo.agenc.net.JsonValue
import ai.tetsuo.agenc.net.PortalEventAdapter
import ai.tetsuo.agenc.net.jsonArray
import ai.tetsuo.agenc.net.jsonObject
import ai.tetsuo.agenc.net.jv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Default),
) {
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

    private var conn: JsonRpcConnection? = null
    private var pumpJob: Job? = null
    private var reconnectJob: Job? = null
    private val mutex = Mutex()

    private val stores = mutableMapOf<String, SessionStore>()

    /** Local UI session id ↔ daemon `session_<uuid>`. */
    private var daemonIdFor = mutableMapOf<String, String>()
    private var localIdFor = mutableMapOf<String, String>()
    private val idMapStore = SessionIdMap(prefs)

    /** daemon sessionId -> agentId (from session.list), used to load a session's history. */
    private val agentIdForSession = mutableMapOf<String, String>()

    /** Messages typed while disconnected — flushed on (re)connect so nothing is dropped. */
    private val pendingSends = mutableListOf<PendingSend>()

    private data class PendingSend(val sessionId: String, val text: String, val images: List<String>)

    /** Stable per-connection client id; the daemon keys event delivery on it. */
    private val clientId = "agenc-android-" + UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadSessions()
        loadIdMap()
    }

    /** Get-or-create the [SessionStore] for a local session id. */
    fun storeFor(sessionId: String, title: String): SessionStore =
        stores.getOrPut(sessionId) { SessionStore(sessionId, title, prefs) }

    // MARK: - id map persistence

    private fun loadIdMap() {
        daemonIdFor = idMapStore.load()
        localIdFor = SessionIdMap.inverse(daemonIdFor)
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
        mutex.withLock { if (conn != null) return }
        _state.value = ConnectionState.Connecting
        val c = JsonRpcConnection(endpoint, relayMode = relayMode)
        mutex.withLock { conn = c }
        c.connect()
        try {
            val initResult = c.initializeHandshake(clientName = "agenc-portal", authCookie = cookie)
            _daemonProtocol.value =
                initResult["protocol"]?.get("version")?.stringValue ?: "1.0.0"
            _state.value = ConnectionState.Connected
            startPump(c)
            flushPendingSends()
            refreshDaemonInfo()
            refreshSessions()
            refreshAgents()
        } catch (e: Throwable) {
            _state.value = ConnectionState.Failed("can't reach your computer")
            mutex.withLock { conn = null }
        }
    }

    suspend fun refreshSessions() {
        val c = conn ?: return
        val result = runCatching { c.send("session.list", null) }.getOrNull() ?: return
        val raw = result["sessions"]?.arrayValue ?: emptyList()
        for (s in raw) {
            val sid = s["sessionId"]?.stringValue
            val aid = s["agentId"]?.stringValue
            if (sid != null && aid != null) agentIdForSession[sid] = aid
        }
        val remote = raw.mapNotNull { sessionSummary(it) }
        var bound = false
        val list = _sessions.value.toMutableList()
        for (r in remote) {
            if (daemonIdFor[r.id] == null) {
                daemonIdFor[r.id] = r.id; localIdFor[r.id] = r.id; bound = true
            }
            if (list.none { it.id == r.id }) list.add(r)
        }
        if (bound) persistIdMap()
        _sessions.value = list
        persistSessions()
    }

    suspend fun refreshAgents() {
        val c = conn ?: return
        val result = runCatching { c.send("agent.list", null) }.getOrNull() ?: return
        _agents.value = (result["agents"]?.arrayValue ?: emptyList()).mapNotNull { agentSummary(it) }
    }

    /** Daemon identity via auth.whoami (real app-server method). */
    suspend fun refreshDaemonInfo() {
        val c = conn ?: return
        val r = runCatching { c.send("auth.whoami", null) }.getOrNull() ?: return
        _daemonModel.value = r["model"]?.stringValue
            ?: r["identity"]?.get("model")?.stringValue ?: _daemonModel.value
        _daemonProvider.value = r["provider"]?.stringValue
            ?: r["identity"]?.get("provider")?.stringValue ?: _daemonProvider.value
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
        stores[sessionId]?.respond(answer)
        scope.launch {
            val did = daemonIdFor[sessionId] ?: sessionId
            runCatching {
                conn?.send(
                    "elicitation.respond",
                    jsonObject(
                        "sessionId" to did.jv(),
                        "requestId" to requestId.jv(),
                        "kind" to "request_user_input".jv(),
                        "response" to jsonObject(
                            "answers" to jsonObject(
                                questionId to jsonObject(
                                    "answers" to jsonArray(answer.jv()),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    /** Switch host: tear down and reconnect to the new endpoint (and transport mode). */
    suspend fun reconfigure(url: String, relay: Boolean = false) {
        if (url == endpoint && relay == relayMode) return
        endpoint = url
        relayMode = relay
        pumpJob?.cancel()
        reconnectJob?.cancel(); reconnectJob = null
        conn?.close()
        mutex.withLock { conn = null }
        _sessions.value = emptyList(); _agents.value = emptyList()
        _state.value = ConnectionState.Disconnected
        connect()
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
        daemonIdFor[localId] = daemonId; localIdFor[daemonId] = localId
        persistIdMap()
    }

    private fun unbindDaemonSession(localId: String) {
        daemonIdFor.remove(localId)?.let { localIdFor.remove(it) }
        persistIdMap()
    }

    // MARK: - attach / detach

    suspend fun attach(sessionId: String, store: SessionStore) {
        connect()
        stores[sessionId] = store
        ensureSession(sessionId, store.title)
        val did = knownDaemonSession(sessionId)
        if (did != null) {
            runCatching {
                conn?.send(
                    "session.attach",
                    jsonObject("sessionId" to did.jv(), "clientId" to clientId.jv()),
                )
            }
            loadTranscript(sessionId, did)
        }
    }

    private suspend fun loadTranscript(localId: String, daemonId: String) {
        if (stores[localId]?.currentItems()?.isEmpty() != true) return
        val r = runCatching {
            conn?.send("session.transcript", jsonObject("sessionId" to daemonId.jv()))
        }.getOrNull() ?: return
        val msgs = (r["messages"]?.arrayValue ?: emptyList()).mapNotNull { m ->
            val role = m["role"]?.stringValue ?: return@mapNotNull null
            val text = m["text"]?.stringValue ?: return@mapNotNull null
            if (text.isEmpty()) return@mapNotNull null
            InitialMessage(role, text)
        }
        if (msgs.isNotEmpty()) {
            stores[localId]?.ingest(
                Event("transcript:$daemonId", null, EventMsg.SessionConfigured(msgs)),
            )
        } else if (!localId.startsWith("s-") && stores[localId]?.currentItems()?.isEmpty() == true) {
            stores[localId]?.ingest(
                Event(
                    "joined:$daemonId", null,
                    EventMsg.ContextCompacted(summary = "Joined a live session"),
                ),
            )
        }
    }

    fun detach(sessionId: String) {
        stores.remove(sessionId)
    }

    // MARK: - send

    fun send(text: String, imageURLs: List<String> = emptyList(), sessionId: String) {
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
        dispatchSend(sessionId, text, imageURLs)
    }

    private fun dispatchSend(sessionId: String, text: String, images: List<String>) {
        if (_state.value !is ConnectionState.Connected || conn == null) {
            pendingSends.add(PendingSend(sessionId, text, images))
            scope.launch { connect() }
            return
        }
        scope.launch { deliver(sessionId, text, images) }
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

    private suspend fun deliver(sessionId: String, text: String, images: List<String>) {
        val c = conn ?: return
        // RESUME: a chat already bound to a daemon session continues the SAME agent via message.send.
        val did = knownDaemonSession(sessionId)
        if (did != null) {
            runCatching {
                c.send(
                    "session.attach",
                    jsonObject("sessionId" to did.jv(), "clientId" to clientId.jv()),
                )
            }
            val ok = runCatching {
                c.send(
                    "message.send",
                    jsonObject(
                        "sessionId" to did.jv(),
                        "content" to jsonArray(contentBlocks(text, images)),
                        "clientMessageId" to UUID.randomUUID().toString().jv(),
                    ),
                )
            }.isSuccess
            if (ok) return
            // The bound daemon session is gone (e.g. AGENT_NOT_FOUND) — drop the binding, recreate below.
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
        val objective = if (text.isEmpty()) "Please look at the attached image." else text
        val createParams = mutableMapOf<String, JsonValue>("objective" to objective.jv())
        val cwd = _sessions.value.firstOrNull { it.id == sessionId }?.cwd ?: ""
        if (cwd.isNotEmpty()) createParams["cwd"] = cwd.jv()
        val r = runCatching { c.send("agent.create", jsonObject(createParams)) }.getOrNull()
        val newDid = r?.get("sessionId")?.stringValue
        if (newDid == null) {
            stores[sessionId]?.ingest(
                Event(
                    UUID.randomUUID().toString(), null,
                    EventMsg.Error("couldn't reach the agent, your message wasn't delivered"),
                ),
            )
            return
        }
        bindDaemonSession(sessionId, newDid)
        runCatching {
            c.send("session.attach", jsonObject("sessionId" to newDid.jv(), "clientId" to clientId.jv()))
        }
        if (images.isNotEmpty()) {
            runCatching {
                c.send(
                    "message.send",
                    jsonObject(
                        "sessionId" to newDid.jv(),
                        "content" to jsonArray(images.map { imageBlock(it) }),
                        "clientMessageId" to UUID.randomUUID().toString().jv(),
                    ),
                )
            }
        }
    }

    private fun flushPendingSends() {
        if (pendingSends.isEmpty()) return
        val queued = pendingSends.toList()
        pendingSends.clear()
        for (q in queued) scope.launch { deliver(q.sessionId, q.text, q.images) }
    }

    private fun failPendingSends(reason: String) {
        val sids = pendingSends.map { it.sessionId }.toSet()
        pendingSends.clear()
        for (sid in sids) {
            stores[sid]?.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.Error(reason)))
        }
    }

    // MARK: - permission replies

    fun approve(scope: String, sessionId: String, requestId: String) {
        stores[sessionId]?.approve(scope)
        this.scope.launch {
            val did = daemonIdFor[sessionId] ?: sessionId
            runCatching {
                conn?.send(
                    "tool.approve",
                    jsonObject(
                        "sessionId" to did.jv(), "requestId" to requestId.jv(), "scope" to scope.jv(),
                    ),
                )
            }
            if (scope == "session") {
                runCatching {
                    conn?.send(
                        "session.setPermissionMode",
                        jsonObject("sessionId" to did.jv(), "mode" to "bypassPermissions".jv()),
                    )
                }
            }
        }
    }

    fun deny(sessionId: String, requestId: String) {
        stores[sessionId]?.clearPendingPermission()
        scope.launch {
            val did = daemonIdFor[sessionId] ?: sessionId
            runCatching {
                conn?.send(
                    "tool.deny",
                    jsonObject("sessionId" to did.jv(), "requestId" to requestId.jv()),
                )
            }
        }
    }

    // MARK: - notification pump

    private fun startPump(c: JsonRpcConnection) {
        pumpJob = scope.launch {
            c.notifications.collect { note ->
                if (note.method == "\$transport.closed") {
                    onPumpClosed()
                    return@collect
                }
                if (note.method == "event.agent_status") {
                    handleAgentStatus(note.params)
                    return@collect
                }
                val mapped = PortalEventAdapter.map(note) ?: return@collect
                val local = localIdFor[mapped.sessionId] ?: mapped.sessionId
                // Dedup replays by composite method+eventId so tool_request/permission_request stay distinct.
                val eid = note.params["eventId"]?.stringValue?.let { "${note.method}:$it" }
                    ?: UUID.randomUUID().toString()
                stores[local]?.ingest(Event(eid, null, mapped.msg))
                val store = stores[local]
                if (store != null) {
                    setSessionLifecycle(local, store.lifecycle.value)
                } else {
                    listLifecycleHint(mapped.msg)?.let { setSessionLifecycle(local, it) }
                }
            }
        }
    }

    private fun handleAgentStatus(p: JsonValue) {
        updateAgentStatus(p)
        val dsid = p["sessionId"]?.stringValue ?: return
        val local = localIdFor[dsid] ?: dsid
        val status = p["status"]?.stringValue ?: ""
        val runStatus = p["runStatus"]?.stringValue ?: ""
        if (status == "error" || runStatus == "errored") {
            val m = p["message"]?.stringValue ?: "Agent error"
            stores[local]?.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.Error(m)))
            setSessionLifecycle(local, SessionLifecycle.Idle)
        } else if (status in IDLE_STATUSES || runStatus in IDLE_RUN_STATUSES) {
            // The agent went idle → the turn finished. The daemon doesn't always emit turn_complete,
            // so flip the OPEN chat out of "working" here.
            val s = stores[local]
            if (s != null && s.lifecycle.value.isInFlight) {
                s.ingest(
                    Event(
                        UUID.randomUUID().toString(), null,
                        EventMsg.TurnComplete(turnId = "", last = null),
                    ),
                )
            }
            setSessionLifecycle(local, stores[local]?.lifecycle?.value ?: SessionLifecycle.Idle)
        }
    }

    private suspend fun onPumpClosed() {
        _state.value = ConnectionState.Failed("disconnected")
        mutex.withLock { conn = null }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val delaySeconds = if (attempt < 6) minOf(8, 1 shl attempt).toLong() else 30L
                delay(delaySeconds * 1000L)
                connect()
                if (_state.value is ConnectionState.Connected) {
                    for (localId in stores.keys.toList()) {
                        val did = daemonIdFor[localId] ?: localId
                        runCatching {
                            conn?.send(
                                "session.attach",
                                jsonObject("sessionId" to did.jv(), "clientId" to clientId.jv()),
                            )
                        }
                    }
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
        for ((_, store) in stores) {
            if (store.lifecycle.value.isInFlight) {
                store.ingest(Event(UUID.randomUUID().toString(), null, EventMsg.Error(reason)))
            }
        }
    }

    /** Reconnect immediately (e.g. when returning to the foreground) and re-attach open sessions. */
    suspend fun ensureConnected() {
        if (conn != null) return
        connect()
        if (_state.value !is ConnectionState.Connected) return
        for (localId in stores.keys.toList()) {
            val did = daemonIdFor[localId] ?: localId
            runCatching {
                conn?.send(
                    "session.attach",
                    jsonObject("sessionId" to did.jv(), "clientId" to clientId.jv()),
                )
            }
        }
    }

    private fun listLifecycleHint(msg: EventMsg): SessionLifecycle? = when (msg) {
        is EventMsg.TurnStarted, is EventMsg.AgentMessageDelta, is EventMsg.ThinkingDelta,
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

    companion object {
        private const val SESSIONS_KEY = Prefs.KEY_SESSIONS
        private val IDLE_STATUSES =
            setOf("idle", "done", "completed", "ready", "finished", "stopped")
        private val IDLE_RUN_STATUSES = setOf("completed", "done", "idle")

        private val sessionsSerializer =
            ListSerializer(MapSerializer(String.serializer(), String.serializer()))
    }
}
