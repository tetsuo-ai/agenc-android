package ai.tetsuo.agenc.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

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

    fun getString(key: String): String? = prefs.getString(key, null)

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    // MARK: - Notification settings (Android port of iOS AppSettings notify toggles + intelligence)

    /** Pull the user back when the agent needs a decision (permission / input) while backgrounded. */
    var notifyAttention: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ATTENTION, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_ATTENTION, v).apply()

    /** Notify when a turn finishes while backgrounded (off by default — chattier than needs-you). */
    var notifyTurnDone: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_TURN, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_TURN, v).apply()

    /** Notify when a turn errors while backgrounded. */
    var notifyErrors: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ERRORS, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_ERRORS, v).apply()

    /**
     * A turn must run at least this many seconds before a "turn complete" push fires — quick
     * round-trips aren't worth a banner. Default 20s.
     */
    var turnDoneThresholdSeconds: Int
        get() = prefs.getInt(KEY_TURN_THRESHOLD, 20)
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
        const val KEY_RELAY_URL = "agenc.relay.url"
        const val KEY_IDMAP = "agenc.idmap.local"
        const val KEY_SESSIONS = "agenc.sessions.local"
        const val KEY_PAIRING = "agenc.pairing.current"

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
