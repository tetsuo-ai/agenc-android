package ai.tetsuo.agenc.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain (unencrypted) key-value store mirroring iOS `UserDefaults`.
 *
 * Holds non-secret persisted state: cached identity, the local↔daemon session-id map,
 * the local session registry, per-session transcript blobs, the active relay URL, and the
 * current pairing. Secrets (bearer + relay ticket) live in [SecureStore] instead.
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

        /** Per-session persisted transcript blob key. */
        fun transcriptKey(sessionId: String): String = "agenc.tx.$sessionId"
    }
}
