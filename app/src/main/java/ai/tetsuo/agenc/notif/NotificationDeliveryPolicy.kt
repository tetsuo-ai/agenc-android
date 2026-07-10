package ai.tetsuo.agenc.notif

/**
 * Pure delivery gate for completion notifications.
 *
 * Keeping this decision independent from Android framework state makes the foreground/background,
 * mute, quiet-hours, and optional duration rules deterministic and unit-testable. A missing duration
 * is valid when no minimum is configured: Core can legitimately report an authoritative completion
 * without first replaying the turn-start event after a reconnect.
 */
internal object NotificationDeliveryPolicy {
    fun shouldPostCompletion(
        backgrounded: Boolean,
        enabled: Boolean,
        muted: Boolean,
        quietHours: Boolean,
        durationSeconds: Int?,
        minimumDurationSeconds: Int,
    ): Boolean {
        if (!backgrounded || !enabled || muted || quietHours) return false
        val minimum = minimumDurationSeconds.coerceAtLeast(0)
        return minimum == 0 || (durationSeconds != null && durationSeconds >= minimum)
    }
}
