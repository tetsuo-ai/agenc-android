package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Onboarding

/**
 * "Connect your computer" — the Android port of iOS `PairGateView`, pixel-faithful to the reference.
 *
 * A purple laptop+phone icon, a white bold heading, a subtitle that renders `agenc remote on` in
 * monospace, a full-width purple "Scan QR code" pill, a dark code field (XXXX-XXXX placeholder), and
 * a dark "Connect" button that stays disabled until a code is entered. All over the shared
 * onboarding gradient background.
 *
 * Stateless: the only local state is the raw code text the user is typing. Normalization (uppercase,
 * strip separators) happens here so the caller receives a clean code via [onClaimCode].
 *
 * @param machineName non-null once a computer has been linked (drives the success banner).
 * @param isLinking true while a claim is in flight (spinner + disabled Connect).
 * @param error optional error string under the action.
 * @param onClaimCode fires with the normalized code when the user taps Connect.
 * @param onScanQr fires when the user taps Scan QR — the caller presents the camera.
 */
@Composable
fun PairingScreen(
    machineName: String?,
    isLinking: Boolean,
    error: String?,
    onClaimCode: (String) -> Unit,
    onScanQr: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    val normalized = normalizePairCode(code)
    val canLink = normalized.isNotEmpty() && !isLinking && machineName == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Onboarding.bg)
            .onboardingBackground()
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            Icon(
                imageVector = Icons.Filled.DevicesOther,
                contentDescription = null,
                tint = Onboarding.accent,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "connect your computer",
                color = Onboarding.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = pairingSubtitle(),
                color = Onboarding.ink.copy(alpha = 0.6f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(26.dp))

            // Full-width purple "Scan QR code" pill.
            Button(
                onClick = {
                    onScanQr()
                    showScanner = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Onboarding.accent),
            ) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Dark code field (XXXX-XXXX).
            TextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                singleLine = true,
                enabled = !isLinking && machineName == null,
                placeholder = {
                    Text(
                        "XXXX-XXXX",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        color = Onboarding.ink.copy(alpha = 0.35f),
                        fontSize = 22.sp,
                        letterSpacing = 4.sp,
                    )
                },
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    letterSpacing = 4.sp,
                    color = Onboarding.ink,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Onboarding.ink.copy(alpha = 0.08f),
                    unfocusedContainerColor = Onboarding.ink.copy(alpha = 0.08f),
                    disabledContainerColor = Onboarding.ink.copy(alpha = 0.05f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Onboarding.accent,
                ),
            )

            Spacer(Modifier.height(12.dp))

            // Dark "Connect" button — disabled until a code is entered.
            Button(
                onClick = { onClaimCode(normalized) },
                enabled = canLink,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Onboarding.ink.copy(alpha = 0.10f),
                    contentColor = Onboarding.accent,
                    disabledContainerColor = Onboarding.ink.copy(alpha = 0.06f),
                    disabledContentColor = Onboarding.ink.copy(alpha = 0.3f),
                ),
            ) {
                if (isLinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Onboarding.accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("connecting", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                machineName != null -> SuccessBanner(machineName)
                error != null -> PairErrorLabel(error)
            }

            Spacer(Modifier.weight(1f))
        }
    }

    // Fullscreen camera scanner overlay — opened by the "Scan QR code" pill.
    if (showScanner) {
        QrScannerOverlay(
            onCode = {
                showScanner = false
                onClaimCode(it)
            },
            onClose = { showScanner = false },
        )
    }
}

/** "on your computer, run `agenc remote on`, then scan the QR or enter the code" — `agenc remote on` mono. */
@Composable
private fun pairingSubtitle() = buildAnnotatedString {
    append("on your computer, run ")
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Onboarding.ink.copy(alpha = 0.8f))) {
        append("agenc remote on")
    }
    append(", then scan the QR or enter the code")
}

@Composable
private fun SuccessBanner(machineName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF34C759),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Linked to $machineName",
            color = Color(0xFF34C759),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PairErrorLabel(error: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = Color(0xFFEE9430),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = error, color = Color(0xFFEE9430), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Uppercase + keep only letters/digits (matches the iOS `normalized` computed property). */
internal fun normalizePairCode(raw: String): String =
    raw.uppercase().filter { it.isLetterOrDigit() }

@Preview(name = "Pairing — empty", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun PairingScreenEmptyPreview() {
    AgenCTheme {
        PairingScreen(
            machineName = null,
            isLinking = false,
            error = null,
            onClaimCode = {},
            onScanQr = {},
        )
    }
}

@Preview(name = "Pairing — linking", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun PairingScreenLinkingPreview() {
    AgenCTheme {
        PairingScreen(
            machineName = null,
            isLinking = true,
            error = null,
            onClaimCode = {},
            onScanQr = {},
        )
    }
}

@Preview(name = "Pairing — error", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun PairingScreenErrorPreview() {
    AgenCTheme {
        PairingScreen(
            machineName = null,
            isLinking = false,
            error = "That code expired. Run agenc remote on again.",
            onClaimCode = {},
            onScanQr = {},
        )
    }
}

@Preview(name = "Pairing — linked", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun PairingScreenLinkedPreview() {
    AgenCTheme {
        PairingScreen(
            machineName = "Pavel's MacBook Pro",
            isLinking = false,
            error = null,
            onClaimCode = {},
            onScanQr = {},
        )
    }
}
