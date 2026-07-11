package ai.tetsuo.agenc.notif

import ai.tetsuo.agenc.MainActivity
import ai.tetsuo.agenc.R
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

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
 *    needs-you / error → the high-importance attention channel (heads-up + double pulse);
 *    turn-done → the default completion channel (short pulse).
 *  - `content.badge` (needs-attention count) → `setNumber(...)` (launcher badge).
 *
 * Real REMOTE pushes (FCM) are a separate follow-up — this is local-only, which works standalone.
 */
class NotificationCoordinator(private val context: Context) {

    data class DeliveryStatus(
        val runtimePermissionGranted: Boolean,
        val appEnabled: Boolean,
        val attentionChannelEnabled: Boolean,
        val turnDoneChannelEnabled: Boolean,
    ) {
        val allEnabled: Boolean
            get() = runtimePermissionGranted && appEnabled &&
                attentionChannelEnabled && turnDoneChannelEnabled
    }

    /**
     * The kind of notification, which picks the channel + action set. Mirrors the iOS
     * `NotificationCoordinator.Category` cases.
     */
    enum class Kind { PERMISSION, INPUT, LEDGER, TURN_DONE, ERROR }

    /**
     * Sessions currently flagged as needing attention (drives the launcher badge). A session enters
     * on a permission/input/error push and clears once it's opened or resolved. Mirrors the iOS
     * `sessionsNeedingAttention` set.
     */
    private data class PostedNotification(
        val tag: String,
        val id: Int,
        val requestId: String?,
    )

    /**
     * Notification callbacks and Core's event pump run on different dispatchers. Keep every badge /
     * posted-notification mutation behind one lock so a simultaneous post and action cannot lose a
     * session or cancel the wrong request.
     */
    private val bookkeepingLock = Any()
    private val sessionsNeedingAttention = mutableSetOf<String>()
    private val attentionKeysBySession = mutableMapOf<String, MutableSet<String>>()
    private val postedBySession = mutableMapOf<String, MutableMap<String, PostedNotification>>()

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
            vibrationPattern = ATTENTION_VIBRATION_PATTERN
        }
        val turnDone = NotificationChannel(
            CHANNEL_TURN_DONE,
            "Turn complete",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A turn finished while the app was in the background."
            enableVibration(true)
            vibrationPattern = TURN_DONE_VIBRATION_PATTERN
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
    @SuppressLint("MissingPermission") // canPostTo() checks runtime, app, and channel access.
    fun post(
        title: String,
        body: String,
        sessionId: String,
        requestId: String?,
        questionId: String? = null,
        kind: Kind,
    ): Boolean {
        val channel = when (kind) {
            Kind.PERMISSION, Kind.INPUT, Kind.LEDGER, Kind.ERROR -> CHANNEL_ATTENTION
            Kind.TURN_DONE -> CHANNEL_TURN_DONE
        }
        val priority = when (kind) {
            Kind.PERMISSION, Kind.INPUT, Kind.LEDGER, Kind.ERROR -> NotificationCompat.PRIORITY_HIGH
            Kind.TURN_DONE -> NotificationCompat.PRIORITY_DEFAULT
        }

        val needsAttention = kind == Kind.PERMISSION || kind == Kind.INPUT ||
            kind == Kind.LEDGER || kind == Kind.ERROR
        val notificationKey = notificationKey(sessionId, requestId, questionId, kind)
        val notification = PostedNotification(
            tag = "$NOTIFICATION_TAG_PREFIX:$notificationKey",
            id = notificationId(notificationKey),
            requestId = requestId,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setGroup(sessionId) // iOS threadIdentifier — group every banner for this session
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(openSessionIntent(sessionId))
            .addExtras(
                Bundle().apply {
                    putString(POSTED_EXTRA_REQUEST_ID, requestId)
                },
            )

        when (kind) {
            Kind.PERMISSION ->
                addPermissionActions(builder, sessionId, requestId)
            Kind.INPUT ->
                addInputActions(builder, sessionId, requestId, questionId)
            // Hardware approval must never be replaceable by a notification RemoteInput answer.
            Kind.LEDGER ->
                builder.addAction(0, "Open Ledger review", openSessionIntent(sessionId))
            Kind.TURN_DONE ->
                addTurnDoneActions(builder, sessionId)
            Kind.ERROR -> Unit // tapping the body opens the session
        }

        if (!canPostTo(channel)) {
            val status = deliveryStatus()
            Log.w(
                LOG_TAG,
                "Delivery blocked kind=$kind " +
                    "runtime=${status.runtimePermissionGranted} app=${status.appEnabled} " +
                    "attentionChannel=${status.attentionChannelEnabled} " +
                    "doneChannel=${status.turnDoneChannelEnabled}",
            )
            return false
        }
        return synchronized(bookkeepingLock) {
            // Use the prospective count in the notification itself, but mutate our authoritative
            // state only after NotificationManager accepts the post.
            val prospectiveBadge = sessionsNeedingAttention.size +
                if (needsAttention && sessionId !in sessionsNeedingAttention) 1 else 0
            builder.setNumber(prospectiveBadge)
            try {
                manager.notify(notification.tag, notification.id, builder.build())
                postedBySession
                    .getOrPut(sessionId) { mutableMapOf() }[notificationKey] = notification
                if (needsAttention) {
                    attentionKeysBySession
                        .getOrPut(sessionId) { mutableSetOf() }
                        .add(notificationKey)
                    sessionsNeedingAttention.add(sessionId)
                }
                Log.i(LOG_TAG, "Posted kind=$kind")
                true
            } catch (error: RuntimeException) {
                Log.w(LOG_TAG, "Post failed kind=$kind", error)
                false
            }
        }
    }

    /** Allow once / Allow for session / Deny — the iOS PERMISSION category buttons. */
    private fun addPermissionActions(
        builder: NotificationCompat.Builder,
        sessionId: String,
        requestId: String?,
    ) {
        builder.addAction(
            0, "Allow once",
            actionIntent(NotificationActionReceiver.ACTION_ALLOW_ONCE, sessionId, requestId),
        )
        builder.addAction(
            0, "Allow all for session",
            actionIntent(NotificationActionReceiver.ACTION_ALLOW_SESSION, sessionId, requestId),
        )
        builder.addAction(
            0, "Deny",
            actionIntent(NotificationActionReceiver.ACTION_DENY, sessionId, requestId),
        )
    }

    /** A typed INPUT answer is an elicitation response, not a new chat message. */
    private fun addInputActions(
        builder: NotificationCompat.Builder,
        sessionId: String,
        requestId: String?,
        questionId: String?,
    ) {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel("Answer your agent…")
            .build()
        val answerIntent = actionIntent(
            action = NotificationActionReceiver.ACTION_RESPOND_INPUT,
            sessionId = sessionId,
            requestId = requestId,
            questionId = questionId,
            mutable = true,
        )
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Answer", answerIntent)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build(),
        )
        builder.addAction(0, "Open", openSessionIntent(sessionId))
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
            NotificationActionReceiver.ACTION_REPLY,
            sessionId,
            requestId = null,
            questionId = null,
            mutable = true,
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
    fun clearAttention(sessionId: String, requestId: String? = null) {
        synchronized(bookkeepingLock) {
            val posted = postedBySession[sessionId]
            if (posted != null) {
                val matching = posted.entries
                    .filter { requestId == null || it.value.requestId == requestId }
                val attentionKeys = attentionKeysBySession[sessionId]
                matching.forEach { (key, notification) ->
                    manager.cancel(notification.tag, notification.id)
                    posted.remove(key)
                    attentionKeys?.remove(key)
                }
                if (posted.isEmpty()) postedBySession.remove(sessionId)
            }

            // The process may have been cold-started by a notification action, in which case the
            // in-memory map is empty but Android still owns the banner. Query our active posts and
            // cancel the matching request by its persisted notification extras.
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.asSequence()
                    ?.filter { it.notification.group == sessionId }
                    ?.filter {
                        requestId == null ||
                            it.notification.extras.getString(POSTED_EXTRA_REQUEST_ID) == requestId
                    }
                    ?.forEach { manager.cancel(it.tag, it.id) }
            }

            val attentionKeys = attentionKeysBySession[sessionId]
            if (attentionKeys.isNullOrEmpty()) {
                attentionKeysBySession.remove(sessionId)
                sessionsNeedingAttention.remove(sessionId)
            }
            Unit
        }
    }

    /** Current Android permission/app/channel delivery state for the Account screen. */
    fun deliveryStatus(): DeliveryStatus {
        val runtimeGranted = runtimePermissionGranted()
        val appEnabled = manager.areNotificationsEnabled()
        val sys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
        } else {
            null
        }
        fun channelEnabled(id: String): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                (sys?.getNotificationChannel(id)?.importance
                    ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE

        return DeliveryStatus(
            runtimePermissionGranted = runtimeGranted,
            appEnabled = appEnabled,
            attentionChannelEnabled = channelEnabled(CHANNEL_ATTENTION),
            turnDoneChannelEnabled = channelEnabled(CHANNEL_TURN_DONE),
        )
    }

    /** Post a harmless completion-channel notification so Account can verify real delivery. */
    @SuppressLint("MissingPermission") // canPostTo() checks runtime, app, and channel access.
    fun postTestNotification(): Boolean {
        ensureChannels()
        if (!canPostTo(CHANNEL_TURN_DONE)) return false
        val builder = NotificationCompat.Builder(context, CHANNEL_TURN_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AgenC notifications are working")
            .setContentText("This device can receive agent updates.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        return try {
            manager.notify(TEST_NOTIFICATION_TAG, TEST_NOTIFICATION_ID, builder.build())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Remove every AgenC notification and reset badge bookkeeping. */
    fun clearAll() {
        synchronized(bookkeepingLock) {
            manager.cancelAll()
            postedBySession.clear()
            attentionKeysBySession.clear()
            sessionsNeedingAttention.clear()
        }
    }

    // MARK: - PendingIntents

    /** A content/Open intent that launches MainActivity and routes it to open [sessionId]. */
    private fun openSessionIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION
            // PendingIntent identity ignores extras. Binding the exact session in data prevents a
            // completion for one chat from ever reusing another chat's open intent after updates.
            data = Uri.Builder()
                .scheme(OPEN_SESSION_SCHEME)
                .authority(OPEN_SESSION_HOST)
                .appendPath(sessionId)
                .build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(sessionId, "open", requestId = null, questionId = null),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** A broadcast intent routed to [NotificationActionReceiver] for a button tap. */
    private fun actionIntent(
        action: String,
        sessionId: String,
        requestId: String?,
        questionId: String? = null,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            // PendingIntent identity ignores extras. A unique data URI prevents Android from
            // treating two requests in the same session as the same action and overwriting them.
            data = Uri.Builder()
                .scheme("agenc")
                .authority("notification-action")
                .appendPath(action)
                .appendQueryParameter("session", sessionId)
                .appendQueryParameter("request", requestId.orEmpty())
                .appendQueryParameter("question", questionId.orEmpty())
                .build()
            putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(NotificationActionReceiver.EXTRA_QUESTION_ID, questionId)
        }
        // RemoteInput (Reply) requires a MUTABLE PendingIntent so the system can inject the text.
        val flags = if (mutable) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(sessionId, action, requestId, questionId),
            intent,
            flags,
        )
    }

    private fun runtimePermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun canPostTo(channelId: String): Boolean {
        val status = deliveryStatus()
        val channelEnabled = when (channelId) {
            CHANNEL_ATTENTION -> status.attentionChannelEnabled
            CHANNEL_TURN_DONE -> status.turnDoneChannelEnabled
            else -> true
        }
        return status.runtimePermissionGranted && status.appEnabled && channelEnabled
    }

    private fun notificationKey(
        sessionId: String,
        requestId: String?,
        questionId: String?,
        kind: Kind,
    ): String = listOf(sessionId, kind.name, requestId.orEmpty(), questionId.orEmpty())
        .joinToString("|")

    /** Tag + id are both request-scoped, so concurrent prompts in one session cannot replace one another. */
    private fun notificationId(notificationKey: String): Int =
        NOTIFICATION_ID_BASE or (notificationKey.hashCode() and NOTIFICATION_ID_MASK)

    /** The data URI supplies exact identity; this request code remains stable for Android bookkeeping. */
    private fun requestCode(
        sessionId: String,
        action: String,
        requestId: String?,
        questionId: String?,
    ): Int = listOf(sessionId, action, requestId.orEmpty(), questionId.orEmpty())
        .joinToString("|")
        .hashCode() and 0x7FFFFFFF

    companion object {
        // Channel behavior is immutable after creation, so vibration changes require versioned IDs.
        const val CHANNEL_ATTENTION = "attention_v2"
        const val CHANNEL_TURN_DONE = "turn_done_v2"

        /** MainActivity reads this extra to open the tapped session. */
        const val EXTRA_OPEN_SESSION_ID = "open_session_id"
        const val OPEN_SESSION_SCHEME = "agenc"
        const val OPEN_SESSION_HOST = "session"
        private const val ACTION_OPEN_SESSION = "ai.tetsuo.agenc.notif.OPEN_SESSION"

        private val ATTENTION_VIBRATION_PATTERN = longArrayOf(0L, 90L, 60L, 150L)
        private val TURN_DONE_VIBRATION_PATTERN = longArrayOf(0L, 70L)
        private const val NOTIFICATION_ID_BASE = 0x0A00_0000
        private const val NOTIFICATION_ID_MASK = 0x00FF_FFFF
        private const val NOTIFICATION_TAG_PREFIX = "agenc"
        private const val LOG_TAG = "AgenCNotification"
        private const val POSTED_EXTRA_REQUEST_ID = "agenc_notification_request_id"
        private const val TEST_NOTIFICATION_TAG = "agenc:test"
        private const val TEST_NOTIFICATION_ID = NOTIFICATION_ID_BASE - 1
    }
}
