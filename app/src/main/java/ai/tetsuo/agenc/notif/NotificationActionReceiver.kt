package ai.tetsuo.agenc.notif

import ai.tetsuo.agenc.data.AppGraph
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Android port of the iOS `userNotificationCenter(_:didReceive:)` action routing.
 *
 * Receives the Allow once / Allow for session / Deny / Reply button taps from a local notification,
 * decodes {sessionId, requestId} (and the RemoteInput reply text for Reply), and routes them through
 * [AppGraph.hub] — exactly like iOS `PendingNotificationAction` → `DaemonHub.perform`. It first calls
 * [DaemonHub.ensureConnected] because on a cold launch the socket isn't up yet when the tap arrives.
 *
 * Registered (not exported) in the manifest so only this app's PendingIntents can trigger it.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()

        // Ensure the app graph is wired even if the process was cold-started by the tap.
        AppGraph.init(context.applicationContext)
        val hub = AppGraph.hub

        // Opening / resolving the session clears its attention badge.
        AppGraph.notifications.clearAttention(sessionId)

        // Route through the hub on a coroutine, after (re)connecting — mirrors iOS `perform`.
        val pending = goAsync()
        scope.launch {
            try {
                hub.ensureConnected()
                when (action) {
                    ACTION_ALLOW_ONCE ->
                        if (requestId != null) hub.approve("once", sessionId, requestId)
                    ACTION_ALLOW_SESSION ->
                        if (requestId != null) hub.approve("session", sessionId, requestId)
                    ACTION_DENY ->
                        if (requestId != null) hub.deny(sessionId, requestId)
                    ACTION_REPLY ->
                        if (!replyText.isNullOrBlank()) hub.send(text = replyText, sessionId = sessionId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALLOW_ONCE = "ai.tetsuo.agenc.notif.ALLOW_ONCE"
        const val ACTION_ALLOW_SESSION = "ai.tetsuo.agenc.notif.ALLOW_SESSION"
        const val ACTION_DENY = "ai.tetsuo.agenc.notif.DENY"
        const val ACTION_REPLY = "ai.tetsuo.agenc.notif.REPLY"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val KEY_REPLY_TEXT = "reply_text"

        private val scope = CoroutineScope(Job() + Dispatchers.Default)
    }
}
