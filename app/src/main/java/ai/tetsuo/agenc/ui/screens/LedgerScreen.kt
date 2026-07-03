package ai.tetsuo.agenc.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.Onboarding
import java.util.Locale

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
 *  - brand purple pills (54dp, 27dp radius — the PairingScreen pill), monospace for anything base58
 *  - Scanning = soft purple pulse over the device list; Connected = device chip + account card +
 *    transfer form; ConfirmOnDevice = a drawn Ledger-Flex outline (screen-first, like the device)
 *  - when the agent asked for the transfer ([AgentTransferRequest]) the recipient is prefilled and
 *    LOCKED read-only with an "agent requested" badge — task text can never retarget funds from
 *    inside this screen, the human verifies the address again on the Ledger itself.
 *
 * The only local state is the two form fields (recipient / amount), keyed on [agentRequest] so an
 * incoming agent request re-prefills them.
 */

/** Every UI state of the Ledger signing flow. */
sealed interface LedgerUi {
    data object Idle : LedgerUi
    data object NeedPermissions : LedgerUi
    data class Scanning(val devices: List<Pair<String, String>>) : LedgerUi        // name to address
    data class Connecting(val deviceName: String) : LedgerUi
    data class Connected(val deviceName: String, val address: String, val balanceSol: Double?) : LedgerUi   // Ledger account (base58, balance may still be loading)
    data class ConfirmOnDevice(val summary: String) : LedgerUi                      // "Approve on your Ledger Flex"
    data object Broadcasting : LedgerUi
    data class Done(val signature: String) : LedgerUi
    data class Error(val message: String) : LedgerUi
}

/** A transfer the agent asked for (prefills the form; null = manual mode). */
data class AgentTransferRequest(val toBase58: String, val lamports: Long, val note: String?)

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
    onSend: (toBase58: String, amountSol: Double) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding(),
    ) {
        LedgerHeader(onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                is LedgerUi.Connected -> ConnectedSection(ui, agentRequest, onSend)
                is LedgerUi.ConfirmOnDevice -> ConfirmSection(ui.summary)
                is LedgerUi.Broadcasting -> BroadcastingSection()
                is LedgerUi.Done -> DoneSection(ui.signature, onBack)
                is LedgerUi.Error -> ErrorSection(ui.message, onRetry)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun LedgerHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
                contentDescription = "Back",
                tint = InkPrimary,
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
        Icon(
            imageVector = Icons.Filled.Lock,
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
// Scanning — purple pulse + device rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScanningSection(
    devices: List<Pair<String, String>>,
    onConnect: (address: String) -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    PurplePulse()
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

/** The soft scanning beacon — two nested purple circles, the outer one breathing. */
@Composable
private fun PurplePulse() {
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
                .background(Brand.accent.copy(alpha = alpha), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Brand.accent.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = Onboarding.accentLight,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
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
            color = Brand.accent,
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
    Spacer(Modifier.height(96.dp))
    CircularProgressIndicator(color = Brand.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        text = "connecting to $deviceName…",
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
    onSend: (toBase58: String, amountSol: Double) -> Unit,
) {
    // Local form state, re-prefilled whenever a (new) agent request arrives.
    var recipient by remember(agentRequest) {
        mutableStateOf(agentRequest?.toBase58 ?: "")
    }
    var amountText by remember(agentRequest) {
        mutableStateOf(agentRequest?.let { formatSol(it.lamports / 1_000_000_000.0) } ?: "")
    }
    val amount = amountText.trim().toDoubleOrNull() ?: 0.0
    val canReview = looksBase58(recipient.trim()) && amount > 0.0

    Spacer(Modifier.height(8.dp))
    ConnectedChip(ui.deviceName)
    Spacer(Modifier.height(18.dp))
    AccountCard(address = ui.address, balanceSol = ui.balanceSol)
    Spacer(Modifier.height(22.dp))

    // --- transfer form -------------------------------------------------------------------
    Text(
        text = "SEND SOL",
        color = InkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
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

    // Amount — decimal keyboard, always editable (the human can shrink an agent ask).
    TextField(
        value = amountText,
        onValueChange = { amountText = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
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
        onClick = { onSend(recipient.trim(), amount) },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "you approve the exact transfer on the device screen",
        color = InkSecondary.copy(alpha = 0.7f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ConnectedChip(deviceName: String) {
    Row(
        modifier = Modifier
            .background(Card, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Brand.success, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = deviceName,
            color = InkPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AccountCard(address: String, balanceSol: Double?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Text(
            text = "ACCOUNT",
            color = InkSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = middleEllipsis(address),
            color = InkPrimary,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        if (balanceSol != null) {
            Text(
                text = "◎ " + String.format(Locale.US, "%.2f", balanceSol),
                color = Onboarding.accentLight,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Brand.accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("loading balance…", color = InkSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AgentRequestedBadge(note: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Onboarding.accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .border(1.dp, Onboarding.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = Onboarding.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "agent requested — recipient locked",
                color = Onboarding.accent,
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
    cursorColor = Brand.accent,
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
            .border(2.dp, Brand.accent, RoundedCornerShape(size * 0.16f))
            .padding(size * 0.09f),
    ) {
        // The screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = size * 0.14f)
                .background(Brand.accent.copy(alpha = 0.20f), RoundedCornerShape(size * 0.08f)),
        )
        // The side button.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(size * 0.075f)
                .background(Brand.accent, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Broadcasting
// ---------------------------------------------------------------------------------------------

@Composable
private fun BroadcastingSection() {
    Spacer(Modifier.height(96.dp))
    CircularProgressIndicator(color = Brand.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        text = "broadcasting…",
        color = InkSecondary,
        fontSize = 15.sp,
    )
}

// ---------------------------------------------------------------------------------------------
// Done
// ---------------------------------------------------------------------------------------------

@Composable
private fun DoneSection(signature: String, onBack: () -> Unit) {
    Spacer(Modifier.height(56.dp))
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Brand.success.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = Brand.success,
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
    Text(
        text = middleEllipsis(signature, keep = 10),
        color = InkPrimary.copy(alpha = 0.85f),
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "view on solscan.io",
        color = InkSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(28.dp))
    PrimaryPill(label = "Done", onClick = onBack)
}

// ---------------------------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------------------------

@Composable
private fun ErrorSection(message: String, onRetry: () -> Unit) {
    Spacer(Modifier.height(56.dp))
    Icon(
        imageVector = Icons.Filled.WarningAmber,
        contentDescription = null,
        tint = Brand.attention,
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
}

// ---------------------------------------------------------------------------------------------
// Shared pieces + helpers
// ---------------------------------------------------------------------------------------------

/** The full-width 54dp / 27dp-radius brand pill used across onboarding. */
@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.accent,
            contentColor = Color.White,
            disabledContainerColor = Brand.accent.copy(alpha = 0.25f),
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** "3ekX…9zQf" middle-ellipsis for base58 strings that never fit on a phone. */
internal fun middleEllipsis(value: String, keep: Int = 6): String =
    if (value.length <= keep * 2 + 1) value
    else value.take(keep) + "…" + value.takeLast(keep)

/** Cheap shape check (NOT validation): base58 alphabet, 32–44 chars — enough to gate the button. */
internal fun looksBase58(value: String): Boolean =
    value.length in 32..44 && value.all { it in BASE58_ALPHABET }

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** SOL amount for the form prefill — up to 9 decimals, trailing zeros trimmed ("0.01", not "0.010000000"). */
internal fun formatSol(sol: Double): String =
    String.format(Locale.US, "%.9f", sol).trimEnd('0').trimEnd('.')

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
                address = "FoxPvzq3TXTfEyM9WcoDx9QRSaSaFvJhLkPsMWpVQKrT",
                balanceSol = 13.98,
            ),
            agentRequest = null,
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
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
                address = "FoxPvzq3TXTfEyM9WcoDx9QRSaSaFvJhLkPsMWpVQKrT",
                balanceSol = 13.98,
            ),
            agentRequest = AgentTransferRequest(
                toBase58 = "2WARqUL5DHfsQwqkQ9XvbHgYmxV8u1kCkxTeVJ5iTZr9",
                lamports = 50_000_000L, // 0.05 SOL
                note = "top up the autonomous hot wallet",
            ),
            onRequestPermissions = {},
            onStartScan = {},
            onConnect = {},
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
            onSend = { _, _ -> },
            onRetry = {},
            onBack = {},
        )
    }
}
