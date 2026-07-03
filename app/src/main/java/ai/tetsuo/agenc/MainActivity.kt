package ai.tetsuo.agenc

import ai.tetsuo.agenc.data.AppGraph
import ai.tetsuo.agenc.data.AuthManager
import ai.tetsuo.agenc.data.DaemonHub
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ledger.LedgerController
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.notif.NotificationCoordinator
import ai.tetsuo.agenc.ui.screens.LedgerScreen
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
    var ledgerOpen by remember { mutableStateOf(false) }
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

    // Ledger BLE runtime permissions (scan/connect) — requested when entering the Ledger flow.
    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> AppGraph.ledger.onPermissionsResult(grants.values.all { it }) }

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
            if (ai.tetsuo.agenc.Demo.ENABLED) hub.ensureDemoSession() // pre-seeded chat for the video
            hub.ensureConnected()
            hub.refreshSessions()
        }
    }
    // Clear the transient "starting login" spinner once the backend responds.
    LaunchedEffect(authState, lastError) {
        if (authState !is AuthManager.State.SignedOut || lastError != null) startingLogin = false
    }

    // NOTE: sign-in is the instant device flow (backend IDENTITY_PROVIDER=mock auto-approves the
    // poll) — no browser hop. If a real OAuth provider is ever re-enabled on the backend, the
    // Polling state's verificationUri must be opened in the browser here for the poll to succeed.

    // Demo: which chat session is awaiting a "transfer sent" reply once the Ledger flow completes.
    var demoReplySession by remember { mutableStateOf<String?>(null) }
    val ledgerUi by AppGraph.ledger.ui.collectAsState()
    // When the scripted transfer confirms on the Flex + broadcasts, drop back to the chat and post
    // the agent's success reply (on the main thread — safe to touch Compose state + the store here).
    LaunchedEffect(ledgerUi, demoReplySession) {
        val sid = demoReplySession ?: return@LaunchedEffect
        if (ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.Done) {
            hub.postAgentReply(sid, ai.tetsuo.agenc.Demo.SUCCESS_REPLY)
            AppGraph.ledger.closeFlow()
            ledgerOpen = false
            demoReplySession = null
        }
    }

    // Ledger signing overlay — sits above everything (incl. an open chat).
    if (ledgerOpen) {
        LedgerHost(
            onRequestPermissions = { blePermLauncher.launch(AppGraph.ledger.requiredPermissions()) },
            // Keep the BLE link alive on back so pairing (step 2) → transfer (step 3) is instant.
            onClose = {
                AppGraph.ledger.hide(); ledgerOpen = false; demoReplySession = null
            },
        )
        return
    }

    // Opened session → Chat overlay.
    val open = selected
    if (route == AppRoute.Main && open != null) {
        ChatHost(
            session = open,
            hub = hub,
            onLedgerRequest = { request, elicitation ->
                // The agent asked for a transfer: prefill the Ledger flow; answering the
                // elicitation with the tx signature closes the loop back to the agent.
                AppGraph.ledger.open(request)
                AppGraph.ledger.onSigned = { sig ->
                    hub.respondElicitation(open.id, elicitation.id, elicitation.questionId, sig)
                }
                ledgerOpen = true
            },
            onDemoTransfer = { sessionId ->
                // Scripted demo: open the prefilled Ledger flow (auto-connects + auto-submits to the
                // Flex); the success reply is posted when the Done state is observed above.
                demoReplySession = sessionId
                AppGraph.ledger.open(
                    ai.tetsuo.agenc.ui.screens.AgentTransferRequest(
                        toBase58 = ai.tetsuo.agenc.Demo.AGENT_WALLET,
                        lamports = ai.tetsuo.agenc.Demo.AMOUNT_LAMPORTS,
                        note = ai.tetsuo.agenc.Demo.VOICE_TRANSCRIPT,
                    ),
                )
                ledgerOpen = true
            },
            onBack = { selected = null; hub.detach(open.id) },
        )
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
        onOpenLedger = {
            AppGraph.ledger.open(null)
            ledgerOpen = true
        },
    )
}

/** Binds one session's SessionStore (attached live) to the stateless ChatScreen. */
@Composable
private fun ChatHost(
    session: SessionSummary,
    hub: DaemonHub,
    onLedgerRequest: (ai.tetsuo.agenc.ui.screens.AgentTransferRequest, PendingElicitation) -> Unit = { _, _ -> },
    onDemoTransfer: (sessionId: String) -> Unit = {},
    onBack: () -> Unit,
) {
    val store = remember(session.id) { hub.storeFor(session.id, session.title) }
    LaunchedEffect(session.id) {
        // Re-seed the demo chat if its store was emptied (belt-and-suspenders with the resident store).
        if (ai.tetsuo.agenc.Demo.ENABLED && session.id == ai.tetsuo.agenc.Demo.SESSION_ID) {
            hub.ensureDemoSession()
        }
        hub.attach(session.id, store)
    }
    val chatScope = rememberCoroutineScope()

    // Demo voice recording: the mic starts a (simulated) recording; sending it posts a voice bubble
    // and, after a short "thinking" beat, opens the scripted Ledger transfer.
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        recordSeconds = 0
        while (true) { kotlinx.coroutines.delay(1000); recordSeconds++ }
    }

    // Foreground-session tracking: this session must NOT get background notifications while open,
    // and its pending banners/badge clear on entry. Mirrors iOS `currentlyOpenSessionId` + clearAttention.
    DisposableEffect(session.id) {
        hub.currentlyOpenSessionId = session.id
        AppGraph.notifications.clearAttention(session.id)
        onDispose {
            if (hub.currentlyOpenSessionId == session.id) hub.currentlyOpenSessionId = null
        }
    }

    // Hardware back inside a chat returns to the session list (not exit the app).
    ChatBackHandler(onBack = onBack)

    val items by store.items.collectAsState()
    val lifecycle by store.lifecycle.collectAsState()
    val usage by store.usage.collectAsState()
    val pendingPermission by store.pendingPermission.collectAsState()
    val pendingElicitation by store.pendingElicitation.collectAsState()
    val thinkingDurations by store.thinkingDurations.collectAsState()

    // Agent-requested transfer: an elicitation embedding {"type":"solana_transfer",...} routes to
    // the Ledger flow (the human still approves the exact amount/recipient ON the device).
    LaunchedEffect(pendingElicitation) {
        val e = pendingElicitation ?: return@LaunchedEffect
        LedgerController.parseTransferRequest(e.question)?.let { req -> onLedgerRequest(req, e) }
    }

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
        thinkingDurations = thinkingDurations,
        isRecording = isRecording,
        recordSeconds = recordSeconds,
        onMic = { if (ai.tetsuo.agenc.Demo.ENABLED) isRecording = true },
        onStopRecording = {
            val secs = recordSeconds.coerceAtLeast(1)
            isRecording = false
            // Post the voice bubble + a brief "thinking", then open the scripted Ledger transfer.
            hub.postVoice(session.id, secs, ai.tetsuo.agenc.Demo.VOICE_TRANSCRIPT)
            chatScope.launch {
                kotlinx.coroutines.delay(ai.tetsuo.agenc.Demo.THINK_DELAY_MS)
                onDemoTransfer(session.id)
            }
        },
    )
}

/** Binds the LedgerController's state to the stateless LedgerScreen. */
@Composable
private fun LedgerHost(onRequestPermissions: () -> Unit, onClose: () -> Unit) {
    val ledger = AppGraph.ledger
    val ui by ledger.ui.collectAsState()
    val request by ledger.agentRequest.collectAsState()
    ChatBackHandler(onBack = onClose)
    LedgerScreen(
        ui = ui,
        agentRequest = request,
        onRequestPermissions = onRequestPermissions,
        onStartScan = { ledger.startScan() },
        onConnect = { ledger.connect(it) },
        onSend = { to, amountSol -> ledger.send(to, amountSol) },
        onRetry = { ledger.retry() },
        onBack = onClose,
    )
}

/** Hardware/gesture back inside overlays. */
@Composable
private fun ChatBackHandler(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
