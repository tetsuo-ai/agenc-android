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
        return runCatching {
            json.decodeFromString(mapSerializer, raw).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    fun save(map: Map<String, String>) {
        prefs.setString(key, json.encodeToString(mapSerializer, map))
    }

    companion object {
        /**
         * Rebuild the daemonId -> localId inverse from a localId -> daemonId map. A loop (not a
         * uniquing collector) so a stale duplicate daemon id can't trap.
         */
        fun inverse(map: Map<String, String>): MutableMap<String, String> {
            val out = mutableMapOf<String, String>()
            for ((local, daemon) in map) out[daemon] = local
            return out
        }
    }
}
