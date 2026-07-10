package ai.tetsuo.agenc.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.Onboarding
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import java.math.BigDecimal

/**
 * Ledger screen — the "Fund / transfer with Ledger Flex" flow, matching the iOS Ledger surface.
 *
 * Fully STATELESS: a controller (a StateFlow-driven singleton in data/, same pattern as the other
 * stores) owns the BLE machine and pushes one [LedgerUi] state at a time; this screen only renders
 * that state and fires callbacks. No BLE, no crypto, no signing here — the private key never leaves
 * the Ledger and this screen never even sees a transaction, only display strings.
 *
 * Visual contract (chat-style pure-dark canvas, like `ChatScreen`):
 *  - black full-screen canvas, header with back chevron + "Ledger" title
 *  - high-contrast monochrome pills, monospace for anything base58
 *  - Scanning = a soft monochrome pulse over the device list; Connected = device chip + account card +
 *    transfer form; ConfirmOnDevice = a drawn Ledger-Flex outline (screen-first, like the device)
 *  - when the agent asked for the transfer ([AgentTransferRequest]) the recipient is prefilled and
 *    LOCKED read-only with an "agent requested" badge — task text can never retarget or resize the
 *    request inside this screen, and the human verifies it again on the Ledger itself.
 *
 * The only local state is the two form fields (recipient / amount), keyed on [agentRequest] so an
 * incoming agent request re-prefills them.
 */

/** A public Solana account derived by the Ledger. No private material ever reaches the app. */
data class LedgerAccountUi(
    val key: String,
    val pathLabel: String,
    val address: String,
    val balanceLamports: Long?,
    val isDefault: Boolean,
)

/** Every UI state of the Ledger signing flow. */
sealed interface LedgerUi {
    data object Idle : LedgerUi
    data object NeedPermissions : LedgerUi
    data class Scanning(val devices: List<Pair<String, String>>) : LedgerUi        // name to address
    data class Connecting(val deviceName: String) : LedgerUi
    data class Connected(
        val deviceName: String,
        val accounts: List<LedgerAccountUi>,
        val selectedAccountKey: String?,
        val isLoadingMore: Boolean = false,
        val canLoadMore: Boolean = true,
    ) : LedgerUi
    data class ConfirmOnDevice(val summary: String) : LedgerUi                      // "Approve on your Ledger Flex"
    data object Broadcasting : LedgerUi
    data class BroadcastUncertain(val signature: String) : LedgerUi
    data class Done(val signature: String) : LedgerUi
    data class Error(val message: String) : LedgerUi
}

/** A transfer the agent asked for (prefills the form; null = manual mode). */
data class AgentTransferRequest(
    val toBase58: String,
    val lamports: Long,
    val note: String?,
    /** Core-generated one-shot id; null only for the local scripted/manual demo path. */
    val intentId: String? = null,
    /** Core challenge echoed by the correlated result; null only for manual/demo transfers. */
    val responseNonce: String? = null,
    /** Rechecked immediately before signing so a review left open cannot outlive authorization. */
    val expiresAtEpochMillis: Long? = null,
)

// The chat-style dark surface set (kept local: this screen is always dark, system theme ignored).
private val Canvas = Color(0xFF000000)
private val Card = Color(0xFF1C1C1E)
private val CardBorder = Color(0xFF2A2A2A)
private val InkPrimary = Color(0xFFF2F2F7)
private val InkSecondary = Color(0xFF9A9A9E)

@Composable
fun LedgerScreen(
    ui: LedgerUi,
    agentRequest: AgentTransferRequest?,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (address: String) -> Unit,
    onSelectAccount: (accountKey: String) -> Unit,
    onLoadMore: () -> Unit,
    onAddAccountIndex: (index: Int) -> Unit = {},
    onForgetLedger: () -> Unit,
    onSend: (toBase58: String, amountSol: String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val canLeave = ui !is LedgerUi.ConfirmOnDevice &&
        ui !is LedgerUi.Broadcasting &&
        ui !is LedgerUi.BroadcastUncertain
    BackHandler(enabled = true) {
        if (canLeave) onBack()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding(),
    ) {
        LedgerHeader(onBack = onBack, enabled = canLeave)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (ui) {
                is LedgerUi.Idle -> HeroSection(
                    buttonLabel = "Start scan",
                    onButton = onStartScan,
                )
                is LedgerUi.NeedPermissions -> HeroSection(
                    buttonLabel = "Grant access",
                    onButton = onRequestPermissions,
                    footnote = "Bluetooth permission is needed to find your Ledger Flex nearby.",
                )
                is LedgerUi.Scanning -> ScanningSection(ui.devices, onConnect)
                is LedgerUi.Connecting -> ConnectingSection(ui.deviceName)
                is LedgerUi.Connected -> ConnectedSection(
                    ui = ui,
                    agentRequest = agentRequest,
                    onSelectAccount = onSelectAccount,
                    onLoadMore = onLoadMore,
                    onAddAccountIndex = onAddAccountIndex,
                    onForgetLedger = onForgetLedger,
                    onSend = onSend,
                )
                is LedgerUi.ConfirmOnDevice -> ConfirmSection(ui.summary)
                is LedgerUi.Broadcasting -> BroadcastingSection()
                is LedgerUi.BroadcastUncertain -> BroadcastUncertainSection(
                    signature = ui.signature,
                    onCheckAgain = onRetry,
                )
                is LedgerUi.Done -> DoneSection(ui.signature, onBack)
                is LedgerUi.Error -> ErrorSection(
                    message = ui.message,
                    onRetry = onRetry,
                    onForgetLedger = onForgetLedger,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun LedgerHeader(onBack: () -> Unit, enabled: Boolean) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptics.perform(HapticCue.Selection)
                onBack()
            },
            enabled = enabled,
        ) {
            AgenCGlyphIcon(
                glyph = AgenCGlyph.Back,
                contentDescription = "Back",
                tint = InkPrimary.copy(alpha = if (enabled) 1f else 0.32f),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "Ledger",
            color = InkPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Idle / NeedPermissions — hero explainer + primary pill
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeroSection(
    buttonLabel: String,
    onButton: () -> Unit,
    footnote: String? = null,
) {
    Spacer(Modifier.height(48.dp))
    LedgerDeviceGlyph(size = 96.dp)
    Spacer(Modifier.height(28.dp))
    Text(
        text = "sign with your Ledger Flex",
        color = InkPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Lock,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "keys never leave the device",
            color = InkSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(36.dp))
    PrimaryPill(label = buttonLabel, onClick = onButton)
    if (footnote != null) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = footnote,
            color = InkSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Scanning — monochrome pulse + device rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanningSection(
    devices: List<Pair<String, String>>,
    onConnect: (address: String) -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    ScanPulse()
    Spacer(Modifier.height(20.dp))
    Text(
        text = "looking for your Ledger…",
        color = InkSecondary,
        fontSize = 15.sp,
    )
    Spacer(Modifier.height(24.dp))
    if (devices.isEmpty()) {
        Text(
            text = "make sure the Flex is unlocked with Bluetooth on",
            color = InkSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    } else {
        devices.forEach { (name, address) ->
            DeviceRow(name = name, address = address, onTap = { onConnect(address) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** The soft scanning beacon — two nested monochrome circles, the outer one breathing. */
@Composable
private fun ScanPulse() {
    val transition = rememberInfiniteTransition(label = "ledger-scan-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.10f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(Onboarding.accentLight.copy(alpha = alpha), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Onboarding.accentLight.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AgenCGlyphIcon(
                glyph = AgenCGlyph.Bluetooth,
                contentDescription = null,
                tint = Onboarding.accentLight,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, onTap: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .clickable {
                haptics.perform(HapticCue.Selection)
                onTap()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerDeviceGlyph(size = 30.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = InkPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = address,
                color = InkSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = "connect",
            color = Onboarding.accentLight,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Connecting
// ---------------------------------------------------------------------------------------------

@Composable
private fun ConnectingSection(deviceName: String) {
    val status = if (deviceName.startsWith("opening ", ignoreCase = true)) {
        "$deviceName…"
    } else {
        "connecting to $deviceName…"
    }
    Spacer(Modifier.height(96.dp))
    CircularProgressIndicator(color = Onboarding.accentLight, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        text = status,
        color = InkSecondary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
    )
}

// ---------------------------------------------------------------------------------------------
// Connected — device chip, account card, transfer form
// ---------------------------------------------------------------------------------------------

@Composable
private fun ConnectedSection(
    ui: LedgerUi.Connected,
    agentRequest: AgentTransferRequest?,
    onSelectAccount: (accountKey: String) -> Unit,
    onLoadMore: () -> Unit,
    onAddAccountIndex: (index: Int) -> Unit,
    onForgetLedger: () -> Unit,
    onSend: (toBase58: String, amountSol: String) -> Unit,
) {
    // Local form state, re-prefilled whenever a (new) agent request arrives.
    var recipient by rememberSaveable(agentRequest?.toBase58, agentRequest?.lamports) {
        mutableStateOf(agentRequest?.toBase58 ?: "")
    }
    var amountText by rememberSaveable(agentRequest?.toBase58, agentRequest?.lamports) {
        mutableStateOf(agentRequest?.let { formatLamports(it.lamports) } ?: "")
    }
    val amount = amountText.trim().toBigDecimalOrNull()
    val amountLamports = runCatching {
        amount?.movePointRight(9)?.longValueExact()
    }.getOrNull()
    val selectedAccount = ui.accounts.firstOrNull { it.key == ui.selectedAccountKey }
        ?: ui.accounts.firstOrNull { it.isDefault }
    val amountIsValid = amountLamports != null && amountLamports > 0
    val balanceCoversAmount = selectedAccount?.balanceLamports == null ||
        (amountLamports != null && amountLamports < selectedAccount.balanceLamports)
    val canReview = selectedAccount != null &&
        looksBase58(recipient.trim()) && amountIsValid && balanceCoversAmount
    var confirmForget by rememberSaveable(ui.deviceName) { mutableStateOf(false) }
    var exactIndex by rememberSaveable(ui.deviceName) { mutableStateOf("") }
    val parsedExactIndex = exactIndex.toIntOrNull()
        ?.takeIf { it in 0..ai.tetsuo.agenc.ledger.SolanaLedgerApp.MAX_LEDGER_ACCOUNT_INDEX }

    Spacer(Modifier.height(8.dp))
    ConnectedDeviceHeader(
        deviceName = ui.deviceName,
        accountCount = ui.accounts.size,
    )
    Spacer(Modifier.height(26.dp))

    SectionLabel(
        label = "DERIVED SOLANA ACCOUNTS",
        trailing = if (ui.accounts.isEmpty()) null else "${ui.accounts.size} found",
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Choose the wallet @ledger uses in chat. Your selection is saved on this phone.",
        color = InkSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth(),
    )
    if (agentRequest != null) {
        Spacer(Modifier.height(10.dp))
        LockedSourceNotice()
    }
    Spacer(Modifier.height(14.dp))

    if (ui.accounts.isEmpty()) {
        LoadingAccountsCard()
    } else {
        ui.accounts.forEachIndexed { index, account ->
            LedgerAccountRow(
                account = account,
                ordinal = index,
                selected = account.key == selectedAccount?.key,
                enabled = agentRequest == null || selectedAccount == null,
                onSelect = { onSelectAccount(account.key) },
            )
            if (index != ui.accounts.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }

    if (ui.canLoadMore || ui.isLoadingMore) {
        Spacer(Modifier.height(10.dp))
        LoadMoreAccountsButton(
            loading = ui.isLoadingMore,
            onClick = onLoadMore,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Ledger accounts are derived paths, not a finite device list. If yours is outside the common scan, enter its exact account index.",
        color = InkSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = exactIndex,
            onValueChange = { value -> exactIndex = value.filter(Char::isDigit).take(7) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Account index") },
            placeholder = { Text("e.g. 42", color = InkSecondary, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = InkPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
            colors = ledgerFieldColors(),
        )
        Spacer(Modifier.width(10.dp))
        TextButton(
            enabled = parsedExactIndex != null && !ui.isLoadingMore,
            onClick = {
                parsedExactIndex?.let(onAddAccountIndex)
                exactIndex = ""
            },
        ) {
            Text("Add", color = if (parsedExactIndex != null) InkPrimary else InkSecondary)
        }
    }
    Spacer(Modifier.height(28.dp))

    // --- transfer form -------------------------------------------------------------------
    SectionLabel(
        label = "SEND SOL",
        trailing = selectedAccount?.pathLabel?.let(::displayDerivationPath),
    )
    if (selectedAccount != null) {
        Spacer(Modifier.height(10.dp))
        SelectedSourceStrip(selectedAccount)
    }
    Spacer(Modifier.height(10.dp))

    if (agentRequest != null) {
        AgentRequestedBadge(note = agentRequest.note)
        Spacer(Modifier.height(10.dp))
    }

    // Recipient — mono; LOCKED read-only when the agent asked for the transfer.
    TextField(
        value = recipient,
        onValueChange = { recipient = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Recipient") },
        readOnly = agentRequest != null,
        placeholder = { Text("recipient address", color = InkSecondary, fontSize = 14.sp) },
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = if (agentRequest != null) InkPrimary.copy(alpha = 0.75f) else InkPrimary,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = ledgerFieldColors(),
    )
    Spacer(Modifier.height(10.dp))

    // Amount — locked together with the recipient for an agent request. The exact request that
    // Core emitted must be the one reviewed here and on the Ledger device.
    TextField(
        value = amountText,
        onValueChange = { amountText = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        readOnly = agentRequest != null,
        label = { Text("Amount") },
        placeholder = { Text("amount (SOL)", color = InkSecondary, fontSize = 14.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = TextStyle(fontSize = 16.sp, color = InkPrimary),
        trailingIcon = {
            Text("SOL", color = InkSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        },
        shape = RoundedCornerShape(14.dp),
        colors = ledgerFieldColors(),
    )
    Spacer(Modifier.height(18.dp))

    PrimaryPill(
        label = "Review on Ledger",
        enabled = canReview,
        onClick = { onSend(recipient.trim(), amountText.trim()) },
    )
    if (!balanceCoversAmount) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Amount must be below the balance. The live network fee is checked before signing.",
            color = InkPrimary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "you approve the exact transfer on the device screen",
        color = InkSecondary.copy(alpha = 0.7f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(42.dp))
    if (confirmForget) {
        ForgetLedgerConfirmation(
            deviceName = ui.deviceName,
            onCancel = { confirmForget = false },
            onConfirm = onForgetLedger,
        )
    } else {
        ForgetLedgerButton(onClick = { confirmForget = true })
    }
}

@Composable
private fun ConnectedDeviceHeader(deviceName: String, accountCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerDeviceGlyph(size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = deviceName,
                color = InkPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (accountCount == 1) "1 derived account" else "$accountCount derived accounts",
                color = InkSecondary,
                fontSize = 12.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(InkPrimary, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("connected", color = InkSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionLabel(label: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = InkSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = InkSecondary.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LedgerAccountRow(
    account: LedgerAccountUi,
    ordinal: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    val background = if (selected) InkPrimary else Card
    val primary = when {
        selected -> Canvas
        enabled -> InkPrimary
        else -> InkPrimary.copy(alpha = 0.46f)
    }
    val secondary = when {
        selected -> Canvas.copy(alpha = 0.62f)
        enabled -> InkSecondary
        else -> InkSecondary.copy(alpha = 0.48f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) {
                haptics.perform(HapticCue.Selection)
                onSelect()
            }
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ACCOUNT ${ordinal + 1}",
                    color = secondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = displayDerivationPath(account.pathLabel),
                    color = secondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = middleEllipsis(account.address, keep = 7),
                color = primary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = account.balanceLamports?.let { "${formatLamports(it)} SOL" }
                    ?: "balance loading…",
                color = secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (selected || account.isDefault) {
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .background(primary, RoundedCornerShape(12.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "@ledger",
                        color = background,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AgenCGlyphIcon(
                    glyph = AgenCGlyph.Check,
                    contentDescription = "Default account",
                    tint = primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        } else {
            Text(
                text = if (enabled) "use" else "locked",
                color = secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LockedSourceNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Lock,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "Source wallet locked to this @ledger request",
            color = InkSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LoadingAccountsCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = InkPrimary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(11.dp))
        Text("deriving Solana accounts…", color = InkSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun LoadMoreAccountsButton(loading: Boolean, onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .clickable(enabled = !loading) {
                haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = InkPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(9.dp))
            Text("deriving more…", color = InkSecondary, fontSize = 13.sp)
        } else {
            Text(
                text = "Load more accounts",
                color = InkPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SelectedSourceStrip(account: LedgerAccountUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Wallet,
            contentDescription = null,
            tint = InkSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = middleEllipsis(account.address, keep = 8),
            color = InkPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text("@ledger", color = InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ForgetLedgerButton(onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Text(
        text = "Forget this Ledger",
        color = InkSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable {
                haptics.perform(HapticCue.Selection)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

@Composable
private fun ForgetLedgerConfirmation(
    deviceName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "Forget $deviceName?",
            color = InkPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This removes the saved device and @ledger wallet from this phone. Your funds stay on the Ledger.",
            color = InkSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(15.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cancel",
                color = InkSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Forget",
                color = Canvas,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(InkPrimary, RoundedCornerShape(14.dp))
                    .clickable {
                        haptics.perform(HapticCue.Reject)
                        onConfirm()
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun AgentRequestedBadge(note: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgenCGlyphIcon(
                glyph = AgenCGlyph.Agent,
                contentDescription = null,
                tint = Onboarding.accentLight,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "agent requested — transfer locked",
                color = Onboarding.accentLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note,
                color = InkSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ledgerFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Card,
    unfocusedContainerColor = Card,
    disabledContainerColor = Card.copy(alpha = 0.6f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = Onboarding.accentLight,
    focusedTextColor = InkPrimary,
    unfocusedTextColor = InkPrimary,
)

// ---------------------------------------------------------------------------------------------
// ConfirmOnDevice — approve on the Flex
// ---------------------------------------------------------------------------------------------

@Composable
private fun ConfirmSection(summary: String) {
    Spacer(Modifier.height(40.dp))
    LedgerDeviceGlyph(size = 120.dp)
    Spacer(Modifier.height(28.dp))
    Text(
        text = "Approve on your Ledger Flex",
        color = InkPrimary,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = summary,
        color = Onboarding.accentLight,
        fontSize = 15.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = "the transaction shows on the device screen — verify the address matches",
        color = InkSecondary.copy(alpha = 0.8f),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

/**
 * A drawn Ledger-Flex outline: a rounded slab with the big screen taking most of the face
 * (the Flex is screen-first) and the small button dot on the side. Pure Compose, no asset.
 */
@Composable
private fun LedgerDeviceGlyph(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(width = size, height = size * 0.72f)
            .border(2.dp, Onboarding.accentLight, RoundedCornerShape(size * 0.16f))
            .padding(size * 0.09f),
    ) {
        // The screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = size * 0.14f)
                .background(Onboarding.accentLight.copy(alpha = 0.16f), RoundedCornerShape(size * 0.08f)),
        )
        // The side button.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(size * 0.075f)
                .background(Onboarding.accentLight, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Broadcasting
// ---------------------------------------------------------------------------------------------

@Composable
private fun BroadcastingSection() {
    Spacer(Modifier.height(96.dp))
    CircularProgressIndicator(color = Onboarding.accentLight, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        text = "broadcasting…",
        color = InkSecondary,
        fontSize = 15.sp,
    )
}

@Composable
private fun BroadcastUncertainSection(signature: String, onCheckAgain: () -> Unit) {
    Spacer(Modifier.height(52.dp))
    AgenCGlyphIcon(
        glyph = AgenCGlyph.Warning,
        contentDescription = null,
        tint = InkPrimary,
        modifier = Modifier.size(42.dp),
    )
    Spacer(Modifier.height(18.dp))
    Text(
        text = "Checking the submitted transaction",
        color = InkPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "The network reply was interrupted. AgenC will only check or rebroadcast these exact signed bytes — it will never ask you to sign this transfer again.",
        color = InkSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    SelectionContainer {
        Text(
            text = signature,
            color = InkPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(22.dp))
    PrimaryPill(label = "Check again", onClick = onCheckAgain)
}

// ---------------------------------------------------------------------------------------------
// Done
// ---------------------------------------------------------------------------------------------

@Composable
private fun DoneSection(signature: String, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Spacer(Modifier.height(56.dp))
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(InkPrimary.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Check,
            contentDescription = null,
            tint = InkPrimary,
            modifier = Modifier.size(38.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text = "sent",
        color = InkPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(14.dp))
    SelectionContainer {
        Text(
            text = signature,
            color = InkPrimary.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "view on solscan.io",
        color = Onboarding.accentLight,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable { uriHandler.openUri("https://solscan.io/tx/$signature") }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Spacer(Modifier.height(28.dp))
    PrimaryPill(label = "Done", onClick = onBack)
}

// ---------------------------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------------------------

@Composable
private fun ErrorSection(
    message: String,
    onRetry: () -> Unit,
    onForgetLedger: () -> Unit,
) {
    Spacer(Modifier.height(56.dp))
    AgenCGlyphIcon(
        glyph = AgenCGlyph.Warning,
        contentDescription = null,
        tint = InkPrimary,
        modifier = Modifier.size(44.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = message,
        color = InkPrimary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(26.dp))
    PrimaryPill(label = "Retry", onClick = onRetry)
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Use another Ledger",
        color = InkSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(onClick = onForgetLedger)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Shared pieces + helpers
// ---------------------------------------------------------------------------------------------

/** The full-width 54dp / 27dp-radius brand pill used across onboarding. */
@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val haptics = rememberAgenCHaptics()
    Button(
        onClick = {
            haptics.perform(HapticCue.Action)
            onClick()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Onboarding.accent,
            contentColor = Onboarding.bg,
            disabledContainerColor = Onboarding.accent.copy(alpha = 0.20f),
            disabledContentColor = Onboarding.ink.copy(alpha = 0.38f),
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** "3ekX…9zQf" middle-ellipsis for base58 strings that never fit on a phone. */
internal fun middleEllipsis(value: String, keep: Int = 6): String =
    if (value.length <= keep * 2 + 1) value
    else value.take(keep) + "…" + value.takeLast(keep)

private fun displayDerivationPath(value: String): String =
    if (value.startsWith("m/")) value else "m/$value"

/** Cheap shape check (NOT validation): base58 alphabet, 32–44 chars — enough to gate the button. */
internal fun looksBase58(value: String): Boolean =
    value.length in 32..44 && value.all { it in BASE58_ALPHABET }

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** SOL amount for the form prefill — up to 9 decimals, trailing zeros trimmed ("0.01", not "0.010000000"). */
internal fun formatLamports(lamports: Long): String =
    BigDecimal.valueOf(lamports, 9).stripTrailingZeros().toPlainString()

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "Ledger — scanning", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LedgerScreenScanningPreview() {
    AgenCTheme {
        LedgerScreen(
            ui = LedgerUi.Scanning(
                devices = listOf(
                    "Ledger Flex 7B2A" to "F0:12:9C:4E:7B:2A",
                    "Ledger Flex D410" to "C8:55:31:AA:D4:10",
                ),
            ),
            agentRequest = null,
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
            onSelectAccount = {},
            onLoadMore = {},
            onForgetLedger = {},
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(name = "Ledger — connected (manual)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LedgerScreenConnectedManualPreview() {
    AgenCTheme {
        LedgerScreen(
            ui = LedgerUi.Connected(
                deviceName = "Ledger Flex 7B2A",
                accounts = previewLedgerAccounts(),
                selectedAccountKey = "0",
            ),
            agentRequest = null,
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
            onSelectAccount = {},
            onLoadMore = {},
            onForgetLedger = {},
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(name = "Ledger — connected (agent request)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LedgerScreenConnectedAgentPreview() {
    AgenCTheme {
        LedgerScreen(
            ui = LedgerUi.Connected(
                deviceName = "Ledger Flex 7B2A",
                accounts = previewLedgerAccounts(),
                selectedAccountKey = "1",
            ),
            agentRequest = AgentTransferRequest(
                toBase58 = "2WARqUL5DHfsQwqkQ9XvbHgYmxV8u1kCkxTeVJ5iTZr9",
                lamports = 50_000_000L, // 0.05 SOL
                note = "top up the autonomous hot wallet",
            ),
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
            onSelectAccount = {},
            onLoadMore = {},
            onForgetLedger = {},
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(name = "Ledger — confirm on device", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LedgerScreenConfirmPreview() {
    AgenCTheme {
        LedgerScreen(
            ui = LedgerUi.ConfirmOnDevice(summary = "◎ 0.05 → 2WARqU…iTZr9"),
            agentRequest = null,
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
            onSelectAccount = {},
            onLoadMore = {},
            onForgetLedger = {},
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(name = "Ledger — done", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LedgerScreenDonePreview() {
    AgenCTheme {
        LedgerScreen(
            ui = LedgerUi.Done(
                signature = "3byyjE7jQmVwqK2rHhGdX8sT4cN9pZaUvLmYoWfEiJkD5nRxAbCt6uS1eMhPgB",
            ),
            agentRequest = null,
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
            onSelectAccount = {},
            onLoadMore = {},
            onForgetLedger = {},
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}

private fun previewLedgerAccounts(): List<LedgerAccountUi> = listOf(
    LedgerAccountUi(
        key = "0",
        pathLabel = "m/44'/501'/0'/0'",
        address = "FoxPvzq3TXTfEyM9WcoDx9QRSaSaFvJhLkPsMWpVQKrT",
        balanceLamports = 13_980_000_000,
        isDefault = true,
    ),
    LedgerAccountUi(
        key = "1",
        pathLabel = "m/44'/501'/1'/0'",
        address = "4wBqpZM9wKh5pwsby7CkprBKikTbkQ6xqA1mVhCyKRNh",
        balanceLamports = 250_000_000,
        isDefault = false,
    ),
    LedgerAccountUi(
        key = "2",
        pathLabel = "m/44'/501'/2'/0'",
        address = "8iKdWPX7MjymKkCJ8xNFYyG98iQAuiU7ZqfQyLkx2wZ3",
        balanceLamports = 0,
        isDefault = false,
    ),
)
