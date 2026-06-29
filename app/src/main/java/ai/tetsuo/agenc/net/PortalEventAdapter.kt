package ai.tetsuo.agenc.net

import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.model.FileMutation
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.QuestionOption
import ai.tetsuo.agenc.model.ToolKind
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

            "event.permission_request" -> Mapped(sid, permission(p))

            "event.session_event" -> {
                val ev = p["event"] ?: return null
                val type = ev["type"]?.stringValue ?: return null
                mapInner(type, ev)?.let { Mapped(sid, it) }
            }

            "event.user_input_request" -> {
                // The agent is asking the user a question — surface it so the turn doesn't stall.
                val q = p["questions"]?.arrayValue?.firstOrNull() ?: return null
                val opts = (q["options"]?.arrayValue ?: emptyList()).mapNotNull { o ->
                    val label = o["label"]?.stringValue ?: return@mapNotNull null
                    // options carry no id; the label IS the value
                    QuestionOption(id = label, label = label)
                }
                Mapped(
                    sid,
                    EventMsg.RequestUserInput(
                        PendingElicitation(
                            id = p["requestId"]?.stringValue ?: UUID.randomUUID().toString(),
                            questionId = q["id"]?.stringValue ?: "q",
                            question = q["question"]?.stringValue
                                ?: q["header"]?.stringValue
                                ?: "The agent needs your input",
                            options = opts,
                            allowsOther = q["isOther"]?.boolValue ?: true,
                        ),
                    ),
                )
            }

            // event.agent_status handled in DaemonHub; voice/unknown elsewhere
            else -> null
        }
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

            "tool_call_completed" ->
                EventMsg.ToolCallCompleted(
                    callId = callId(p),
                    result = p["result"]?.stringValue ?: p["output"]?.stringValue ?: "",
                    isError = p["isError"]?.boolValue ?: false,
                    diff = fileMutation(p["diff"]),
                )

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

    private fun permission(v: JsonValue): EventMsg {
        val name = v["toolName"]?.stringValue ?: "tool"
        return EventMsg.RequestPermissions(
            PendingPermission(
                id = v["requestId"]?.stringValue ?: UUID.randomUUID().toString(),
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
