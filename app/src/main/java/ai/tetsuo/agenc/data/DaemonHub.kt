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
import ai.tetsuo.agenc.notif.NotificationCoordinator
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    // Set true while a connect() is mid-handshake so a second caller can't open a parallel socket.
    private var connecting = false

    // Thread-safe: these maps are read/written from BOTH the notification pump (Dispatchers.Default)
    // and UI-thread callbacks (send/approve/attach). ConcurrentHashMap prevents the get/put races and
    // ConcurrentModificationExceptions the plain HashMaps caused during live sessions + reconnects.
    private val stores = ConcurrentHashMap<String, SessionStore>()

    // MARK: - Notification intelligence (Android port of iOS DaemonHub notify state)

    /**
     * The session the user has open RIGHT NOW. `notifyIfBackgrounded` never fires for it (foreground-
     * session suppression), and the chat clears its badge. Set/cleared from the ChatHost.
     */
    @Volatile var currentlyOpenSessionId: String? = null

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
        val text: String,
        val images: List<String>,
        val attempts: Int = 0,
    )

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
        // Atomic check-and-claim: only one caller proceeds; the rest see conn!=null or connecting and
        // bail, so we never open two sockets / two pumps for the same hub.
        mutex.withLock {
            if (conn != null || connecting) return
            connecting = true
        }
        _state.value = ConnectionState.Connecting
        val c = JsonRpcConnection(endpoint, relayMode = relayMode)
        try {
            c.connect()
            val initResult = c.initializeHandshake(clientName = "agenc-portal", authCookie = cookie)
            _daemonProtocol.value =
                initResult["protocol"]?.get("version")?.stringValue ?: "1.0.0"
            // Publish the live connection only once the handshake actually succeeded.
            mutex.withLock { conn = c; connecting = false }
            _state.value = ConnectionState.Connected
            startPump(c)
            flushPendingSends()
            refreshDaemonInfo()
            refreshSessions()
            refreshAgents()
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { c.close() }
            mutex.withLock { conn = null; connecting = false }
            throw e // never swallow cancellation — the caller's scope is going down
        } catch (e: Throwable) {
            _state.value = ConnectionState.Failed("can't reach your computer")
            runCatching { c.close() }
            mutex.withLock { conn = null; connecting = false }
            // Self-heal: without this, a failed connect only retries on the NEXT user action
            // (the reconnect loop starts from onPumpClosed, which needs a pump to have existed).
            scheduleReconnect()
        }
    }

    suspend fun refreshSessions() {
        val c = conn ?: return
        val result = runCatching { c.send("session.list", jsonObject()) }.getOrNull() ?: return
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
        val result = runCatching { c.send("agent.list", jsonObject()) }.getOrNull() ?: return
        _agents.value = (result["agents"]?.arrayValue ?: emptyList()).mapNotNull { agentSummary(it) }
    }

    /** Daemon identity via auth.whoami (real app-server method). */
    suspend fun refreshDaemonInfo() {
        val c = conn ?: return
        val r = runCatching { c.send("auth.whoami", jsonObject()) }.getOrNull() ?: return
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
        mutex.withLock { conn = null; connecting = false }
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
        // Keep the pre-seeded demo chat resident so its messages survive navigating away + back.
        if (ai.tetsuo.agenc.Demo.ENABLED && sessionId == ai.tetsuo.agenc.Demo.SESSION_ID) return
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

    private fun dispatchSend(sessionId: String, text: String, images: List<String>) {
        if (_state.value !is ConnectionState.Connected || conn == null) {
            synchronized(pendingSends) { pendingSends.add(PendingSend(sessionId, text, images)) }
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

    private suspend fun deliver(sessionId: String, text: String, images: List<String>, attempts: Int = 0) {
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
            val sendResult = runCatching {
                c.send(
                    "message.send",
                    jsonObject(
                        "sessionId" to did.jv(),
                        "content" to jsonArray(contentBlocks(text, images)),
                        "clientMessageId" to UUID.randomUUID().toString().jv(),
                    ),
                )
            }
            if (sendResult.isSuccess) return
            // TRANSPORT failure (timeout/closed) = a zombie socket, NOT a dead session: keep the
            // binding, queue the message, and rebuild the connection — flushPendingSends retries it.
            if (requeueOnTransportFailure(sendResult.exceptionOrNull(), sessionId, text, images, attempts)) return
            // A real RPC rejection (e.g. AGENT_NOT_FOUND after a daemon restart) — rebind below.
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
        val createResult = runCatching { c.send("agent.create", jsonObject(createParams)) }
        val newDid = createResult.getOrNull()?.get("sessionId")?.stringValue
        if (newDid == null) {
            // Zombie socket → queue + rebuild + retry instead of a dead-end error.
            if (requeueOnTransportFailure(createResult.exceptionOrNull(), sessionId, text, images, attempts)) return
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

    /**
     * True (handled) when [error] is a transport failure — timeout or closed — meaning the socket
     * is a zombie (uplink alive, downlink dead) or gone: queue the message for redelivery, tear the
     * connection down, and reconnect. Gives up (returns false) after 2 requeues so a persistent
     * outage still surfaces the error instead of looping agent.create against the daemon forever.
     */
    private fun requeueOnTransportFailure(
        error: Throwable?,
        sessionId: String,
        text: String,
        images: List<String>,
        attempts: Int,
    ): Boolean {
        val transport = error is JsonRpcConnection.ConnError.Timeout ||
            error is JsonRpcConnection.ConnError.Closed
        if (!transport || attempts >= 2) return false
        synchronized(pendingSends) {
            pendingSends.add(PendingSend(sessionId, text, images, attempts + 1))
        }
        forceReconnect()
        return true
    }

    /** Tear down the current (possibly half-dead) connection and dial fresh. */
    private fun forceReconnect() {
        scope.launch {
            pumpJob?.cancel()
            val old = mutex.withLock { val c = conn; conn = null; connecting = false; c }
            runCatching { old?.close() }
            _state.value = ConnectionState.Failed("reconnecting")
            connect()
        }
    }

    private fun flushPendingSends() {
        val queued = synchronized(pendingSends) {
            val q = pendingSends.toList(); pendingSends.clear(); q
        }
        for (q in queued) scope.launch { deliver(q.sessionId, q.text, q.images, q.attempts) }
    }

    private fun failPendingSends(reason: String) {
        val sids = synchronized(pendingSends) {
            val s = pendingSends.map { it.sessionId }.toSet(); pendingSends.clear(); s
        }
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
        updateAgentStatus(p)
        val dsid = p["sessionId"]?.stringValue ?: return
        val local = localIdFor[dsid] ?: dsid
        val status = p["status"]?.stringValue ?: ""
        val runStatus = p["runStatus"]?.stringValue ?: ""
        if (status == "error" || runStatus == "errored") {
            val m = p["message"]?.stringValue ?: "Agent error"
            val err = EventMsg.Error(m)
            stores[local]?.ingest(Event(UUID.randomUUID().toString(), null, err))
            setSessionLifecycle(local, SessionLifecycle.Idle)
            notifyIfBackgrounded(local, err)
        } else if (status in IDLE_STATUSES || runStatus in IDLE_RUN_STATUSES) {
            // The agent went idle → the turn finished. The daemon doesn't always emit turn_complete,
            // so flip the OPEN chat out of "working" here. Only treat it as a real turn-done (and
            // notify) when a turn was actually in flight — bare idle pings shouldn't spam a banner.
            val s = stores[local]
            val wasInFlight = s != null && s.lifecycle.value.isInFlight
            if (wasInFlight) {
                val complete = EventMsg.TurnComplete(turnId = "", last = null)
                s.ingest(Event(UUID.randomUUID().toString(), null, complete))
                notifyIfBackgrounded(local, complete, synthetic = true)
            }
            setSessionLifecycle(local, stores[local]?.lifecycle?.value ?: SessionLifecycle.Idle)
        }
    }

    private suspend fun onPumpClosed() {
        _state.value = ConnectionState.Failed("disconnected")
        mutex.withLock { conn = null; connecting = false }
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
        for (store in stores.values.toList()) {
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

    /** The app is backgrounded when the process lifecycle is below STARTED. */
    private fun isBackgrounded(): Boolean =
        !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Fire a local notification for events the user cares about WHILE BACKGROUNDED, each gated by its
     * own toggle and an intelligence layer (per-session mute, the foreground session, a turn-done
     * threshold, and quiet hours). Direct port of the iOS `notifyIfBackgrounded`.
     *
     * @param synthetic true for a completion synthesized from `agent_status` idle (still a real
     * turn-done for notification purposes) so the turn-done path runs even without a wire TurnComplete.
     */
    private fun notifyIfBackgrounded(local: String, msg: EventMsg, synthetic: Boolean = false) {
        val nc = notifications ?: return
        if (!isBackgrounded()) {
            // Still consume a completed turn's start time so a later foreground turn re-stamps cleanly.
            if (msg is EventMsg.TurnComplete) turnStartedAt.remove(local)
            return
        }

        // Never notify for the session the user has open right now, or one they've explicitly muted.
        if (local == currentlyOpenSessionId || prefs.mutedSessions.contains(local)) {
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
                nc.post(
                    title = "Input needed · $title",
                    body = msg.pending.question,
                    sessionId = local,
                    requestId = msg.pending.id,
                    kind = NotificationCoordinator.Kind.INPUT,
                )
            }
            is EventMsg.TurnComplete -> if (prefs.notifyTurnDone) {
                val duration = turnDuration(local)
                turnStartedAt.remove(local) // consume the start time for this turn
                // THRESHOLD: skip quick turns — a sub-threshold round-trip isn't worth a banner.
                if (duration != null && duration < prefs.turnDoneThresholdSeconds) return
                // QUIET HOURS: turn-done is non-urgent, so suppress it inside the quiet window
                // (needs-you / error still fire above).
                if (prefs.isWithinQuietHours()) return
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
            setOf("idle", "done", "completed", "ready", "finished", "stopped")
        private val IDLE_RUN_STATUSES = setOf("completed", "done", "idle")

        private val sessionsSerializer =
            ListSerializer(MapSerializer(String.serializer(), String.serializer()))
    }
}
