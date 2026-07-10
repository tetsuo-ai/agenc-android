package ai.tetsuo.agenc

import ai.tetsuo.agenc.data.AppGraph
import ai.tetsuo.agenc.data.AuthManager
import ai.tetsuo.agenc.data.DaemonHub
import ai.tetsuo.agenc.data.HistoryLoadState
import ai.tetsuo.agenc.data.PendingLedgerResponseRecord
import ai.tetsuo.agenc.data.hasCorrelatedLedgerReceipt
import ai.tetsuo.agenc.data.linkedAccountIdentity
import ai.tetsuo.agenc.model.LedgerSolanaTransferClientAction
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import ai.tetsuo.agenc.model.PendingLedgerInteraction
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PermissionDecisionState
import ai.tetsuo.agenc.notif.NotificationCoordinator
import ai.tetsuo.agenc.notif.BackgroundLinkService
import ai.tetsuo.agenc.ui.screens.LedgerScreen
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.chat.ChatScreen
import ai.tetsuo.agenc.ui.chat.ChatConnectionState
import ai.tetsuo.agenc.ui.screens.AppRoute
import ai.tetsuo.agenc.ui.screens.DeviceCodeUi
import ai.tetsuo.agenc.ui.screens.MainTab
import ai.tetsuo.agenc.ui.screens.RootNav
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private data class ActiveLedgerElicitation(
    val sessionId: String,
    val hostBinding: String,
    val elicitation: PendingElicitation,
    val request: ai.tetsuo.agenc.ui.screens.AgentTransferRequest,
    val sourceAddress: String,
    val submittedReceipt: LedgerTransferReceipt? = null,
)

internal enum class LedgerHostExitAction {
    Proceed,
    ArchiveReceipt,
    ClearUnsigned,
    BlockCritical,
    BlockInvalidReceipt,
}

internal fun planLedgerHostExit(
    record: PendingLedgerResponseRecord?,
    hasPendingBroadcast: Boolean,
    hardwareFlowCritical: Boolean,
): LedgerHostExitAction = when {
    hasPendingBroadcast || hardwareFlowCritical ||
        (record?.hardwareReviewStarted == true && record.receipt == null) ->
        LedgerHostExitAction.BlockCritical
    record == null -> LedgerHostExitAction.Proceed
    record.receipt == null -> LedgerHostExitAction.ClearUnsigned
    hasCorrelatedLedgerReceipt(record) -> LedgerHostExitAction.ArchiveReceipt
    else -> LedgerHostExitAction.BlockInvalidReceipt
}

class MainActivity : ComponentActivity() {

    /** The session a tapped notification asked to open, surfaced to Compose. Nulled once routed. */
    private val pendingOpenSessionId = MutableStateFlow<String?>(null)
    /** Pairing code delivered by agenc://pair?c=...; retained until sign-in reaches Pairing. */
    private val pendingPairCode = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)        // wire AuthManager + DaemonHub for the process lifetime
        consumeOpenSessionExtra(intent)          // a cold launch from a notification tap
        consumePairingDeepLink(intent)
        enableEdgeToEdge()
        setContent { AgenCTheme { AgenCRoot(pendingOpenSessionId, pendingPairCode) } }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.hub.setAppInForeground(true)
        BackgroundLinkService.stop(this)
    }

    override fun onStop() {
        // Update before super so a completion racing with Home/Recents is treated as backgrounded.
        // A configuration recreation keeps the replacement Activity visible, so it must not create
        // a tiny false-background window that could emit an alert while the user is still in chat.
        if (!isChangingConfigurations) {
            AppGraph.hub.setAppInForeground(false)
            // onStop is a visible→background transition, an explicit Android FGS-start exemption.
            BackgroundLinkService.startIfEligible(this)
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenSessionExtra(intent)          // a warm launch (SINGLE_TOP) from a notification tap
        consumePairingDeepLink(intent)
    }

    /** Pull the notification's target session id off the launch intent and hand it to Compose. */
    private fun consumeOpenSessionExtra(intent: Intent?) {
        val sid = intent?.getStringExtra(NotificationCoordinator.EXTRA_OPEN_SESSION_ID)
            ?: intent?.data?.takeIf {
                it.scheme == NotificationCoordinator.OPEN_SESSION_SCHEME &&
                    it.host == NotificationCoordinator.OPEN_SESSION_HOST
            }?.pathSegments?.firstOrNull()
            ?: return
        intent?.removeExtra(NotificationCoordinator.EXTRA_OPEN_SESSION_ID)
        intent?.data = null
        pendingOpenSessionId.value = sid
    }

    private fun consumePairingDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "agenc" || uri.host != "pair") return
        val raw = uri.getQueryParameter("c") ?: uri.getQueryParameter("code") ?: return
        val code = ai.tetsuo.agenc.ui.screens.normalizePairCode(raw)
        if (code.length == 8) pendingPairCode.value = code
        intent.data = null
    }
}

/**
 * The single source of truth for which screen shows: the auth state + paired-machine drive the
 * route (Login → Pairing → Main), and an opened session overlays the Chat screen. All screens are
 * stateless composables; this is the only place the data layer (AppGraph) is read.
 */
@Composable
fun AgenCRoot(
    pendingOpen: MutableStateFlow<String?> = MutableStateFlow(null),
    pendingPair: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val auth = AppGraph.auth
    val hub = AppGraph.hub
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val authState by auth.state.collectAsState()
    val lastError by auth.lastError.collectAsState()
    val pairedMachine by auth.pairedMachine.collectAsState()
    val relayTicketVersion by auth.relayTicketVersion.collectAsState()
    val sessions by hub.sessions.collectAsState()
    val connectionState by hub.state.collectAsState()
    val coreIdentity by hub.accountIdentity.collectAsState()
    val pendingLedgerInteraction by hub.pendingLedgerInteraction.collectAsState()
    val pendingOpenId by pendingOpen.collectAsState()
    val pendingPairCode by pendingPair.collectAsState()

    var selected by remember { mutableStateOf<SessionSummary?>(null) }
    var ledgerOpen by remember { mutableStateOf(false) }
    var activeLedgerElicitation by remember {
        mutableStateOf(AppGraph.prefs.pendingLedgerResponse()?.toActiveLedgerElicitation())
    }
    var ledgerReceiptOutboxVersion by remember { mutableStateOf(0) }
    var mainTab by remember { mutableStateOf(MainTab.Home) }
    var sessionsQuery by rememberSaveable { mutableStateOf("") }
    var startingLogin by remember { mutableStateOf(false) }
    var isLinking by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairingPrefill by remember { mutableStateOf<String?>(null) }
    // Prefs isn't reactive, so mirror the muted set in Compose state (seeded from prefs) and write both.
    var mutedSessions by remember { mutableStateOf(AppGraph.prefs.mutedSessions) }

    val signedIn = authState is AuthManager.State.SignedIn
    val route = when {
        signedIn && pairedMachine != null -> AppRoute.Main
        signedIn -> AppRoute.Pairing
        else -> AppRoute.Login
    }

    // AgenC now uses one always-dark visual system from onboarding through the signed-in shell.
    val view = LocalView.current
    val darkSystemSurface = true
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !darkSystemSurface
            isAppearanceLightNavigationBars = !darkSystemSurface
        }
    }

    // Ledger BLE runtime permissions (scan/connect) — requested when entering the Ledger flow.
    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> AppGraph.ledger.onPermissionsResult(grants.values.all { it }) }

    // Ask for POST_NOTIFICATIONS once on Android 13+ so local notifications can actually show.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted-or-not; nothing to do — the poster no-ops when denied */ }
    LaunchedEffect(route) {
        if (route == AppRoute.Main &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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

    LaunchedEffect(pendingPairCode, route) {
        val code = pendingPairCode ?: return@LaunchedEffect
        if (route != AppRoute.Pairing) return@LaunchedEffect
        pairingPrefill = code
        pendingPair.value = null
    }

    // Re-point only while authenticated. Logout must close the hub, never fall through to the
    // unauthenticated loopback daemon simply because the relay ticket disappeared.
    LaunchedEffect(relayTicketVersion, signedIn) {
        if (signedIn) AppGraph.reconfigureForCurrentTicket() else hub.disconnect()
    }
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

    // A pairing can change outside the logout button (re-link, restore, or process restart). Move
    // any completed old-host receipt out of the active slot; never rebind it to the new Mac.
    LaunchedEffect(pairedMachine, relayTicketVersion, activeLedgerElicitation?.hostBinding) {
        val record = AppGraph.prefs.pendingLedgerResponse() ?: return@LaunchedEffect
        if (record.hostBinding == auth.ledgerHostBinding()) return@LaunchedEffect
        val criticalUi = ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.ConfirmOnDevice ||
            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.Broadcasting ||
            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.BroadcastUncertain
        when (
            planLedgerHostExit(
                record = record,
                hasPendingBroadcast = AppGraph.prefs.pendingLedgerBroadcast() != null,
                hardwareFlowCritical = criticalUi,
            )
        ) {
            LedgerHostExitAction.ArchiveReceipt -> {
                if (AppGraph.prefs.archivePendingLedgerReceipt(record)) {
                    activeLedgerElicitation = null
                    ledgerReceiptOutboxVersion += 1
                } else {
                    AppGraph.ledger.showError(
                        "This receipt belongs to the previous Mac and could not be archived. " +
                            "Keep this phone paired and retry before starting another transfer.",
                    )
                    ledgerOpen = true
                }
            }
            LedgerHostExitAction.ClearUnsigned -> {
                if (AppGraph.prefs.clearPendingLedgerResponse()) {
                    activeLedgerElicitation = null
                } else {
                    AppGraph.ledger.showError(
                        "The previous Mac's unsigned Ledger request could not be retired safely.",
                    )
                    ledgerOpen = true
                }
            }
            LedgerHostExitAction.BlockCritical,
            LedgerHostExitAction.BlockInvalidReceipt -> {
                AppGraph.ledger.showError(
                    "A Ledger signature or receipt from the previous Mac still needs recovery. " +
                        "Reconnect that Mac before using @ledger again.",
                )
                ledgerOpen = true
            }
            LedgerHostExitAction.Proceed -> Unit
        }
    }
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

    // Core capability-routes this typed request even when the originating chat is not open. Persist
    // the correlation BEFORE showing any signing UI; if no default account exists, sourceAddress is
    // intentionally blank until the human explicitly taps one of the derived account rows.
    LaunchedEffect(
        pendingLedgerInteraction?.elicitation?.id,
        activeLedgerElicitation?.elicitation?.id,
        route,
    ) {
        val pending = pendingLedgerInteraction ?: return@LaunchedEffect
        if (route != AppRoute.Main) return@LaunchedEffect
        val action = pending.elicitation.clientAction ?: return@LaunchedEffect
        val existing = activeLedgerElicitation
        if (existing != null) {
            if (existing.elicitation.id == pending.elicitation.id &&
                existing.elicitation.clientAction?.intentId == action.intentId
            ) {
                hub.consumeLedgerInteraction(pending.elicitation.id, action.intentId)
            }
            // A second session stays in the bounded hub FIFO until this hardware review settles.
            return@LaunchedEffect
        }

        val request = ai.tetsuo.agenc.ui.screens.AgentTransferRequest(
            toBase58 = action.to,
            lamports = action.lamports,
            note = action.note,
            intentId = action.intentId,
            responseNonce = action.responseNonce,
            expiresAtEpochMillis = action.expiresAtEpochMillis,
        )
        val source = AppGraph.ledger.selectedWalletContext()?.address.orEmpty()
        val cannotReplaceActiveHardwareFlow = ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.ConfirmOnDevice ||
            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.Broadcasting ||
            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.BroadcastUncertain
        val cancellation = when {
            action.expiresAtEpochMillis <= System.currentTimeMillis() ->
                ledgerCancelledReceipt(action, source, "request_expired")
            cannotReplaceActiveHardwareFlow ->
                ledgerCancelledReceipt(action, source, "ledger_phone_busy")
            AppGraph.prefs.pendingLedgerBroadcast() != null ->
                ledgerCancelledReceipt(action, source, "previous_broadcast_unresolved")
            else -> null
        }
        val candidate = ActiveLedgerElicitation(
            sessionId = pending.sessionId,
            hostBinding = pending.hostBinding,
            elicitation = pending.elicitation,
            request = request,
            sourceAddress = source,
            submittedReceipt = cancellation,
        )
        if (!AppGraph.prefs.setPendingLedgerResponse(candidate.toPendingLedgerResponseRecord())) {
            AppGraph.ledger.showError("Couldn't safely save the Ledger request. Nothing was signed.")
            ledgerOpen = true
            return@LaunchedEffect
        }
        activeLedgerElicitation = candidate
        hub.consumeLedgerInteraction(pending.elicitation.id, action.intentId)
        if (ledgerOpen && cancellation == null) {
            AppGraph.ledger.open(request)
        }
    }

    // A broadcast is irreversible. Deliver its exact receipt to Core, but never re-sign and never
    // convert it into a cancellation if the relay acknowledgement is lost. A reconnect changes
    // [connectionState] and retries this same receipt once more.
    LaunchedEffect(activeLedgerElicitation?.submittedReceipt, connectionState) {
        val active = activeLedgerElicitation ?: return@LaunchedEffect
        val receipt = active.submittedReceipt ?: return@LaunchedEffect
        if (active.hostBinding != auth.ledgerHostBinding()) return@LaunchedEffect
        if (connectionState != DaemonHub.ConnectionState.Connected) {
            return@LaunchedEffect
        }
        when (
            hub.respondLedgerElicitationOutcome(
                active.sessionId,
                active.elicitation.id,
                active.elicitation.questionId,
                receipt,
            )
        ) {
            DaemonHub.ElicitationResponseOutcome.Resolved -> {
                if (AppGraph.prefs.clearPendingLedgerResponse()) {
                    activeLedgerElicitation = null
                }
            }
            // Core may have timed out, cancelled, or restarted. Keep the exact durable receipt
            // in an independent outbox so it cannot occupy the next hardware-review slot.
            DaemonHub.ElicitationResponseOutcome.AlreadySettled -> {
                if (AppGraph.prefs.archivePendingLedgerReceipt(
                        active.toPendingLedgerResponseRecord(),
                    )
                ) {
                    activeLedgerElicitation = null
                    ledgerReceiptOutboxVersion += 1
                }
            }
            DaemonHub.ElicitationResponseOutcome.InFlight,
            DaemonHub.ElicitationResponseOutcome.TransportFailed -> Unit
        }
    }

    // Reconcile archived receipts independently from the one active hardware slot. An
    // inconclusive `not pending` answer remains archived, but never blocks a later @ledger request.
    LaunchedEffect(connectionState, ledgerReceiptOutboxVersion) {
        if (connectionState != DaemonHub.ConnectionState.Connected) return@LaunchedEffect
        val hostBinding = auth.ledgerHostBinding()
        var removedAny = false
        for (record in AppGraph.prefs.ledgerReceiptOutboxState().records) {
            if (record.hostBinding != hostBinding) continue
            val receipt = record.receipt?.let(LedgerTransferReceipt::parse) ?: continue
            when (
                hub.respondLedgerElicitationOutcome(
                    record.sessionId,
                    record.requestId,
                    record.questionId,
                    receipt,
                )
            ) {
                DaemonHub.ElicitationResponseOutcome.Resolved -> {
                    if (AppGraph.prefs.removeLedgerReceiptOutboxRecord(record)) {
                        removedAny = true
                    }
                }
                DaemonHub.ElicitationResponseOutcome.AlreadySettled,
                DaemonHub.ElicitationResponseOutcome.InFlight,
                DaemonHub.ElicitationResponseOutcome.TransportFailed -> Unit
            }
        }
        if (removedAny) ledgerReceiptOutboxVersion += 1
    }

    // The broadcast can finish after a process restore, when no callback closure survives. The
    // persisted correlation record is the authority: turn the immutable tx signature into the
    // exact receipt and let the acknowledgement effect above deliver it.
    LaunchedEffect(ledgerUi) {
        val record = AppGraph.prefs.pendingLedgerResponse() ?: return@LaunchedEffect
        if (record.receipt != null) {
            activeLedgerElicitation = record.toActiveLedgerElicitation()
            return@LaunchedEffect
        }
        val done = ledgerUi as? ai.tetsuo.agenc.ui.screens.LedgerUi.Done
            ?: return@LaunchedEffect
        // LedgerController commits the receipt synchronously before it clears the exact signed bytes
        // or publishes Done. Compose only mirrors that durable authority into observable state.
        AppGraph.ledger.showError(
            "Transfer ${done.signature.take(8)}… submitted, but its receipt is not durable yet.",
        )
    }

    // Recover a phone review that was interrupted before approval/broadcast. This reconnects the
    // remembered Ledger directly; it never signs without the human returning to the device UI.
    LaunchedEffect(route, activeLedgerElicitation?.elicitation?.id) {
        val active = activeLedgerElicitation ?: return@LaunchedEffect
        if (route != AppRoute.Main || active.submittedReceipt != null || ledgerOpen) {
            return@LaunchedEffect
        }
        if (active.hostBinding != auth.ledgerHostBinding()) {
            AppGraph.ledger.showError(
                "This Ledger request belongs to a different paired Mac and cannot be opened here.",
            )
            ledgerOpen = true
            return@LaunchedEffect
        }
        if (active.request.expiresAtEpochMillis?.let { it <= System.currentTimeMillis() } == true &&
            AppGraph.prefs.pendingLedgerBroadcast() == null
        ) {
            val expired = active.copy(submittedReceipt = active.cancelledReceipt("request_expired"))
            if (AppGraph.prefs.setPendingLedgerResponse(expired.toPendingLedgerResponseRecord())) {
                activeLedgerElicitation = expired
            }
            return@LaunchedEffect
        }
        AppGraph.ledger.open(active.request)
        ledgerOpen = true
    }

    // Ledger signing overlay — sits above everything (incl. an open chat).
    if (ledgerOpen) {
        LedgerHost(
            onRequestPermissions = { blePermLauncher.launch(AppGraph.ledger.requiredPermissions()) },
            onDefaultAccountSelected = {
                val active = activeLedgerElicitation
                val wallet = AppGraph.ledger.selectedWalletContext()
                if (active != null && active.submittedReceipt == null && wallet != null) {
                    val updated = active.copy(sourceAddress = wallet.address)
                    if (AppGraph.prefs.setPendingLedgerResponse(updated.toPendingLedgerResponseRecord())) {
                        activeLedgerElicitation = updated
                    } else {
                        AppGraph.ledger.showError(
                            "Couldn't durably save this default account. Nothing can be signed.",
                        )
                    }
                }
            },
            onForgotLedger = {
                activeLedgerElicitation?.takeIf { it.submittedReceipt == null }?.let { active ->
                    val cancelled = active.copy(
                        submittedReceipt = active.cancelledReceipt("ledger_forgotten"),
                    )
                    if (AppGraph.prefs.setPendingLedgerResponse(
                            cancelled.toPendingLedgerResponseRecord(),
                        )
                    ) {
                        activeLedgerElicitation = cancelled
                    }
                }
            },
            onClose = close@{
                if (ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.ConfirmOnDevice ||
                    ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.Broadcasting ||
                    ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.BroadcastUncertain
                ) {
                    return@close
                }
                activeLedgerElicitation?.takeIf { it.submittedReceipt == null }?.let { active ->
                    val cancelled = active.copy(
                        submittedReceipt = active.cancelledReceipt("cancelled_on_phone"),
                    )
                    if (AppGraph.prefs.setPendingLedgerResponse(
                            cancelled.toPendingLedgerResponseRecord(),
                        )
                    ) {
                        activeLedgerElicitation = cancelled
                    } else {
                        AppGraph.ledger.showError("Couldn't save the cancellation. Try again.")
                        return@close
                    }
                }
                AppGraph.ledger.hide(); ledgerOpen = false; demoReplySession = null
            },
        )
        return
    }

    // Keep the selected session as navigation state. The main shell remains composed underneath
    // the chat so returning preserves its selected tab, search query, and scroll/saveable state.
    val open = selected

    val deviceCode = (authState as? AuthManager.State.Polling)
        ?.let { DeviceCodeUi(it.userCode, it.verificationUri) }
    val cachedIdentity = (authState as? AuthManager.State.SignedIn)?.identity
    val identity = linkedAccountIdentity(cachedIdentity, coreIdentity)?.takeUnless {
        !it.runtimeLinked &&
            it.displayName.equals("User", ignoreCase = true) &&
            it.handle.equals("user@example.com", ignoreCase = true)
    }

    // `/login` and `/logout` can happen in the desktop TUI after the relay socket is already open.
    // Refresh while Account is visible so the phone links/clears that identity without reconnecting.
    LaunchedEffect(route, mainTab, connectionState) {
        if (route == AppRoute.Main &&
            mainTab == MainTab.Account &&
            connectionState == DaemonHub.ConnectionState.Connected
        ) {
            while (true) {
                hub.refreshDaemonInfo()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }
    val reduceMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Retain the main shell underneath the chat so a back gesture returns to the exact same
        // tab/list state. Hide it from accessibility while the chat is the active destination.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (route == AppRoute.Main && open != null) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            RootNav(
                route = route,
                deviceCode = deviceCode,
                isLoading = startingLogin && deviceCode == null,
                loginError = if (route == AppRoute.Login) lastError else null,
                onStartLogin = { startingLogin = true; auth.startLogin(it) },
                onCancelLogin = { startingLogin = false; auth.cancelLogin() },
                machineName = pairedMachine,
                isLinking = isLinking,
                pairingError = pairingError,
                onClaimCode = { code ->
                    pairingPrefill = null
                    scope.launch {
                        isLinking = true; pairingError = null
                        auth.linkMac(code, "AgenC Android").onFailure { pairingError = it.message }
                        isLinking = false
                    }
                },
                // PairingScreen owns the CameraX/ML Kit overlay; this callback is reserved for
                // host-level analytics or routing and intentionally has no side effect here.
                onScanQr = {},
                pairingInitialCode = pairingPrefill,
                mainTab = mainTab,
                connectionStatus = when (connectionState) {
                    DaemonHub.ConnectionState.Connected -> ai.tetsuo.agenc.ui.screens.ConnectionStatus.Connected
                    DaemonHub.ConnectionState.Connecting -> ai.tetsuo.agenc.ui.screens.ConnectionStatus.Connecting
                    DaemonHub.ConnectionState.Disconnected,
                    is DaemonHub.ConnectionState.Failed -> ai.tetsuo.agenc.ui.screens.ConnectionStatus.Offline
                },
                sessions = sessions,
                sessionsQuery = sessionsQuery,
                onSessionsQueryChange = { sessionsQuery = it },
                onOpenSession = { selected = it },
                onNewChat = {
                    val s = SessionSummary(
                        id = UUID.randomUUID().toString(), title = "New chat",
                        lifecycle = SessionLifecycle.Idle, cwd = "", subtitle = "", updated = "now",
                    )
                    hub.registerSession(s)
                    selected = s
                },
                onMainTabChange = { mainTab = it },
                identity = identity,
                onLogout = logout@{
                    val record = AppGraph.prefs.pendingLedgerResponse()
                    val criticalUi =
                        ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.ConfirmOnDevice ||
                            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.Broadcasting ||
                            ledgerUi is ai.tetsuo.agenc.ui.screens.LedgerUi.BroadcastUncertain
                    when (
                        planLedgerHostExit(
                            record = record,
                            hasPendingBroadcast = AppGraph.prefs.pendingLedgerBroadcast() != null,
                            hardwareFlowCritical = criticalUi,
                        )
                    ) {
                        LedgerHostExitAction.ArchiveReceipt -> {
                            if (record == null ||
                                !AppGraph.prefs.archivePendingLedgerReceipt(record)
                            ) {
                                AppGraph.ledger.showError(
                                    "Can't log out: the submitted Ledger receipt could not be " +
                                        "archived safely. Stay paired and retry logout.",
                                )
                                ledgerOpen = true
                                return@logout
                            }
                            activeLedgerElicitation = null
                            ledgerReceiptOutboxVersion += 1
                        }
                        LedgerHostExitAction.ClearUnsigned -> {
                            if (!AppGraph.prefs.clearPendingLedgerResponse()) {
                                AppGraph.ledger.showError(
                                    "Can't log out: the pending Ledger request could not be retired.",
                                )
                                ledgerOpen = true
                                return@logout
                            }
                            activeLedgerElicitation = null
                        }
                        LedgerHostExitAction.BlockCritical -> {
                            AppGraph.ledger.showError(
                                "Can't log out while a Ledger signature or broadcast is unresolved. " +
                                    "Finish Ledger recovery first, then retry logout.",
                            )
                            ledgerOpen = true
                            return@logout
                        }
                        LedgerHostExitAction.BlockInvalidReceipt -> {
                            AppGraph.ledger.showError(
                                "Can't log out because the saved Ledger receipt failed validation. " +
                                    "Reconnect the paired Mac and retry recovery.",
                            )
                            ledgerOpen = true
                            return@logout
                        }
                        LedgerHostExitAction.Proceed -> Unit
                    }
                    AppGraph.ledger.closeFlow()
                    auth.signOut()
                    selected = null
                    mainTab = MainTab.Home
                    sessionsQuery = ""
                    mutedSessions = emptySet()
                    scope.launch { hub.resetForSignOut() }
                },
                mutedSessions = mutedSessions,
                onToggleMute = { sid ->
                    AppGraph.prefs.toggleMuted(sid)
                    mutedSessions = AppGraph.prefs.mutedSessions
                },
                onDeleteSession = { sid ->
                    hub.terminateSession(sid).also { result ->
                        if (result.isSuccess && sid in mutedSessions) {
                            mutedSessions = AppGraph.prefs.mutedSessions
                        }
                    }
                },
                onOpenLedger = {
                    AppGraph.ledger.open(null)
                    ledgerOpen = true
                },
            )
        }

        AnimatedContent(
            targetState = open.takeIf { route == AppRoute.Main },
            contentKey = { it?.id ?: "main-shell" },
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                when {
                    reduceMotion -> EnterTransition.None togetherWith ExitTransition.None
                    targetState != null -> {
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = FastOutSlowInEasing,
                            ),
                            initialAlpha = 0.62f,
                        ) + scaleIn(
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = FastOutSlowInEasing,
                            ),
                            initialScale = 0.992f,
                        )) togetherWith ExitTransition.None
                    }
                    else -> EnterTransition.None togetherWith (
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 130,
                                easing = FastOutLinearInEasing,
                            ),
                        ) + scaleOut(
                            animationSpec = tween(
                                durationMillis = 130,
                                easing = FastOutLinearInEasing,
                            ),
                            targetScale = 0.996f,
                        )
                    )
                }
            },
            label = "session-overlay",
        ) { chatSession ->
            if (chatSession == null) {
                Box(Modifier.fillMaxSize())
            } else {
                ChatHost(
                    session = chatSession,
                    hub = hub,
                    connectionState = connectionState,
                    onDemoTransfer = { sessionId ->
                        // Scripted demo: open the prefilled Ledger flow (auto-connects +
                        // auto-submits to the Flex); post success when Done is observed above.
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
                    onBack = {
                        if (selected?.id == chatSession.id) selected = null
                        hub.detach(chatSession.id)
                    },
                )
            }
        }
    }
}

/** Binds one session's SessionStore (attached live) to the stateless ChatScreen. */
@Composable
private fun ChatHost(
    session: SessionSummary,
    hub: DaemonHub,
    connectionState: DaemonHub.ConnectionState,
    onDemoTransfer: (sessionId: String) -> Unit = {},
    onBack: () -> Unit,
) {
    val store = remember(session.id) { hub.storeFor(session.id, session.title) }
    val items by store.items.collectAsState()
    val historyState by store.historyState.collectAsState()
    val historyReady = items.isNotEmpty() ||
        historyState == HistoryLoadState.Cached ||
        historyState == HistoryLoadState.Ready
    val historyLoadFailed = items.isEmpty() && historyState == HistoryLoadState.Failed
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

    val lifecycle by store.lifecycle.collectAsState()
    val usage by store.usage.collectAsState()
    val pendingPermission by store.pendingPermission.collectAsState()
    val permissionDecisions by hub.permissionDecisions.collectAsState()
    val pendingElicitation by store.pendingElicitation.collectAsState()
    val genericElicitation = pendingElicitation?.takeIf { it.clientAction == null }
    val thinkingDurations by store.thinkingDurations.collectAsState()

    ChatScreen(
        sessionId = session.id,
        title = session.title,
        cwd = session.cwd,
        items = items,
        historyReady = historyReady,
        historyLoadFailed = historyLoadFailed,
        lifecycle = lifecycle,
        usage = usage,
        pendingPermission = pendingPermission,
        permissionDecisionState = pendingPermission
            ?.let { permissionDecisions[it.id] }
            ?: PermissionDecisionState.Idle,
        pendingElicitation = genericElicitation,
        isReadOnly = session.isTerminalOrigin,
        connectionState = when (connectionState) {
            DaemonHub.ConnectionState.Connected -> ChatConnectionState.Connected
            DaemonHub.ConnectionState.Connecting -> ChatConnectionState.Connecting
            DaemonHub.ConnectionState.Disconnected,
            is DaemonHub.ConnectionState.Failed -> ChatConnectionState.Offline
        },
        // Core binds an exact @ledger token to this root human turn and emits the privileged typed
        // action. Android sends the user's text unchanged, which also makes edited turns correct.
        onSend = { message -> hub.send(text = message, sessionId = session.id) },
        onStop = { hub.cancelTurn(session.id) },
        onApprove = { id, scope -> hub.approve(scope, session.id, id) },
        onDeny = { id -> hub.deny(session.id, id) },
        onRespond = { id, answer ->
            hub.respondElicitation(session.id, id, genericElicitation?.questionId ?: "", answer)
        },
        onBack = onBack,
        reduceMotion = !android.animation.ValueAnimator.areAnimatorsEnabled(),
        thinkingDurations = thinkingDurations,
        isRecording = isRecording,
        recordSeconds = recordSeconds,
        voiceEnabled = ai.tetsuo.agenc.Demo.ENABLED,
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
        onEditSend = { messageOrdinal, messageId, revisedText ->
            hub.reviseMessage(
                sessionId = session.id,
                messageOrdinal = messageOrdinal,
                messageId = messageId,
                revisedText = revisedText,
            )
        },
    )
}

/** Binds the LedgerController's state to the stateless LedgerScreen. */
@Composable
private fun LedgerHost(
    onRequestPermissions: () -> Unit,
    onDefaultAccountSelected: () -> Unit,
    onForgotLedger: () -> Unit,
    onClose: () -> Unit,
) {
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
        onSelectAccount = {
            ledger.selectDefaultAccount(it)
            onDefaultAccountSelected()
        },
        onLoadMore = { ledger.loadMore() },
        onAddAccountIndex = { ledger.addAccountIndex(it) },
        onForgetLedger = {
            ledger.forgetLedger()
            onForgotLedger()
        },
        onSend = { to, amountSol -> ledger.send(to, amountSol) },
        onRetry = { ledger.retry() },
        onBack = onClose,
    )
}

private fun ledgerCancelledReceipt(
    action: LedgerSolanaTransferClientAction,
    sourceAddress: String,
    reason: String,
): LedgerTransferReceipt = LedgerTransferReceipt.cancelled(
    intentId = action.intentId,
    responseNonce = action.responseNonce,
    to = action.to,
    lamports = action.lamports,
    from = sourceAddress,
    reason = reason,
)

private fun ActiveLedgerElicitation.cancelledReceipt(reason: String): LedgerTransferReceipt =
    ledgerCancelledReceipt(
        action = requireNotNull(elicitation.clientAction),
        sourceAddress = sourceAddress,
        reason = reason,
    )

private fun ActiveLedgerElicitation.toPendingLedgerResponseRecord(): PendingLedgerResponseRecord =
    PendingLedgerResponseRecord(
        sessionId = sessionId,
        requestId = elicitation.id,
        questionId = elicitation.questionId,
        hostBinding = hostBinding,
        intentId = requireNotNull(elicitation.clientAction).intentId,
        responseNonce = requireNotNull(elicitation.clientAction).responseNonce,
        expiresAtEpochMillis = requireNotNull(elicitation.clientAction).expiresAtEpochMillis,
        sourceAddress = sourceAddress,
        recipientAddress = request.toBase58,
        lamports = request.lamports,
        receipt = submittedReceipt?.toJson(),
    )

private fun PendingLedgerResponseRecord.toActiveLedgerElicitation(): ActiveLedgerElicitation =
    LedgerSolanaTransferClientAction(
        intentId = intentId,
        responseNonce = responseNonce,
        to = recipientAddress,
        lamports = lamports,
        note = null,
        expiresAtEpochMillis = expiresAtEpochMillis,
    ).let { action -> ActiveLedgerElicitation(
        sessionId = sessionId,
        hostBinding = hostBinding,
        elicitation = PendingElicitation(
            id = requestId,
            questionId = questionId,
            question = "Ledger transfer",
            options = emptyList(),
            allowsOther = false,
            clientAction = action,
        ),
        request = ai.tetsuo.agenc.ui.screens.AgentTransferRequest(
            toBase58 = recipientAddress,
            lamports = lamports,
            note = null,
            intentId = intentId,
            responseNonce = responseNonce,
            expiresAtEpochMillis = expiresAtEpochMillis,
        ),
        sourceAddress = sourceAddress,
        submittedReceipt = receipt?.let(LedgerTransferReceipt::parse),
    ) }

/** Hardware/gesture back inside overlays. */
@Composable
private fun ChatBackHandler(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
