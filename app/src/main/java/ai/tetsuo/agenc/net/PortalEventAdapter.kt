package ai.tetsuo.agenc.net

import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.model.FileMutation
import ai.tetsuo.agenc.model.LedgerSolanaTransferClientAction
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import ai.tetsuo.agenc.model.LedgerTransferResult
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.QuestionOption
import ai.tetsuo.agenc.model.ToolKind
import java.time.Instant
import java.util.UUID

/**
 * Direct port of the iOS app's PortalEventAdapter.swift.
 *
 * Maps agenc-core's app-server-protocol notifications onto the reducer's [EventMsg].
 * Top-level events: `event.message_chunk`, `event.tool_request`, `event.permission_request`,
 * `event.agent_status` (handled in DaemonHub), `event.session_event { event:{ type,… } }`.
 * All carry base `{ sessionId, eventId, agentId?, sequence? }`. On (re)attach the daemon REPLAYS
 * past events — there is no `session_configured` envelope.
 */
object PortalEventAdapter {

    /** Result of mapping one notification: the daemon session id + the reduced event. */
    data class Mapped(val sessionId: String, val msg: EventMsg)

    fun map(note: JsonRpcConnection.Notification): Mapped? {
        val p = note.params
        val sid = p["sessionId"]?.stringValue ?: return null

        return when (note.method) {
            "event.message_chunk" -> {
                val d = p["delta"]?.stringValue ?: ""
                if (d.isEmpty()) null
                else Mapped(sid, EventMsg.AgentMessageDelta(delta = d, resetBuffer = false))
            }

            "event.tool_request" -> {
                val name = p["toolName"]?.stringValue ?: "tool"
                Mapped(
                    sid,
                    EventMsg.ToolCallStarted(
                        callId = p["requestId"]?.stringValue ?: UUID.randomUUID().toString(),
                        toolName = name,
                        title = name,
                        kind = toolKind(name),
                    ),
                )
            }

            "event.permission_request" -> permission(p)?.let { Mapped(sid, it) }

            "event.session_event" -> {
                val ev = p["event"] ?: return null
                val type = ev["type"]?.stringValue ?: return null
                mapInner(type, ev)?.let { Mapped(sid, it) }
            }

            "event.user_input_request" -> {
                // The agent is asking the user a question — surface it so the turn doesn't stall.
                // A Ledger clientAction is authoritative and may intentionally have no display
                // question. Never derive transfer authority from the human-readable question text.
                val actionValue = p["clientAction"]
                val action = parseLedgerClientAction(actionValue)
                // Presence is authoritative: a malformed/expired privileged action must never
                // degrade into the generic answer sheet carried alongside it.
                if (actionValue != null && action == null) return null
                val q = p["questions"]?.arrayValue?.firstOrNull()
                if (q == null && action == null) return null
                val opts = (q?.get("options")?.arrayValue ?: emptyList()).mapNotNull { o ->
                    val label = o["label"]?.stringValue ?: return@mapNotNull null
                    // options carry no id; the label IS the value
                    QuestionOption(id = label, label = label)
                }
                val requestId = p["requestId"]?.stringValue ?: return null
                Mapped(
                    sid,
                    EventMsg.RequestUserInput(
                        PendingElicitation(
                            id = requestId,
                            questionId = q?.get("id")?.stringValue
                                ?: if (action != null) "ledger_transfer" else "q",
                            question = q?.get("question")?.stringValue
                                ?: q?.get("header")?.stringValue
                                ?: "The agent needs your input",
                            options = opts,
                            allowsOther = q?.get("isOther")?.boolValue ?: (action == null),
                            clientAction = action,
                        ),
                    ),
                )
            }

            // event.agent_status handled in DaemonHub; voice/unknown elsewhere
            else -> null
        }
    }

    /**
     * Fail-closed parser for Core's privileged mobile Ledger action. A forged generic question,
     * malformed amount/address, wrong network/source/capability, or expired request returns null.
     */
    internal fun parseLedgerClientAction(
        value: JsonValue?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): LedgerSolanaTransferClientAction? {
        val action = value as? JsonValue.Obj ?: return null
        if (action["type"]?.stringValue != LEDGER_ACTION_TYPE ||
            action["source"]?.stringValue != LEDGER_ACTION_SOURCE ||
            action["targetCapability"]?.stringValue != LEDGER_CAPABILITY ||
            action["network"]?.stringValue != LEDGER_NETWORK
        ) {
            return null
        }
        val intentId = action["intentId"]?.stringValue
            ?.takeIf {
                it.startsWith("ledger_") &&
                    it.length in 15..200 &&
                    SAFE_LEDGER_ID.matches(it)
            }
            ?: return null
        val responseNonce = action["responseNonce"]?.stringValue
            ?.takeIf(LedgerTransferReceipt::isValidResponseNonce)
            ?: return null
        val to = action["to"]?.stringValue ?: return null
        if (to.length !in 32..44 ||
            !runCatching { Base58.decode(to).size == 32 }.getOrDefault(false)
        ) {
            return null
        }
        val lamportsText = action["lamports"]?.stringValue ?: return null
        if (!POSITIVE_CANONICAL_INTEGER.matches(lamportsText)) return null
        val lamports = lamportsText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val note = action["note"]?.stringValue?.takeIf { it.length <= 240 }
        if (action["note"] != null && note == null) return null
        val expiresAt = action["expiresAt"]?.stringValue
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return null
        if (expiresAt <= nowEpochMillis || expiresAt - nowEpochMillis > MAX_LEDGER_TTL_MS) {
            return null
        }
        return LedgerSolanaTransferClientAction(
            intentId = intentId,
            responseNonce = responseNonce,
            to = to,
            lamports = lamports,
            note = note,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun mapInner(type: String, ev: JsonValue): EventMsg? {
        // CRITICAL: session_event fields are nested under `payload`, e.g. {id, type, payload:{delta…}}.
        // Reading them off `ev` directly silently drops thinking, tool results, and the final message.
        val p = ev["payload"] ?: ev
        return when (type) {
            "turn_started" ->
                EventMsg.TurnStarted(turnId = p["turnId"]?.stringValue ?: "turn")

            "turn_complete", "turn_completed" ->
                EventMsg.TurnComplete(
                    turnId = p["turnId"]?.stringValue ?: "turn",
                    last = p["last"]?.stringValue,
                )

            "assistant_thinking_delta" -> {
                val d = p["delta"]?.stringValue ?: ""
                if (d.isEmpty()) null else EventMsg.ThinkingDelta(text = d)
            }

            // reasoning streams via assistant_thinking_delta; these are structural/duplicate
            "agent_thinking", "assistant_thinking_block_start", "assistant_thinking_block_stop" -> null

            "agent_message" -> {
                val t = p["content"]?.stringValue
                    ?: p["message"]?.stringValue
                    ?: p["text"]?.stringValue
                    ?: ""
                if (t.isEmpty()) null else EventMsg.AgentMessage(text = t)
            }

            // assistant text streams via top-level event.message_chunk; avoid doubling
            "agent_message_delta" -> null

            // the app already renders the user's message locally on send
            "user_message" -> null

            "tool_call_started" -> {
                val name = p["toolName"]?.stringValue
                    ?: p["metadata"]?.get("toolName")?.stringValue
                    ?: "tool"
                EventMsg.ToolCallStarted(
                    callId = callId(p),
                    toolName = name,
                    title = name,
                    kind = toolKind(name),
                )
            }

            "tool_call_completed" -> {
                val result = p["result"]?.stringValue ?: p["output"]?.stringValue ?: ""
                val isError = p["isError"]?.boolValue ?: false
                EventMsg.ToolCallCompleted(
                    callId = callId(p),
                    result = result,
                    isError = isError,
                    diff = fileMutation(p["diff"]),
                    ledgerResult = if (isError) null else LedgerTransferResult.parse(result),
                )
            }

            "interrupt" ->
                EventMsg.TurnAborted(reason = p["reason"]?.stringValue ?: "interrupted")

            "request_permissions" -> permission(p)

            "error" ->
                EventMsg.Error(message = p["message"]?.stringValue ?: "error")

            // non-terminal warning — do NOT flip the whole session into .error
            "warning" -> null

            // content block; assistant text streams via message_chunk
            "text" -> null

            // history_cleared/image_url/user_input_request/etc.
            else -> EventMsg.Unknown(type = type)
        }
    }

    private fun permission(v: JsonValue): EventMsg? {
        val name = v["toolName"]?.stringValue ?: "tool"
        val requestId = v["requestId"]?.stringValue ?: return null
        return EventMsg.RequestPermissions(
            PendingPermission(
                id = requestId,
                toolName = name,
                title = "Allow $name?",
                detail = v["reason"]?.stringValue ?: "",
                kind = toolKind(name),
                diff = fileMutation(v["input"]),
                permissions = v["permissions"]?.stringArray ?: emptyList(),
            ),
        )
    }

    private fun callId(ev: JsonValue): String =
        ev["toolCallId"]?.stringValue
            ?: ev["callId"]?.stringValue
            ?: ev["requestId"]?.stringValue
            ?: ev["id"]?.stringValue
            ?: ""

    private const val LEDGER_ACTION_TYPE = "ledger_solana_transfer_v1"
    private const val LEDGER_ACTION_SOURCE = "agenc-core"
    private const val LEDGER_CAPABILITY = "portal.ledger.solana.sign.v1"
    private const val LEDGER_NETWORK = "mainnet-beta"
    private const val MAX_LEDGER_TTL_MS = 15 * 60 * 1_000L
    private val SAFE_LEDGER_ID = Regex("[A-Za-z0-9_-]+")
    private val POSITIVE_CANONICAL_INTEGER = Regex("[1-9][0-9]*")

    private fun toolKind(raw: String?): ToolKind = ToolKind.from(raw)

    private fun fileMutation(v: JsonValue?): FileMutation? {
        if (v == null) return null
        val path = v["filePath"]?.stringValue ?: return null
        return FileMutation(
            filePath = path,
            operation = v["operation"]?.stringValue ?: "edit",
            additions = v["additions"]?.intValue ?: 0,
            removals = v["removals"]?.intValue ?: 0,
        )
    }
}
