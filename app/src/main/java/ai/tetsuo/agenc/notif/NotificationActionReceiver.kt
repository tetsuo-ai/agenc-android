package ai.tetsuo.agenc.notif

import ai.tetsuo.agenc.data.AppGraph
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android port of the iOS `userNotificationCenter(_:didReceive:)` action routing.
 *
 * Receives the Allow once / Allow for session / Deny / Reply button taps from a local notification,
 * decodes {sessionId, requestId, questionId} (and RemoteInput text), and routes them through
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
        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID)
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()

        // Ensure the app graph is wired even if the process was cold-started by the tap.
        AppGraph.init(context.applicationContext)
        val hub = AppGraph.hub

        // Route through the hub on a coroutine, after (re)connecting — mirrors iOS `perform`.
        val pending = goAsync()
        scope.launch {
            try {
                // A stale banner must never make a signed-out cold process fall back to loopback.
                // When cold but authenticated, re-mint the short-lived relay credential first and
                // re-point the hub only after AuthManager safely commits that same auth generation.
                if (!AppGraph.auth.isSignedIn) return@launch
                if (hub.state.value !is ai.tetsuo.agenc.data.DaemonHub.ConnectionState.Connected &&
                    AppGraph.auth.refreshRelayTicket()
                ) {
                    AppGraph.reconfigureForCurrentTicket()
                }
                hub.ensureConnected()
                val resolved = when (action) {
                    ACTION_ALLOW_ONCE ->
                        requestId?.let { hub.approveAwait("once", sessionId, it) } ?: false
                    ACTION_ALLOW_SESSION ->
                        requestId?.let { hub.approveAwait("session", sessionId, it) } ?: false
                    ACTION_DENY ->
                        requestId?.let { hub.denyAwait(sessionId, it) } ?: false
                    ACTION_REPLY -> {
                        if (!replyText.isNullOrBlank()) {
                            hub.send(text = replyText, sessionId = sessionId)
                            true
                        } else {
                            false
                        }
                    }
                    ACTION_RESPOND_INPUT -> {
                        if (!replyText.isNullOrBlank() && requestId != null && questionId != null) {
                            hub.respondElicitationAwait(
                                sessionId = sessionId,
                                requestId = requestId,
                                questionId = questionId,
                                answer = replyText,
                            )
                        } else {
                            false
                        }
                    }
                    else -> false
                }
                // Do not remove the actionable banner until Core authoritatively accepts the tap.
                if (resolved && action != ACTION_REPLY) {
                    AppGraph.notifications.clearAttention(sessionId, requestId)
                }
            } catch (error: Throwable) {
                android.util.Log.w("NotificationAction", "Couldn't execute notification action", error)
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
        const val ACTION_RESPOND_INPUT = "ai.tetsuo.agenc.notif.RESPOND_INPUT"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_QUESTION_ID = "question_id"
        const val KEY_REPLY_TEXT = "reply_text"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
