package ai.tetsuo.agenc.notif

import ai.tetsuo.agenc.MainActivity
import ai.tetsuo.agenc.R
import ai.tetsuo.agenc.data.AppGraph
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground owner for the relay/Core WebSocket while AgenC's Activity is backgrounded.
 *
 * The service never owns auth state, sessions, or chat state. It only keeps the process alive and
 * nudges the existing single-flight [DaemonHub] connection/reconnect machinery. Returning to the
 * Activity stops it without disconnecting the socket, so foreground operation continues seamlessly.
 */
class BackgroundLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null
    private var relayTicketJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Android requires promotion within five seconds. No dependency/auth initialization may
        // delay channel creation + startForeground on a cold service process.
        ensureChannel()
        promoteToForeground()
        AppGraph.init(applicationContext)
        Log.i(LOG_TAG, "Background Core link service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun()) {
            Log.i(LOG_TAG, "Background Core link no longer eligible; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (keepAliveJob?.isActive != true) {
            keepAliveJob = scope.launch { maintainConnection() }
        }
        if (relayTicketJob?.isActive != true) {
            relayTicketJob = scope.launch { observeRelayTickets() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        keepAliveJob?.cancel()
        relayTicketJob?.cancel()
        scope.cancel()
        Log.i(LOG_TAG, "Background Core link service stopped")
        super.onDestroy()
    }

    private suspend fun maintainConnection() {
        while (scope.isActive && shouldRun()) {
            val hub = AppGraph.hub
            val observedState = hub.state.value
            // DaemonHub owns exponential reconnect. The service only keeps the process alive and
            // watches eligibility; it must not flatten that backoff with a hard retry loop.
            withTimeoutOrNull(BackgroundLinkPolicy.WATCHDOG_MILLIS) {
                hub.state.first { it != observedState }
            }
        }
        if (!shouldRun()) stopSelf()
    }

    private suspend fun observeRelayTickets() {
        AppGraph.auth.relayTicketVersion.collectLatest {
            if (!shouldRun()) return@collectLatest
            runCatching { AppGraph.reconfigureForCurrentTicket() }
                .onFailure { Log.w(LOG_TAG, "Couldn't apply refreshed relay ticket", it) }
        }
    }

    private fun shouldRun(): Boolean = BackgroundLinkPolicy.shouldRun(
        appInForeground = AppGraph.hub.isAppInForeground,
        signedIn = AppGraph.auth.isSignedIn,
        pairedMachine = AppGraph.auth.pairedMachine.value != null,
    )

    private fun promoteToForeground() {
        val openApp = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_APP
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AgenC is connected")
            .setContentText("Keeping your Core sessions online")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background Core connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps paired AgenC Core sessions connected in the background."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val LOG_TAG = "AgenCBackgroundLink"
        private const val CHANNEL_ID = "background_core_link_v1"
        private const val NOTIFICATION_ID = 0x0A10_0001
        private const val ACTION_OPEN_APP = "ai.tetsuo.agenc.notif.OPEN_APP"

        fun startIfEligible(context: Context): Boolean {
            AppGraph.init(context.applicationContext)
            if (!BackgroundLinkPolicy.shouldRun(
                    appInForeground = AppGraph.hub.isAppInForeground,
                    signedIn = AppGraph.auth.isSignedIn,
                    pairedMachine = AppGraph.auth.pairedMachine.value != null,
                )
            ) {
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BackgroundLinkService::class.java),
                )
                true
            } catch (error: RuntimeException) {
                // Android 12+ can reject a start that is no longer inside the visible→background
                // exemption; never crash the Activity during that race.
                Log.w(LOG_TAG, "Couldn't start background Core link", error)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundLinkService::class.java))
        }
    }
}
