package ai.tetsuo.agenc.data

/** Bounded replay/terminal guard for global `event.agent_status` delivery. */
internal class AgentStatusDeliveryTracker(
    private val maxSeenEventIds: Int = 2_048,
    private val maxCompletedSessions: Int = 1_024,
) {
    private val seenEventIds = LinkedHashSet<String>()
    private val activeTurnBySession = mutableMapOf<String, String>()
    private val lastCompletedTurnBySession = LinkedHashMap<String, String>()

    @Synchronized
    fun acceptEvent(sessionId: String, eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return true
        val key = "$sessionId:$eventId"
        if (!seenEventIds.add(key)) return false
        while (seenEventIds.size > maxSeenEventIds.coerceAtLeast(1)) {
            seenEventIds.remove(seenEventIds.first())
        }
        return true
    }

    /** False means a delayed running frame belongs to the turn already completed. */
    @Synchronized
    fun markRunning(sessionId: String, turnId: String?): Boolean {
        val normalized = turnId?.takeIf { it.isNotBlank() } ?: return true
        if (lastCompletedTurnBySession[sessionId] == normalized) return false
        activeTurnBySession[sessionId] = normalized
        return true
    }

    @Synchronized
    fun shouldComplete(sessionId: String, turnId: String?, wasInFlight: Boolean): Boolean {
        val normalized = turnId?.takeIf { it.isNotBlank() }
        if (normalized != null && lastCompletedTurnBySession[sessionId] == normalized) {
            if (activeTurnBySession[sessionId] == normalized) activeTurnBySession.remove(sessionId)
            return false
        }
        val activeTurn = activeTurnBySession[sessionId]
        if (normalized == null && activeTurn == null && !wasInFlight) return false
        val completedTurn = normalized ?: activeTurn
        activeTurnBySession.remove(sessionId)
        if (completedTurn != null) {
            lastCompletedTurnBySession.remove(sessionId)
            lastCompletedTurnBySession[sessionId] = completedTurn
            while (lastCompletedTurnBySession.size > maxCompletedSessions.coerceAtLeast(1)) {
                lastCompletedTurnBySession.remove(lastCompletedTurnBySession.keys.first())
            }
        }
        return true
    }

    @Synchronized fun clearRunning(sessionId: String) { activeTurnBySession.remove(sessionId) }

    @Synchronized
    fun clearSession(sessionId: String) {
        activeTurnBySession.remove(sessionId)
        lastCompletedTurnBySession.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        seenEventIds.clear()
        activeTurnBySession.clear()
        lastCompletedTurnBySession.clear()
    }
}
