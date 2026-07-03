package ai.tetsuo.agenc.notif

import ai.tetsuo.agenc.MainActivity
import ai.tetsuo.agenc.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Android port of the iOS app's `NotificationCoordinator` — rich, actionable LOCAL notifications.
 *
 * The app posts a rich local notification when a permission/input request arrives (or a turn
 * finishes / errors) WHILE BACKGROUNDED, so a turn blocked on approval pulls the user back — and
 * they can Allow once / Allow for session / Deny / Reply straight from the banner.
 *
 * iOS → Android mapping:
 *  - UNNotificationCategory action buttons → [android.app.PendingIntent] into
 *    [NotificationActionReceiver] (plus a [RemoteInput] for the turn-done Reply).
 *  - `threadIdentifier` (session grouping) → `setGroup(sessionId)`.
 *  - `interruptionLevel` (`.timeSensitive` vs `.active`) → the channel IMPORTANCE:
 *    needs-you / error → the high-importance "attention" channel (heads-up banner);
 *    turn-done → the default "turn_done" channel (quiet).
 *  - `content.badge` (needs-attention count) → `setNumber(...)` (launcher badge).
 *
 * Real REMOTE pushes (FCM) are a separate follow-up — this is local-only, which works standalone.
 */
class NotificationCoordinator(private val context: Context) {

    /**
     * The kind of notification, which picks the channel + action set. Mirrors the iOS
     * `NotificationCoordinator.Category` cases.
     */
    enum class Kind { PERMISSION, INPUT, TURN_DONE, ERROR }

    /**
     * Sessions currently flagged as needing attention (drives the launcher badge). A session enters
     * on a permission/input/error push and clears once it's opened or resolved. Mirrors the iOS
     * `sessionsNeedingAttention` set.
     */
    private val sessionsNeedingAttention = mutableSetOf<String>()

    private val manager get() = NotificationManagerCompat.from(context)

    /** Ensure the two channels exist. Idempotent — safe to call on every app start. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sys = context.getSystemService(NotificationManager::class.java) ?: return
        val attention = NotificationChannel(
            CHANNEL_ATTENTION,
            "Needs you & errors",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "The agent needs a decision, or a turn errored."
            enableVibration(true)
        }
        val turnDone = NotificationChannel(
            CHANNEL_TURN_DONE,
            "Turn complete",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A turn finished while the app was in the background."
        }
        sys.createNotificationChannels(listOf(attention, turnDone))
    }

    /**
     * Post a rich, actionable local notification. Ungated — the CALLER (DaemonHub) picks the relevant
     * toggle and the [kind], so one method serves every notification. The kind drives the channel +
     * action buttons; the group threads a session's banners; the badge counts needs-you/error/input.
     *
     * @param requestId the pending permission/input requestId, needed by the action buttons.
     */
    fun post(
        title: String,
        body: String,
        sessionId: String,
        requestId: String?,
        kind: Kind,
    ) {
        val channel = when (kind) {
            Kind.PERMISSION, Kind.INPUT, Kind.ERROR -> CHANNEL_ATTENTION
            Kind.TURN_DONE -> CHANNEL_TURN_DONE
        }
        val priority = when (kind) {
            Kind.PERMISSION, Kind.INPUT, Kind.ERROR -> NotificationCompat.PRIORITY_HIGH
            Kind.TURN_DONE -> NotificationCompat.PRIORITY_DEFAULT
        }

        // Banners that need a decision count toward the badge; a finished turn doesn't.
        if (kind == Kind.PERMISSION || kind == Kind.INPUT || kind == Kind.ERROR) {
            sessionsNeedingAttention.add(sessionId)
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setGroup(sessionId) // iOS threadIdentifier — group every banner for this session
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(openSessionIntent(sessionId))
            .setNumber(sessionsNeedingAttention.size)

        when (kind) {
            Kind.PERMISSION, Kind.INPUT ->
                addPermissionActions(builder, sessionId, requestId, kind)
            Kind.TURN_DONE ->
                addTurnDoneActions(builder, sessionId)
            Kind.ERROR -> Unit // tapping the body opens the session
        }

        if (hasPostPermission()) {
            manager.notify(notificationId(sessionId), builder.build())
        }
    }

    /** Allow once / Allow for session / Deny — the iOS PERMISSION / INPUT category buttons. */
    private fun addPermissionActions(
        builder: NotificationCompat.Builder,
        sessionId: String,
        requestId: String?,
        kind: Kind,
    ) {
        // INPUT requests can't be Allowed/Denied — they need a typed answer, so surface Reply + Open.
        if (kind == Kind.INPUT) {
            addTurnDoneActions(builder, sessionId, replyLabel = "Answer")
            return
        }
        builder.addAction(
            0, "Allow once",
            actionIntent(NotificationActionReceiver.ACTION_ALLOW_ONCE, sessionId, requestId),
        )
        builder.addAction(
            0, "Allow for session",
            actionIntent(NotificationActionReceiver.ACTION_ALLOW_SESSION, sessionId, requestId),
        )
        builder.addAction(
            0, "Deny",
            actionIntent(NotificationActionReceiver.ACTION_DENY, sessionId, requestId),
        )
    }

    /** Reply (RemoteInput text) + Open — the iOS TURN_DONE category buttons. */
    private fun addTurnDoneActions(
        builder: NotificationCompat.Builder,
        sessionId: String,
        replyLabel: String = "Reply",
    ) {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply to your agent…")
            .build()
        val replyIntent = actionIntent(
            NotificationActionReceiver.ACTION_REPLY, sessionId, requestId = null, mutable = true,
        )
        val replyAction = NotificationCompat.Action.Builder(0, replyLabel, replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
        builder.addAction(replyAction)
        builder.addAction(0, "Open", openSessionIntent(sessionId))
    }

    /**
     * Drop a session from the "needs attention" set (it was opened, or its request resolved), clear
     * its posted banners, and refresh the launcher badge. Mirrors the iOS `clearAttention`.
     */
    fun clearAttention(sessionId: String) {
        val removed = sessionsNeedingAttention.remove(sessionId)
        if (hasPostPermission()) manager.cancel(notificationId(sessionId))
        if (!removed) return
        // The launcher badge is derived from active notifications; canceling this session's banner
        // above already drops its contribution.
    }

    // MARK: - PendingIntents

    /** A content/Open intent that launches MainActivity and routes it to open [sessionId]. */
    private fun openSessionIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(sessionId, "open"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** A broadcast intent routed to [NotificationActionReceiver] for a button tap. */
    private fun actionIntent(
        action: String,
        sessionId: String,
        requestId: String?,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, requestId)
        }
        // RemoteInput (Reply) requires a MUTABLE PendingIntent so the system can inject the text.
        val flags = if (mutable) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context, requestCode(sessionId, action), intent, flags,
        )
    }

    private fun hasPostPermission(): Boolean = manager.areNotificationsEnabled()

    /** Stable per-session notification id so a session's banners replace one another. */
    private fun notificationId(sessionId: String): Int =
        (NOTIFICATION_ID_BASE + (sessionId.hashCode() and 0xFFFF))

    /** Distinct request codes per (session, action) so PendingIntents don't collide/overwrite. */
    private fun requestCode(sessionId: String, action: String): Int =
        (sessionId.hashCode() * 31 + action.hashCode()) and 0x7FFFFFFF

    companion object {
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_TURN_DONE = "turn_done"

        /** MainActivity reads this extra to open the tapped session. */
        const val EXTRA_OPEN_SESSION_ID = "open_session_id"

        private const val NOTIFICATION_ID_BASE = 0x0A00_0000
    }
}
