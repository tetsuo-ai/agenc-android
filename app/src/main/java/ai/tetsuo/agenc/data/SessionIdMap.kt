package ai.tetsuo.agenc.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Direct port of the iOS app's SessionIdMap.swift.
 *
 * On-disk mirror of the local-UI-session-id ↔ daemon-session-id binding. A chat the app creates
 * gets a bare UUID local id (no `session_` prefix) and only learns its daemon `session_<uuid>`
 * after the first message's `agent.create`. Persisting the binding lets a continued chat RESUME
 * the same daemon session instead of forking a brand-new agent on relaunch.
 */
class SessionIdMap(
    private val prefs: Prefs,
    private val key: String = Prefs.KEY_IDMAP,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    /** localId -> daemonId */
    fun load(): MutableMap<String, String> {
        val raw = prefs.getString(key) ?: return mutableMapOf()
        val decoded = runCatching {
            json.decodeFromString(mapSerializer, raw).toMutableMap()
        }.getOrElse { mutableMapOf() }
        val normalized = normalize(decoded)
        if (normalized != decoded) save(normalized)
        return normalized
    }

    fun save(map: Map<String, String>) {
        prefs.setString(key, json.encodeToString(mapSerializer, map))
    }

    companion object {
        /**
         * Migrate maps written by the old discovery path. It could persist both
         * `local -> daemon` and `daemon -> daemon`, making the inverse depend on map order.
         * A real local binding always wins over the redundant self binding.
         */
        fun normalize(map: Map<String, String>): MutableMap<String, String> {
            val normalized = map.toMutableMap()
            val nonSelfTargets = map.asSequence()
                .filter { (local, daemon) -> local != daemon }
                .map { (_, daemon) -> daemon }
                .toSet()
            for (daemon in nonSelfTargets) {
                if (normalized[daemon] == daemon) normalized.remove(daemon)
            }
            return normalized
        }

        /**
         * Rebuild the daemonId -> localId inverse from a localId -> daemonId map. A loop (not a
         * uniquing collector) so a stale duplicate daemon id can't trap.
         */
        fun inverse(map: Map<String, String>): MutableMap<String, String> {
            val out = mutableMapOf<String, String>()
            for ((local, daemon) in normalize(map)) out[daemon] = local
            return out
        }
    }
}
