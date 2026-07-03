package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.DividerKind
import ai.tetsuo.agenc.model.Event
import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.PersistedMsg
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.ToolCardState
import ai.tetsuo.agenc.model.ToolKind
import ai.tetsuo.agenc.model.ToolStatus
import ai.tetsuo.agenc.model.TokenUsage
import ai.tetsuo.agenc.model.TranscriptItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Direct port of the iOS app's SessionStore.swift — the streaming brain.
 *
 * A pure-ish reducer that folds an ordered [EventMsg] stream into a rendered transcript.
 * Mirrors agenc-core's event-log-reducer.ts. Idempotent on `seq` and on the daemon eventId.
 *
 * Observable state is exposed as [StateFlow]s so Compose can `collectAsState`:
 *  - [items] — the rendered transcript
 *  - [lifecycle] — the session lifecycle
 *  - [pendingPermission] / [pendingElicitation] — open interactive requests
 *  - [usage] — accumulated token cost
 *  - [revision] — bumps on every ingested event (incl. streaming deltas) so the view can keep
 *    the latest line in view while tokens stream
 *  - [thinkingDurations] — thinking-item id → measured reasoning seconds
 *
 * NOTE on threading: [ingest] is `@Synchronized` so it is safe to call from multiple threads
 * (the notification pump on Dispatchers.Default AND UI-thread paths like attach/send). All transcript
 * mutation funnels through it, so the plain backing collections never race. Reads happen on the main
 * thread via the StateFlows (which are themselves thread-safe).
 */
class SessionStore(
    val id: String,
    var title: String,
    private val prefs: Prefs,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

    private val _lifecycle = MutableStateFlow<SessionLifecycle>(SessionLifecycle.Idle)
    val lifecycle: StateFlow<SessionLifecycle> = _lifecycle.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PendingPermission?>(null)
    val pendingPermission: StateFlow<PendingPermission?> = _pendingPermission.asStateFlow()

    private val _pendingElicitation = MutableStateFlow<PendingElicitation?>(null)
    val pendingElicitation: StateFlow<PendingElicitation?> = _pendingElicitation.asStateFlow()

    private val _usage = MutableStateFlow(TokenUsage())
    val usage: StateFlow<TokenUsage> = _usage.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _thinkingDurations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val thinkingDurations: StateFlow<Map<String, Int>> = _thinkingDurations.asStateFlow()

    // --- Mutable working copies (the StateFlows mirror these snapshots) ---
    private val itemsList = mutableListOf<TranscriptItem>()

    private var assistantBuffer = ""
    private var currentTurnId: String? = null
    private var thinkingBuffer = ""
    private var lastSeq = -1
    private val durations = mutableMapOf<String, Int>()
    private var currentThinkingStart: Long? = null
    private var currentThinkingId: String? = null

    /** Daemon eventIds already ingested — drops replayed events so the transcript isn't duplicated. */
    private val seenEventIds = mutableSetOf<String>()

    /** Index of the current turn's tool group, so every tool call folds into one "Ran N tools" row. */
    private var currentTurnToolGroupIndex: Int? = null

    /** Index of the current turn's single thinking item. */
    private var currentTurnThinkingIndex: Int? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        loadPersisted() // restore the transcript so reopening a chat keeps its history
    }

    // MARK: Ingestion

    @Synchronized
    fun ingest(event: Event) {
        // Idempotent on the daemon's eventId: a reconnect replays buffered events.
        if (event.id.isNotEmpty()) {
            if (!seenEventIds.add(event.id)) return
        }
        event.seq?.let { s ->
            if (s <= lastSeq) return // FIFO de-dupe (legacy seq path)
            lastSeq = s
        }
        reduce(event.msg)
        publishItems()
        _revision.value = _revision.value + 1
        // Persist on natural checkpoints so the conversation survives close + relaunch.
        when (event.msg) {
            is EventMsg.UserMessage,
            is EventMsg.AgentMessage,
            is EventMsg.ToolCallCompleted,
            is EventMsg.TurnComplete,
            is EventMsg.SessionConfigured -> savePersisted()
            else -> Unit
        }
    }

    private fun reduce(msg: EventMsg) {
        when (msg) {
            is EventMsg.TurnStarted -> {
                currentTurnId = msg.turnId
                assistantBuffer = ""
                thinkingBuffer = ""
                currentTurnToolGroupIndex = null
                currentTurnThinkingIndex = null
                currentThinkingStart = null
                currentThinkingId = null
                _lifecycle.value = SessionLifecycle.Running
            }

            is EventMsg.UserMessage -> {
                // A user message starts a new turn. turn_started isn't emitted in every flow.
                thinkingBuffer = ""; assistantBuffer = ""
                currentTurnToolGroupIndex = null; currentTurnThinkingIndex = null
                currentThinkingStart = null; currentThinkingId = null
                itemsList.add(TranscriptItem.User(msgId = UUID.randomUUID().toString(), text = msg.text))
            }

            is EventMsg.UserVoice -> {
                thinkingBuffer = ""; assistantBuffer = ""
                currentTurnToolGroupIndex = null; currentTurnThinkingIndex = null
                currentThinkingStart = null; currentThinkingId = null
                itemsList.add(
                    TranscriptItem.Voice(UUID.randomUUID().toString(), msg.seconds, msg.transcript),
                )
            }

            is EventMsg.ThinkingDelta -> {
                if (currentThinkingStart == null) currentThinkingStart = nowMillis()
                thinkingBuffer += msg.text
                upsertThinking(thinkingBuffer)
            }

            is EventMsg.AgentMessageDelta -> {
                freezeThinkingDuration()
                assistantBuffer = if (msg.resetBuffer) msg.delta else assistantBuffer + msg.delta
                upsertAssistant(assistantBuffer)
            }

            is EventMsg.AgentMessage -> {
                freezeThinkingDuration()
                assistantBuffer = msg.text
                upsertAssistant(msg.text)
            }

            is EventMsg.ToolCallStarted -> {
                // Fold ALL of a turn's tool calls into one group.
                val card = ToolCardState(
                    callId = msg.callId,
                    toolName = msg.toolName,
                    title = msg.title,
                    kind = msg.kind,
                )
                val gi = currentTurnToolGroupIndex
                if (gi != null && gi < itemsList.size) {
                    when (val existing = itemsList[gi]) {
                        is TranscriptItem.ToolGroup ->
                            itemsList[gi] = TranscriptItem.ToolGroup(existing.msgId, existing.cards + card)
                        is TranscriptItem.Tool ->
                            itemsList[gi] =
                                TranscriptItem.ToolGroup(existing.card.callId, listOf(existing.card, card))
                        else -> {
                            itemsList.add(TranscriptItem.Tool(card))
                            currentTurnToolGroupIndex = itemsList.size - 1
                        }
                    }
                } else {
                    itemsList.add(TranscriptItem.Tool(card))
                    currentTurnToolGroupIndex = itemsList.size - 1
                }
            }

            is EventMsg.ToolProgress -> mutateCard(msg.callId) { it.output += msg.chunk }

            is EventMsg.ToolCallCompleted -> mutateCard(msg.callId) {
                it.status = if (msg.isError) ToolStatus.error else ToolStatus.success
                it.resultPreview = msg.result
                it.diff = msg.diff
            }

            is EventMsg.TokenCount -> {
                val u = _usage.value
                u.accumulate(msg.usage)
                _usage.value = TokenUsage(u.input, u.output, u.costUSD)
            }

            is EventMsg.RequestPermissions -> {
                _pendingPermission.value = msg.pending
                _lifecycle.value = SessionLifecycle.AwaitingPermission
            }

            is EventMsg.RequestUserInput -> {
                _pendingElicitation.value = msg.pending
                _lifecycle.value = SessionLifecycle.AwaitingInput
            }

            is EventMsg.ContextCompacted ->
                itemsList.add(
                    TranscriptItem.Divider(
                        msgId = UUID.randomUUID().toString(),
                        kind = DividerKind.Compacted(msg.summary),
                    ),
                )

            is EventMsg.SessionConfigured -> {
                // Replay on attach/reconnect. Only SEED an empty transcript — never wipe one we have.
                if (itemsList.isNotEmpty() || msg.initialMessages.isEmpty()) return
                assistantBuffer = ""
                thinkingBuffer = ""
                currentTurnId = null
                for (m in msg.initialMessages) {
                    itemsList.add(
                        if (m.role == "user") {
                            TranscriptItem.User(UUID.randomUUID().toString(), m.text)
                        } else {
                            TranscriptItem.Assistant(UUID.randomUUID().toString(), m.text)
                        },
                    )
                }
                _lifecycle.value = SessionLifecycle.Idle
            }

            is EventMsg.TurnComplete -> {
                freezeThinkingDuration()
                msg.last?.takeIf { it.isNotEmpty() }?.let { upsertAssistant(it) }
                _lifecycle.value = SessionLifecycle.Done
            }

            is EventMsg.TurnAborted -> {
                freezeThinkingDuration()
                _lifecycle.value =
                    if (msg.reason.contains("interrupt")) SessionLifecycle.Done
                    else SessionLifecycle.Error(msg.reason)
            }

            is EventMsg.Error -> _lifecycle.value = SessionLifecycle.Error(msg.message)

            is EventMsg.Unknown -> Unit // forward-compat: never crash on a new variant
        }
    }

    // MARK: Client → daemon replies (UI hooks; the live transactions are driven by DaemonHub)

    /** Mirrors tool.approve{scope}. */
    fun approve(scope: String) {
        _pendingPermission.value = null
        _lifecycle.value = SessionLifecycle.Running
    }

    /**
     * LIVE deny: the daemon drives what happens after tool.deny (the agent gets the denial and
     * continues its turn), so just dismiss the prompt and wait for the agent's real response.
     */
    fun clearPendingPermission() {
        _pendingPermission.value = null
        _lifecycle.value = SessionLifecycle.Running
    }

    fun respond(optionId: String) {
        _pendingElicitation.value = null
        _lifecycle.value = SessionLifecycle.Running
    }

    /** Read-only snapshot for callers that need the current item list synchronously (e.g. attach). */
    fun currentItems(): List<TranscriptItem> = _items.value

    /** Demo: replace the whole transcript with a fixed seed conversation (role,text) and persist it. */
    @Synchronized
    fun reseed(messages: List<Pair<String, String>>) {
        itemsList.clear(); seenEventIds.clear(); durations.clear()
        assistantBuffer = ""; thinkingBuffer = ""; currentTurnId = null; lastSeq = -1
        currentTurnToolGroupIndex = null; currentTurnThinkingIndex = null
        currentThinkingStart = null; currentThinkingId = null
        for ((role, text) in messages) {
            itemsList.add(
                if (role == "user") TranscriptItem.User(UUID.randomUUID().toString(), text)
                else TranscriptItem.Assistant(UUID.randomUUID().toString(), text),
            )
        }
        _lifecycle.value = SessionLifecycle.Idle
        _pendingPermission.value = null
        _pendingElicitation.value = null
        publishItems()
        savePersisted()
    }

    // MARK: Helpers

    private fun publishItems() {
        _items.value = itemsList.toList()
    }

    private fun upsertAssistant(text: String) {
        val last = itemsList.lastOrNull()
        if (last is TranscriptItem.Assistant) {
            itemsList[itemsList.size - 1] = TranscriptItem.Assistant(last.msgId, text)
        } else {
            // Each assistant text block gets a fresh id; reusing currentTurnId collides.
            itemsList.add(TranscriptItem.Assistant(UUID.randomUUID().toString(), text))
        }
    }

    private fun upsertThinking(text: String) {
        val ti = currentTurnThinkingIndex
        val existing = if (ti != null && ti < itemsList.size) itemsList[ti] else null
        if (ti != null && existing is TranscriptItem.Thinking) {
            itemsList[ti] = TranscriptItem.Thinking(existing.msgId, text)
            currentThinkingId = existing.msgId
        } else {
            val newId = UUID.randomUUID().toString()
            itemsList.add(TranscriptItem.Thinking(newId, text))
            currentTurnThinkingIndex = itemsList.size - 1
            currentThinkingId = newId
        }
    }

    /** Freeze the in-flight reasoning clock onto the current thinking item. Idempotent. */
    private fun freezeThinkingDuration() {
        val start = currentThinkingStart ?: return
        val tid = currentThinkingId ?: return
        val elapsed = maxOf(1, Math.round((nowMillis() - start) / 1000.0).toInt())
        durations[tid] = elapsed
        _thinkingDurations.value = durations.toMap()
        currentThinkingStart = null // frozen — don't re-stamp on later deltas of the same answer
    }

    private fun mutateCard(callId: String, transform: (ToolCardState) -> Unit) {
        for (i in itemsList.indices) {
            val item = itemsList[i]
            if (item is TranscriptItem.Tool && item.card.callId == callId) {
                transform(item.card)
                itemsList[i] = TranscriptItem.Tool(item.card)
                return
            }
            if (item is TranscriptItem.ToolGroup) {
                val card = item.cards.firstOrNull { it.callId == callId }
                if (card != null) {
                    transform(card)
                    itemsList[i] = TranscriptItem.ToolGroup(item.msgId, item.cards.toList())
                    return
                }
            }
        }
    }

    // MARK: Persistence — keep the transcript across close + relaunch (keyed by session id)

    private val persistKey: String get() = Prefs.transcriptKey(id)
    private val persistedMsgSerializer = ListSerializer(PersistedMsg.serializer())

    private fun toolMsg(c: ToolCardState): PersistedMsg = PersistedMsg(
        role = "tool",
        text = "",
        toolName = c.toolName,
        toolKind = c.kind.name,
        toolStatus = when (c.status) {
            ToolStatus.error -> "error"
            ToolStatus.success -> "success"
            ToolStatus.running -> "running"
        },
        toolResult = c.resultPreview ?: c.output,
    )

    private fun snapshot(): List<PersistedMsg> {
        val out = mutableListOf<PersistedMsg>()
        for (item in itemsList) {
            when (item) {
                is TranscriptItem.User -> out.add(PersistedMsg(role = "user", text = item.text))
                is TranscriptItem.Assistant ->
                    out.add(PersistedMsg(role = "assistant", text = item.text))
                is TranscriptItem.Tool -> out.add(toolMsg(item.card))
                is TranscriptItem.ToolGroup -> out.addAll(item.cards.map { toolMsg(it) })
                // Voice (demo) is ephemeral; thinking/divider aren't persisted either.
                is TranscriptItem.Voice, is TranscriptItem.Thinking, is TranscriptItem.Divider -> Unit
            }
        }
        return out
    }

    private fun savePersisted() {
        val data = runCatching {
            json.encodeToString(persistedMsgSerializer, snapshot())
        }.getOrNull() ?: return
        prefs.setString(persistKey, data)
    }

    private fun loadPersisted() {
        val raw = prefs.getString(persistKey) ?: return
        val msgs = runCatching {
            json.decodeFromString(persistedMsgSerializer, raw)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return

        val rebuilt = mutableListOf<TranscriptItem>()
        for (m in msgs) {
            when (m.role) {
                "assistant" ->
                    rebuilt.add(TranscriptItem.Assistant(UUID.randomUUID().toString(), m.text))
                "tool" -> {
                    val card = ToolCardState(
                        callId = UUID.randomUUID().toString(),
                        toolName = m.toolName,
                        title = m.toolName,
                        kind = ToolKind.entries.firstOrNull { it.name == m.toolKind } ?: ToolKind.task,
                    )
                    card.status = if (m.toolStatus == "error") ToolStatus.error else ToolStatus.success
                    card.resultPreview = m.toolResult.ifEmpty { null }
                    // Group consecutive tools on restore too — mirror live ingestion ("Ran N tools").
                    when (val last = rebuilt.lastOrNull()) {
                        is TranscriptItem.Tool ->
                            rebuilt[rebuilt.size - 1] =
                                TranscriptItem.ToolGroup(last.card.callId, listOf(last.card, card))
                        is TranscriptItem.ToolGroup ->
                            rebuilt[rebuilt.size - 1] =
                                TranscriptItem.ToolGroup(last.msgId, last.cards + card)
                        else -> rebuilt.add(TranscriptItem.Tool(card))
                    }
                }
                else -> rebuilt.add(TranscriptItem.User(UUID.randomUUID().toString(), m.text))
            }
        }
        itemsList.clear()
        itemsList.addAll(rebuilt)
        publishItems()
        _lifecycle.value = SessionLifecycle.Done
    }
}
