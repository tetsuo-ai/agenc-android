package ai.tetsuo.agenc

import ai.tetsuo.agenc.data.AppGraph
import ai.tetsuo.agenc.data.AuthManager
import ai.tetsuo.agenc.data.DaemonHub
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.notif.NotificationCoordinator
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.chat.ChatScreen
import ai.tetsuo.agenc.ui.screens.AppRoute
import ai.tetsuo.agenc.ui.screens.DeviceCodeUi
import ai.tetsuo.agenc.ui.screens.MainTab
import ai.tetsuo.agenc.ui.screens.RootNav
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    /** The session a tapped notification asked to open, surfaced to Compose. Nulled once routed. */
    private val pendingOpenSessionId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)        // wire AuthManager + DaemonHub for the process lifetime
        consumeOpenSessionExtra(intent)          // a cold launch from a notification tap
        enableEdgeToEdge()
        setContent { AgenCTheme { AgenCRoot(pendingOpenSessionId) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenSessionExtra(intent)          // a warm launch (SINGLE_TOP) from a notification tap
    }

    /** Pull the notification's target session id off the launch intent and hand it to Compose. */
    private fun consumeOpenSessionExtra(intent: Intent?) {
        val sid = intent?.getStringExtra(NotificationCoordinator.EXTRA_OPEN_SESSION_ID) ?: return
        intent.removeExtra(NotificationCoordinator.EXTRA_OPEN_SESSION_ID)
        pendingOpenSessionId.value = sid
    }
}

/**
 * The single source of truth for which screen shows: the auth state + paired-machine drive the
 * route (Login → Pairing → Main), and an opened session overlays the Chat screen. All screens are
 * stateless composables; this is the only place the data layer (AppGraph) is read.
 */
@Composable
fun AgenCRoot(pendingOpen: MutableStateFlow<String?> = MutableStateFlow(null)) {
    val auth = AppGraph.auth
    val hub = AppGraph.hub
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val authState by auth.state.collectAsState()
    val lastError by auth.lastError.collectAsState()
    val pairedMachine by auth.pairedMachine.collectAsState()
    val relayTicketVersion by auth.relayTicketVersion.collectAsState()
    val sessions by hub.sessions.collectAsState()
    val pendingOpenId by pendingOpen.collectAsState()

    var selected by remember { mutableStateOf<SessionSummary?>(null) }
    var mainTab by remember { mutableStateOf(MainTab.Sessions) }
    var startingLogin by remember { mutableStateOf(false) }
    var isLinking by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    // Prefs isn't reactive, so mirror the muted set in Compose state (seeded from prefs) and write both.
    var mutedSessions by remember { mutableStateOf(AppGraph.prefs.mutedSessions) }

    val signedIn = authState is AuthManager.State.SignedIn
    val route = when {
        signedIn && pairedMachine != null -> AppRoute.Main
        signedIn -> AppRoute.Pairing
        else -> AppRoute.Login
    }

    // Ask for POST_NOTIFICATIONS once on Android 13+ so local notifications can actually show.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted-or-not; nothing to do — the poster no-ops when denied */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A tapped notification wants a session opened: match it (or synthesize a stub) and route.
    LaunchedEffect(pendingOpenId, sessions, route) {
        val sid = pendingOpenId ?: return@LaunchedEffect
        if (route != AppRoute.Main) return@LaunchedEffect
        val target = sessions.firstOrNull { it.id == sid }
            ?: SessionSummary(
                id = sid, title = "Chat", lifecycle = SessionLifecycle.Idle,
                cwd = "", subtitle = "", updated = "now",
            )
        selected = target
        pendingOpen.value = null
    }

    // Re-point the connection when the relay ticket changes (login / pairing / unlink).
    LaunchedEffect(relayTicketVersion) { AppGraph.reconfigureForCurrentTicket() }
    // Connect + load sessions whenever we're in the Main flow.
    LaunchedEffect(route, relayTicketVersion) {
        if (route == AppRoute.Main) {
            hub.ensureConnected()
            hub.refreshSessions()
        }
    }
    // Clear the transient "starting login" spinner once the backend responds.
    LaunchedEffect(authState, lastError) {
        if (authState !is AuthManager.State.SignedOut || lastError != null) startingLogin = false
    }

    // Opened session → Chat overlay.
    val open = selected
    if (route == AppRoute.Main && open != null) {
        ChatHost(session = open, hub = hub) { selected = null; hub.detach(open.id) }
        return
    }

    val deviceCode = (authState as? AuthManager.State.Polling)
        ?.let { DeviceCodeUi(it.userCode, it.verificationUri) }
    val identity = (authState as? AuthManager.State.SignedIn)?.identity

    RootNav(
        route = route,
        deviceCode = deviceCode,
        isLoading = startingLogin && deviceCode == null,
        loginError = if (route == AppRoute.Login) lastError else null,
        onStartLogin = { startingLogin = true; auth.startLogin(it) },
        machineName = pairedMachine,
        isLinking = isLinking,
        pairingError = pairingError,
        onClaimCode = { code ->
            scope.launch {
                isLinking = true; pairingError = null
                auth.linkMac(code, "AgenC Android").onFailure { pairingError = it.message }
                isLinking = false
            }
        },
        onScanQr = { /* TODO: CameraX + ML Kit barcode scanner */ },
        mainTab = mainTab,
        sessions = sessions,
        onOpenSession = { selected = it },
        onNewChat = {
            val s = SessionSummary(
                id = UUID.randomUUID().toString(), title = "New chat",
                lifecycle = SessionLifecycle.Idle, cwd = "", subtitle = "", updated = "now",
            )
            hub.registerSession(s)
            selected = s
        },
        onOpenAccount = { mainTab = MainTab.Account },
        identity = identity,
        onLogout = { auth.signOut(); selected = null; mainTab = MainTab.Sessions },
        mutedSessions = mutedSessions,
        onToggleMute = { sid ->
            AppGraph.prefs.toggleMuted(sid)
            mutedSessions = AppGraph.prefs.mutedSessions
        },
    )
}

/** Binds one session's SessionStore (attached live) to the stateless ChatScreen. */
@Composable
private fun ChatHost(session: SessionSummary, hub: DaemonHub, onBack: () -> Unit) {
    val store = remember(session.id) { hub.storeFor(session.id, session.title) }
    LaunchedEffect(session.id) { hub.attach(session.id, store) }

    // Foreground-session tracking: this session must NOT get background notifications while open,
    // and its pending banners/badge clear on entry. Mirrors iOS `currentlyOpenSessionId` + clearAttention.
    DisposableEffect(session.id) {
        hub.currentlyOpenSessionId = session.id
        AppGraph.notifications.clearAttention(session.id)
        onDispose {
            if (hub.currentlyOpenSessionId == session.id) hub.currentlyOpenSessionId = null
        }
    }

    val items by store.items.collectAsState()
    val lifecycle by store.lifecycle.collectAsState()
    val usage by store.usage.collectAsState()
    val pendingPermission by store.pendingPermission.collectAsState()
    val pendingElicitation by store.pendingElicitation.collectAsState()

    ChatScreen(
        title = session.title,
        cwd = session.cwd,
        items = items,
        lifecycle = lifecycle,
        usage = usage,
        pendingPermission = pendingPermission,
        pendingElicitation = pendingElicitation,
        isReadOnly = session.isTerminalOrigin,
        onSend = { hub.send(text = it, sessionId = session.id) },
        onStop = { hub.cancelTurn(session.id) },
        onApprove = { id, scope -> hub.approve(scope, session.id, id) },
        onDeny = { id -> hub.deny(session.id, id) },
        onRespond = { id, answer ->
            hub.respondElicitation(session.id, id, pendingElicitation?.questionId ?: "", answer)
        },
        onBack = onBack,
    )
}
