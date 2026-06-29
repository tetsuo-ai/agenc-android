package ai.tetsuo.agenc.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android counterpart of the iOS app's KeychainStore.swift.
 *
 * Backs the opaque bearer token + relay client ticket with [EncryptedSharedPreferences]
 * (AES-256, master key in the AndroidKeyStore) — the closest equivalent to the iOS Keychain's
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: available after first unlock, never synced
 * off-device. The same `get/set/delete(account:)` surface the Swift code used.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun set(value: String, account: String) {
        prefs.edit().putString(account, value).apply()
    }

    fun get(account: String): String? = prefs.getString(account, null)

    fun delete(account: String) {
        prefs.edit().remove(account).apply()
    }

    companion object {
        private const val FILE_NAME = "agenc.secure.prefs"

        // Account keys (mirror AuthManager.Keys in the iOS app).
        const val KEY_BEARER = "bearer"
        const val KEY_RELAY_TICKET = "relay.ticket"
    }
}
