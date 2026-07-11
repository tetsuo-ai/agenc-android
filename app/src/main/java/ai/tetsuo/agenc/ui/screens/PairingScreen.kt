package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.Onboarding
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/**
 * "Connect your computer" — the Android port of iOS `PairGateView`, pixel-faithful to the reference.
 *
 * A focused monochrome setup with one primary QR action and a secondary manual-code path.
 *
 * Stateless: the only local state is the raw code text the user is typing. Normalization (uppercase,
 * strip separators) happens here so the caller receives a clean code via [onClaimCode].
 *
 * @param machineName non-null once a computer has been linked (drives the success banner).
 * @param isLinking true while a claim is in flight (spinner + disabled Connect).
 * @param error optional error string under the action.
 * @param onClaimCode fires with the normalized code when the user taps Connect.
 * @param onScanQr optional host callback fired before this screen presents its CameraX overlay.
 * @param initialCode optional deep-link code to prefill; it is never submitted automatically.
 */
@Composable
fun PairingScreen(
    machineName: String?,
    isLinking: Boolean,
    error: String?,
    onClaimCode: (String) -> Unit,
    onScanQr: () -> Unit,
    initialCode: String? = null,
) {
    val haptics = rememberAgenCHaptics()
    var code by rememberSaveable { mutableStateOf("") }
    // A camera overlay is transient UI. Never resurrect it after a cold restart or task restore.
    var showScanner by remember { mutableStateOf(false) }
    LaunchedEffect(initialCode) {
        val prefill = initialCode?.let(::normalizePairCode)
        if (prefill?.length == 8) code = prefill
    }
    val normalized = code
    val canLink = normalized.length == 8 && !isLinking && machineName == null

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Onboarding.bg)
            .onboardingBackground()
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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

            // High-contrast primary action.
            Button(
                onClick = {
                    haptics.perform(HapticCue.Action)
                    onScanQr()
                    showScanner = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Onboarding.accent,
                    contentColor = Onboarding.bg,
                ),
            ) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Onboarding.bg,
                )
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code", fontWeight = FontWeight.SemiBold, color = Onboarding.bg, fontSize = 16.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Dark code field (XXXX-XXXX). The focus border replaces a blinking caret, which would
            // otherwise sit awkwardly in the middle of the centered placeholder.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pairing code",
                    color = Onboarding.ink.copy(alpha = 0.72f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = code,
                    // Keep the editable value separator-free. Inserting '-' into the actual value
                    // on every keystroke resets the IME selection and can reorder fast input.
                    onValueChange = { code = normalizePairCode(it).take(8) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    singleLine = true,
                    enabled = !isLinking && machineName == null,
                    placeholder = {
                        Text(
                            "XXXX-XXXX",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            color = Onboarding.ink.copy(alpha = 0.34f),
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
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canLink) onClaimCode(normalized) },
                    ),
                    visualTransformation = PairCodeVisualTransformation,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Onboarding.ink,
                        unfocusedTextColor = Onboarding.ink,
                        disabledTextColor = Onboarding.ink.copy(alpha = 0.42f),
                        focusedContainerColor = Onboarding.ink.copy(alpha = 0.07f),
                        unfocusedContainerColor = Onboarding.ink.copy(alpha = 0.05f),
                        disabledContainerColor = Onboarding.ink.copy(alpha = 0.04f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        errorBorderColor = Color.Transparent,
                        cursorColor = Color.Transparent,
                        errorCursorColor = Color.Transparent,
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Secondary action — enabled only when all eight code characters are present.
            Button(
                onClick = {
                    haptics.perform(HapticCue.Action)
                    onClaimCode(normalized)
                },
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

        }
    }

    // Fullscreen camera scanner overlay — opened by the "Scan QR code" pill.
    if (showScanner) {
        QrScannerOverlay(
            onCode = {
                val scanned = normalizePairCode(it)
                if (scanned.length == 8) {
                    showScanner = false
                    onClaimCode(scanned)
                }
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
            tint = Brand.success,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Linked to $machineName",
            color = Onboarding.ink,
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
            tint = Brand.danger,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = error, color = Onboarding.ink, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Uppercase + keep only letters/digits (matches the iOS `normalized` computed property). */
internal fun normalizePairCode(raw: String): String =
    raw.uppercase().filter { it.isLetterOrDigit() }

/** Human-friendly XXXX-XXXX presentation without mutating the IME's separator-free value. */
internal object PairCodeVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text.take(8)
        val formatted = if (raw.length > 4) {
            raw.take(4) + "-" + raw.drop(4)
        } else {
            raw
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val safe = offset.coerceIn(0, raw.length)
                return if (raw.length > 4 && safe > 4) safe + 1 else safe
            }

            override fun transformedToOriginal(offset: Int): Int {
                val safe = offset.coerceIn(0, formatted.length)
                return if (raw.length > 4 && safe > 4) {
                    (safe - 1).coerceAtMost(raw.length)
                } else {
                    safe.coerceAtMost(raw.length)
                }
            }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

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
