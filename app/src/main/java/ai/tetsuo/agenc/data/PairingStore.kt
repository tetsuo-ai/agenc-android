package ai.tetsuo.agenc.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Direct port of the iOS app's PairingStore.swift.
 *
 * The Mac this phone is paired with — the durable anchor. The short-lived relay client ticket is
 * re-minted from `pairingId` on every connect (AuthManager), so this store only persists the ids +
 * display name.
 */
class PairingStore(private val prefs: Prefs) {

    @Serializable
    data class Pairing(
        val pairingId: String,
        val machineName: String,
        val relayUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val current: Pairing?
        get() {
            val raw = prefs.getString(Prefs.KEY_PAIRING) ?: return null
            return runCatching { json.decodeFromString(Pairing.serializer(), raw) }.getOrNull()
        }

    fun set(pairing: Pairing) {
        prefs.setString(Prefs.KEY_PAIRING, json.encodeToString(Pairing.serializer(), pairing))
    }

    fun clear() {
        prefs.remove(Prefs.KEY_PAIRING)
    }
}
