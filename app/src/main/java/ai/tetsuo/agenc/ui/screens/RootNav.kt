package ai.tetsuo.agenc.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.AccountUiState
import ai.tetsuo.agenc.model.CoreAccountLinkState
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/** Welcome locally, authenticate+pair from a CLI code, then enter the main app. */
enum class AppRoute { Welcome, Pairing, Main }

/** Within [AppRoute.Main], which destination the bottom navigation is showing. */
enum class MainTab { Home, Sessions, Account }

/** Small UI projection of the daemon link; keeps transport details out of the screen layer. */
enum class ConnectionStatus { Connected, Connecting, Offline }

/**
 * The top-level switch — the Android port of the iOS `RootView` swap logic.
 *
 * The Pairing route is fully stateless. The Main route renders the host-owned [mainTab] so the
 * selected destination survives temporary Chat and Ledger overlays.
 *
 * Auth/pairing state:
 * @param machineName linked computer name for [PairingScreen] (null until linked).
 * @param isLinking claim-in-flight flag for [PairingScreen].
 * @param pairingError error for [PairingScreen].
 * @param onClaimCode Connect tap on [PairingScreen]; carries the normalized code.
 * @param onScanQr Scan-QR tap on [PairingScreen].
 * @param pairingInitialCode a deep-link code to prefill for explicit user confirmation.
 *
 * Main state:
 * @param mainTab the selected main tab.
 * @param sessions the session list for Home + Sessions.
 * @param onOpenSession row tap.
 * @param onNewChat compose action.
 * @param onMainTabChange updates the single host-owned tab state.
 * @param account canonical hosted account plus its fail-closed Core link state.
 * @param onLogout logout tap on [AccountScreen].
 */
@Composable
fun RootNav(
    route: AppRoute,
    // Welcome (local navigation only; no auth request)
    onWelcomeContinue: () -> Unit,
    // Pairing
    machineName: String?,
    isLinking: Boolean,
    pairingError: String?,
    onClaimCode: (String) -> Unit,
    onScanQr: () -> Unit,
    pairingInitialCode: String? = null,
    // Main
    mainTab: MainTab,
    connectionStatus: ConnectionStatus = ConnectionStatus.Offline,
    sessions: List<SessionSummary>,
    sessionsQuery: String = "",
    onSessionsQueryChange: (String) -> Unit = {},
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    onMainTabChange: (MainTab) -> Unit,
    account: AccountUiState?,
    onRefreshAccount: () -> Unit = {},
    onLogout: () -> Unit,
    mutedSessions: Set<String> = emptySet(),
    onToggleMute: (String) -> Unit = {},
    onDeleteSession: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onOpenLedger: (() -> Unit)? = null,
) {
    when (route) {
        AppRoute.Welcome -> WelcomeScreen(onContinue = onWelcomeContinue)

        AppRoute.Pairing -> PairingScreen(
            machineName = machineName,
            isLinking = isLinking,
            error = pairingError,
            onClaimCode = onClaimCode,
            onScanQr = onScanQr,
            initialCode = pairingInitialCode,
        )

        AppRoute.Main -> MainShell(
            selectedTab = mainTab,
            onTabChange = onMainTabChange,
            connectionStatus = connectionStatus,
            sessions = sessions,
            sessionsQuery = sessionsQuery,
            onSessionsQueryChange = onSessionsQueryChange,
            onOpenSession = onOpenSession,
            onNewChat = onNewChat,
            account = account,
            machineName = machineName,
            onRefreshAccount = onRefreshAccount,
            onLogout = onLogout,
            mutedSessions = mutedSessions,
            onToggleMute = onToggleMute,
            onDeleteSession = onDeleteSession,
            onOpenLedger = onOpenLedger,
        )
    }
}

/**
 * The signed-in shell: the host owns the selected tab so opening an overlay cannot reset it.
 */
@Composable
private fun MainShell(
    selectedTab: MainTab,
    onTabChange: (MainTab) -> Unit,
    connectionStatus: ConnectionStatus,
    sessions: List<SessionSummary>,
    sessionsQuery: String,
    onSessionsQueryChange: (String) -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    account: AccountUiState?,
    machineName: String?,
    onRefreshAccount: () -> Unit,
    onLogout: () -> Unit,
    mutedSessions: Set<String>,
    onToggleMute: (String) -> Unit,
    onDeleteSession: suspend (String) -> Result<Unit>,
    onOpenLedger: (() -> Unit)? = null,
) {
    val tabStateHolder = rememberSaveableStateHolder()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MainPalette.canvas,
        bottomBar = {
            BottomNavigationBar(
                selected = selectedTab,
                onSelect = onTabChange,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MainPalette.canvas)
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            Crossfade(
                targetState = selectedTab,
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "main-tab-content",
            ) { tab ->
                tabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        MainTab.Home -> HomeScreen(
                            sessions = sessions,
                            connectionStatus = connectionStatus,
                            onNewChat = onNewChat,
                            onOpen = onOpenSession,
                        )

                        MainTab.Sessions -> SessionsScreen(
                            sessions = sessions,
                            query = sessionsQuery,
                            onQueryChange = onSessionsQueryChange,
                            onOpen = onOpenSession,
                            onNewChat = onNewChat,
                            onAccount = { onTabChange(MainTab.Account) },
                            mutedSessions = mutedSessions,
                            onToggleMute = onToggleMute,
                            onDeleteSession = onDeleteSession,
                        )

                        MainTab.Account -> AccountScreen(
                            account = account,
                            machineName = machineName,
                            connectionStatus = connectionStatus,
                            onRefreshAccount = onRefreshAccount,
                            onLogout = onLogout,
                            onOpenLedger = onOpenLedger,
                        )
                    }
                }
            }
        }
    }
}

/** Edge-to-edge typographic navigation: no icon container and no Material ripple overlay. */
@Composable
private fun BottomNavigationBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MainPalette.canvas)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = MainPalette.lineSoft)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(
                index = "01",
                label = "Home",
                isSelected = selected == MainTab.Home,
                onClick = { onSelect(MainTab.Home) },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                index = "02",
                label = "Sessions",
                isSelected = selected == MainTab.Sessions,
                onClick = { onSelect(MainTab.Sessions) },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                index = "03",
                label = "Account",
                isSelected = selected == MainTab.Account,
                onClick = { onSelect(MainTab.Account) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(
    index: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val showIndex = LocalDensity.current.fontScale < 1.5f
    val haptics = rememberAgenCHaptics()
    val tint by animateColorAsState(
        targetValue = if (isSelected) MainPalette.ink else MainPalette.quiet,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "bottom-nav-tint",
    )
    val indicator by animateColorAsState(
        targetValue = if (isSelected) MainPalette.ink else Color.Transparent,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "bottom-nav-indicator",
    )
    val pressedAlpha by animateFloatAsState(
        targetValue = if (isPressed) .72f else 1f,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "bottom-nav-press",
    )
    Column(
        modifier = modifier
            .heightIn(min = 60.dp)
            .selectable(
                selected = isSelected,
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                role = Role.Tab,
                onClick = {
                    if (!isSelected) {
                        haptics.perform(HapticCue.Selection)
                        onClick()
                    }
                },
            )
            .alpha(pressedAlpha)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .background(indicator),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showIndex) {
                Text(
                    text = index,
                    color = tint.copy(alpha = if (isSelected) .68f else .78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = .4.sp,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = label.uppercase(),
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = .65.sp,
            )
        }
    }
}

@Preview(name = "RootNav — Pairing", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun RootNavPairingPreview() {
    AgenCTheme {
        RootNav(
            route = AppRoute.Pairing,
            onWelcomeContinue = {},
            machineName = null,
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Sessions,
            sessions = emptyList(),
            onOpenSession = {},
            onNewChat = {},
            onMainTabChange = {},
            account = null,
            onLogout = {},
        )
    }
}

@Preview(name = "RootNav — Main/Sessions", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun RootNavMainPreview() {
    AgenCTheme {
        RootNav(
            route = AppRoute.Main,
            onWelcomeContinue = {},
            machineName = "Workstation",
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Sessions,
            sessions = emptyList(),
            onOpenSession = {},
            onNewChat = {},
            onMainTabChange = {},
            account = previewAccount(),
            onLogout = {},
        )
    }
}

@Preview(name = "RootNav — Main/Home", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun RootNavHomePreview() {
    AgenCTheme {
        RootNav(
            route = AppRoute.Main,
            onWelcomeContinue = {},
            machineName = "Workstation",
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Home,
            sessions = listOf(
                SessionSummary(
                    id = "sess-1",
                    title = "Refactor auth flow",
                    lifecycle = SessionLifecycle.Running,
                    cwd = "~/agenc-core",
                    subtitle = "wiring login",
                    updated = "now",
                ),
            ),
            onOpenSession = {},
            onNewChat = {},
            onMainTabChange = {},
            account = previewAccount(),
            onLogout = {},
        )
    }
}

private fun previewAccount(): AccountUiState {
    val identity = Identity(
        displayName = "AgenC User",
        email = "user@example.invalid",
        accountId = "acct-preview",
    )
    return AccountUiState(
        phone = identity,
        core = CoreAccountLinkState.Linked(identity),
    )
}
