package ai.tetsuo.agenc.model

/**
 * Direct port of the iOS app's Models.swift — the shared domain types every layer depends on.
 * The wire (relay/daemon) delivers Portal JSON-RPC notifications that PortalEventAdapter normalizes
 * into [EventMsg]; SessionStore reduces those into [TranscriptItem]s + a [SessionLifecycle].
 */

// MARK: - Wire envelope (mirrors agenc-core: Event = {id, msg, seq})

data class Event(
    val id: String,
    val seq: Int?,
    val msg: EventMsg,
)

/** The runtime EventMsg variants the chat UI reduces. */
sealed interface EventMsg {
    data class TurnStarted(val turnId: String) : EventMsg
    data class UserMessage(val text: String) : EventMsg
    /** A voice message the user recorded (demo flow): duration + its transcript caption. */
    data class UserVoice(val seconds: Int, val transcript: String) : EventMsg
    data class ThinkingDelta(val text: String) : EventMsg
    data class AgentMessageDelta(val delta: String, val resetBuffer: Boolean) : EventMsg
    data class AgentMessage(val text: String) : EventMsg
    data class ToolCallStarted(
        val callId: String, val toolName: String, val title: String, val kind: ToolKind,
    ) : EventMsg
    data class ToolProgress(val callId: String, val chunk: String) : EventMsg
    data class ToolCallCompleted(
        val callId: String,
        val result: String,
        val isError: Boolean,
        val diff: FileMutation?,
        /** Strictly parsed clean result; rendered only when the matching tool is request_ledger_transfer. */
        val ledgerResult: LedgerTransferResult? = null,
    ) : EventMsg
    data class TokenCount(val usage: TokenUsage) : EventMsg
    data class RequestPermissions(val pending: PendingPermission) : EventMsg
    data class RequestUserInput(val pending: PendingElicitation) : EventMsg
    data class ContextCompacted(val summary: String) : EventMsg
    data class SessionConfigured(val initialMessages: List<InitialMessage>) : EventMsg
    data class TurnComplete(val turnId: String, val last: String?) : EventMsg
    data class TurnAborted(val reason: String) : EventMsg
    data class Error(val message: String) : EventMsg
    data class Unknown(val type: String) : EventMsg
}

/** A persisted message replayed on attach/reconnect (session_configured.initialMessages). */
data class InitialMessage(val role: String, val text: String)   // role: user | assistant

// MARK: - Tool model

enum class ToolKind { bash, fileEdit, fileRead, search, web, mcp, task;
    companion object {
        fun from(raw: String?): ToolKind = entries.firstOrNull { it.name == raw } ?: bash
    }
}

enum class ToolStatus { running, success, error }

data class FileMutation(
    val filePath: String,
    val operation: String,   // create | edit | delete
    val additions: Int,
    val removals: Int,
)

data class ToolCardState(
    val callId: String,
    var toolName: String,
    var title: String,
    var kind: ToolKind,
    var status: ToolStatus = ToolStatus.running,
    var output: String = "",
    var diff: FileMutation? = null,
    var resultPreview: String? = null,
) {
    val id: String get() = callId
}

// MARK: - Transcript

sealed interface DividerKind {
    data class Compacted(val summary: String) : DividerKind
}

sealed interface TranscriptItem {
    val id: String

    data class User(val msgId: String, val text: String) : TranscriptItem {
        override val id get() = "u-$msgId"
    }
    /** A user voice message: a playable-looking bubble with duration + transcript caption. */
    data class Voice(val msgId: String, val seconds: Int, val transcript: String) : TranscriptItem {
        override val id get() = "v-$msgId"
    }
    data class Assistant(val msgId: String, val text: String) : TranscriptItem {
        override val id get() = "a-$msgId"
    }
    data class Thinking(val msgId: String, val text: String) : TranscriptItem {
        override val id get() = "t-$msgId"
    }
    data class Tool(val card: ToolCardState) : TranscriptItem {
        override val id get() = "tool-${card.callId}"
    }
    data class ToolGroup(val msgId: String, val cards: List<ToolCardState>) : TranscriptItem {
        override val id get() = "tg-$msgId"
    }
    data class Divider(val msgId: String, val kind: DividerKind) : TranscriptItem {
        override val id get() = "d-$msgId"
    }
    data class LedgerTransfer(val result: LedgerTransferResult) : TranscriptItem {
        override val id get() = "ledger-${result.intentId}"
    }

    val isAssistant: Boolean get() = this is Assistant
}

/** Codable snapshot of one transcript entry, persisted per session. */
@kotlinx.serialization.Serializable
data class PersistedMsg(
    var role: String = "",         // user | assistant | tool
    var text: String = "",
    var toolName: String = "",
    var toolKind: String = "",
    var toolStatus: String = "",   // running | success | error
    var toolResult: String = "",
    /** Canonical typed receipt JSON for role=ledger_transfer. */
    var ledgerResult: String = "",
)

// MARK: - Lifecycle

sealed interface SessionLifecycle {
    data object Idle : SessionLifecycle
    data object Running : SessionLifecycle
    data object AwaitingPermission : SessionLifecycle
    data object AwaitingInput : SessionLifecycle
    data object Done : SessionLifecycle
    data class Error(val message: String) : SessionLifecycle

    val label: String
        get() = when (this) {
            Idle -> "Idle"
            Running -> "Working…"
            AwaitingPermission -> "Needs approval"
            AwaitingInput -> "Waiting for you"
            Done -> "Done"
            is Error -> "Error"
        }

    /** The agent is actively generating — drives the live shimmer on the status indicator. */
    val isActive: Boolean get() = this is Running

    /** A turn is in flight and the UI is waiting on the connection. */
    val isInFlight: Boolean
        get() = when (this) {
            Running, AwaitingPermission, AwaitingInput -> true
            Idle, Done, is Error -> false
        }
}

// MARK: - Interactive requests (keyed by requestId)

data class PendingPermission(
    val id: String,            // requestId
    val toolName: String,
    val title: String,
    val detail: String,
    val kind: ToolKind,
    var diff: FileMutation? = null,
    val permissions: List<String>,
)

/** Authoritative client state for one permission decision; keyed by the daemon requestId. */
enum class PermissionDecisionAction { AllowOnce, AllowSession, Deny }

sealed interface PermissionDecisionState {
    data object Idle : PermissionDecisionState
    data class Submitting(val action: PermissionDecisionAction) : PermissionDecisionState
    data class Failed(val message: String) : PermissionDecisionState
}

data class QuestionOption(val id: String, val label: String)

/**
 * Trusted, machine-readable action emitted only by Core's built-in `request_ledger_transfer` tool.
 * Monetary values arrive as decimal text on the wire and are parsed only after strict validation,
 * so JavaScript number precision can never change the amount shown or signed on the phone.
 */
data class LedgerSolanaTransferClientAction(
    val intentId: String,
    /** Core-generated high-entropy challenge echoed by the exact response. */
    val responseNonce: String,
    val to: String,
    val lamports: Long,
    val note: String?,
    val expiresAtEpochMillis: Long,
)

data class PendingElicitation(
    val id: String,            // requestId
    var questionId: String = "",
    val question: String,
    val options: List<QuestionOption>,
    var allowsOther: Boolean = true,
    val clientAction: LedgerSolanaTransferClientAction? = null,
)

/** A capability-routed Ledger request can arrive even when its chat is not open/attached. */
data class PendingLedgerInteraction(
    val sessionId: String,
    val elicitation: PendingElicitation,
    /** Pairing id (or a local-daemon sentinel) that owns this request/response namespace. */
    val hostBinding: String,
)

// MARK: - Cost

data class TokenUsage(
    var input: Int = 0,
    var output: Int = 0,
    var costUSD: Double = 0.0,
) {
    fun accumulate(u: TokenUsage) {
        input += u.input
        output += u.output
        costUSD += u.costUSD
    }
}

// MARK: - Summaries (session.list / agent.list projections)

enum class Tier { free, pro, team, enterprise;
    val label: String get() = name.replaceFirstChar { it.uppercase() }
}

data class SessionSummary(
    val id: String,
    var title: String,
    var lifecycle: SessionLifecycle,
    var cwd: String,
    var subtitle: String,
    var updated: String,
) {
    /** Terminal-started sessions carry a `conv-` id and are presented read-only. */
    val isTerminalOrigin: Boolean get() = id.startsWith("conv-")
}

data class AgentSummary(
    val id: String,
    var objective: String,
    var status: String,
    var needsAttention: Boolean,
)

/** A device running the AgenC daemon that the app can connect to and control. */
data class Host(
    val id: String,
    val name: String,
    val endpoint: String,
    val online: Boolean,
)

data class Identity(
    var displayName: String,
    var handle: String,
    var tier: Tier,
    /** Stable account owner used to verify that phone auth and Core `/login` are the same person. */
    var accountId: String? = null,
    /** False when Core confirms the person but does not expose subscription data. */
    var tierVerified: Boolean = true,
    /** True only after the runtime identity matches the phone identity by account id or email. */
    var runtimeLinked: Boolean = false,
    /** A connected runtime reported a different stable account id. */
    var runtimeMismatch: Boolean = false,
)
