package ai.tetsuo.agenc

import androidx.compose.ui.graphics.Color

/**
 * Endpoint + brand constants, ported from the iOS app's BackendConfig / AppSettings.
 * The Android app talks to the SAME hosted backend (id.agenc.ag) and the SAME relay worker.
 */
object AppConfig {
    /** Hosted backend (OAuth 2.0 device-authorization grant + pairing). */
    const val BACKEND_BASE = "https://id.agenc.ag"

    /** AgenC relay (Cloudflare worker). The app dials {RELAY_BASE}/v1/client?ticket=… */
    const val RELAY_BASE = "wss://agenc-relay.chmirenko2.workers.dev"

    /** Direct same-network daemon (raw JSON-RPC, no relay envelope). */
    const val LOCAL_DAEMON = "ws://127.0.0.1:7766"

    /** Relay client endpoint for a signed pairing/account ticket. */
    fun relayClientUrl(relayBase: String, ticket: String): String =
        "$relayBase/v1/client?ticket=$ticket"

    // Backend REST paths
    object Path {
        const val LOGIN_START = "/v1/auth/login/start"
        const val LOGIN_POLL = "/v1/auth/login/poll"
        const val LOGOUT = "/v1/auth/logout"
        const val RELAY_TICKET = "/v1/relay/ticket"
        const val PAIR_CLAIM = "/v1/pair/claim"
        const val PAIR_CLIENT_TICKET = "/v1/pair/client-ticket"
        const val PAIR_REVOKE = "/v1/pair/revoke"
    }

    /** Legacy UI constant kept for compatibility; the active design system is monochrome. */
    val Accent = Color(0xFFF4F4F1)
    val Canvas = Color(0xFF000000)
    val Danger = Color(0xFFFF453A)
}

/** Permission mode → wire value for session.setPermissionMode (AgentCreateParams.permissionMode). */
enum class PermissionMode(val daemonValue: String, val label: String, val shortLabel: String) {
    Ask("default", "Ask every time", "Ask"),
    AcceptEdits("acceptEdits", "Auto-accept edits", "Auto"),
    Plan("plan", "Plan first", "Plan"),
    Yolo("bypassPermissions", "YOLO, no prompts", "YOLO"),
}
