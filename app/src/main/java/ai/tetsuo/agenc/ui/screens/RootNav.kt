package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand

/** The three top-level destinations: passwordless login → pair a computer → the main app. */
enum class AppRoute { Login, Pairing, Main }

/** Within [AppRoute.Main], which screen the floating tab bar is showing. */
enum class MainTab { Home, Sessions, Account }

/**
 * The top-level switch — the Android port of the iOS `RootView` swap logic.
 *
 * The Login and Pairing routes are fully stateless. The Main route hosts an internal-state floating
 * tab bar (Home / Sessions / Account) seeded from [mainTab]; once inside Main, the tab is owned here
 * so the bottom bar feels instant. The host's [onOpenAccount] still jumps to the Account tab when it
 * needs to (e.g. a header shortcut).
 *
 * Auth/pairing state:
 * @param deviceCode device-flow code for [LoginScreen] (null until issued).
 * @param isLoading login-in-flight flag for [LoginScreen].
 * @param loginError error for [LoginScreen].
 * @param onStartLogin Sign-in tap on [LoginScreen]; carries the optional identifier (null here).
 *
 * @param machineName linked computer name for [PairingScreen] (null until linked).
 * @param isLinking claim-in-flight flag for [PairingScreen].
 * @param pairingError error for [PairingScreen].
 * @param onClaimCode Connect tap on [PairingScreen]; carries the normalized code.
 * @param onScanQr Scan-QR tap on [PairingScreen].
 *
 * Main state:
 * @param mainTab the initial main tab (the floating bar takes over once mounted).
 * @param sessions the session list for Home + Sessions.
 * @param onOpenSession row tap.
 * @param onNewChat compose action.
 * @param onOpenAccount jump-to-account hook (kept for host compatibility).
 * @param identity the account identity for [AccountScreen].
 * @param onLogout logout tap on [AccountScreen].
 */
@Composable
fun RootNav(
    route: AppRoute,
    // Login
    deviceCode: DeviceCodeUi?,
    isLoading: Boolean,
    loginError: String?,
    onStartLogin: (identifier: String?) -> Unit,
    // Pairing
    machineName: String?,
    isLinking: Boolean,
    pairingError: String?,
    onClaimCode: (String) -> Unit,
    onScanQr: () -> Unit,
    // Main
    mainTab: MainTab,
    sessions: List<SessionSummary>,
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenAccount: () -> Unit,
    identity: Identity?,
    onLogout: () -> Unit,
    mutedSessions: Set<String> = emptySet(),
    onToggleMute: (String) -> Unit = {},
    onOpenLedger: (() -> Unit)? = null,
) {
    when (route) {
        AppRoute.Login -> LoginScreen(
            deviceCode = deviceCode,
            isLoading = isLoading,
            error = loginError,
            onStart = onStartLogin,
        )

        AppRoute.Pairing -> PairingScreen(
            machineName = machineName,
            isLinking = isLinking,
            error = pairingError,
            onClaimCode = onClaimCode,
            onScanQr = onScanQr,
        )

        AppRoute.Main -> MainShell(
            initialTab = mainTab,
            sessions = sessions,
            onOpenSession = onOpenSession,
            onNewChat = onNewChat,
            identity = identity,
            onLogout = onLogout,
            mutedSessions = mutedSessions,
            onToggleMute = onToggleMute,
            onOpenLedger = onOpenLedger,
        )
    }
}

/**
 * The signed-in shell: the selected tab is INTERNAL state, the floating bottom navigation bar
 * (Home / Sessions / Account) switches it, and the content fills the area beneath. Follows the
 * system light/dark appearance.
 */
@Composable
private fun MainShell(
    initialTab: MainTab,
    sessions: List<SessionSummary>,
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    identity: Identity?,
    onLogout: () -> Unit,
    mutedSessions: Set<String>,
    onToggleMute: (String) -> Unit,
    onOpenLedger: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Content for the selected tab.
        when (tab) {
            MainTab.Home -> HomeScreen(
                sessions = sessions,
                onNewChat = onNewChat,
                onOpen = onOpenSession,
            )

            MainTab.Sessions -> SessionsScreen(
                sessions = sessions,
                onOpen = onOpenSession,
                onNewChat = onNewChat,
                onAccount = { tab = MainTab.Account },
                mutedSessions = mutedSessions,
                onToggleMute = onToggleMute,
            )

            MainTab.Account -> AccountScreen(
                identity = identity,
                onLogout = onLogout,
                onOpenLedger = onOpenLedger,
            )
        }

        // Floating bottom navigation bar.
        FloatingTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 40.dp, vertical = 12.dp),
        )
    }
}

/** A floating, rounded three-tab bar: Home / Sessions / Account. Selected tab is tinted purple. */
@Composable
private fun FloatingTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(32.dp), clip = false)
            .background(colors.surface, RoundedCornerShape(32.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem(
            label = "Home",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            isSelected = selected == MainTab.Home,
            onClick = { onSelect(MainTab.Home) },
            modifier = Modifier.weight(1f),
        )
        TabItem(
            label = "Sessions",
            selectedIcon = Icons.AutoMirrored.Filled.Chat,
            unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
            isSelected = selected == MainTab.Sessions,
            onClick = { onSelect(MainTab.Sessions) },
            modifier = Modifier.weight(1f),
        )
        TabItem(
            label = "Account",
            selectedIcon = Icons.Filled.Person,
            unselectedIcon = Icons.Outlined.Person,
            isSelected = selected == MainTab.Account,
            onClick = { onSelect(MainTab.Account) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (isSelected) Brand.accent else colors.onSurface
    val chip = if (isSelected) Brand.accent.copy(alpha = 0.12f) else Color.Transparent
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(chip, RoundedCornerShape(24.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (isSelected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Preview(name = "RootNav — Login", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun RootNavLoginPreview() {
    AgenCTheme {
        RootNav(
            route = AppRoute.Login,
            deviceCode = null,
            isLoading = false,
            loginError = null,
            onStartLogin = {},
            machineName = null,
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Sessions,
            sessions = emptyList(),
            onOpenSession = {},
            onNewChat = {},
            onOpenAccount = {},
            identity = null,
            onLogout = {},
        )
    }
}

@Preview(name = "RootNav — Pairing", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun RootNavPairingPreview() {
    AgenCTheme {
        RootNav(
            route = AppRoute.Pairing,
            deviceCode = null,
            isLoading = false,
            loginError = null,
            onStartLogin = {},
            machineName = null,
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Sessions,
            sessions = emptyList(),
            onOpenSession = {},
            onNewChat = {},
            onOpenAccount = {},
            identity = null,
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
            deviceCode = null,
            isLoading = false,
            loginError = null,
            onStartLogin = {},
            machineName = "Pavel's MacBook Pro",
            isLinking = false,
            pairingError = null,
            onClaimCode = {},
            onScanQr = {},
            mainTab = MainTab.Sessions,
            sessions = emptyList(),
            onOpenSession = {},
            onNewChat = {},
            onOpenAccount = {},
            identity = Identity(displayName = "Pavel C.", handle = "@pavel", tier = Tier.pro),
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
            deviceCode = null,
            isLoading = false,
            loginError = null,
            onStartLogin = {},
            machineName = "Pavel's MacBook Pro",
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
                    cwd = "/Users/pavel/agenc-core",
                    subtitle = "wiring login",
                    updated = "now",
                ),
            ),
            onOpenSession = {},
            onNewChat = {},
            onOpenAccount = {},
            identity = Identity(displayName = "Pavel C.", handle = "@pavel", tier = Tier.pro),
            onLogout = {},
        )
    }
}
