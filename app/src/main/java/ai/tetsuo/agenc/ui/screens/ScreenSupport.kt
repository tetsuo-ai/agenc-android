package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette
import ai.tetsuo.agenc.ui.Onboarding

/**
 * Shared visual helpers for the ported auth/pairing/sessions/account screens.
 * Kept separate so the individual screen files stay focused on layout.
 */

/** Branded outlined-text-field colors: purple focus ring on the dark canvas. */
@Composable
internal fun brandedFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Brand.accent,
    unfocusedBorderColor = ChatPalette.divider,
    cursorColor = Brand.accent,
    focusedLabelColor = Brand.accent,
    unfocusedLabelColor = ChatPalette.secondaryText,
    focusedPlaceholderColor = ChatPalette.secondaryText,
    unfocusedPlaceholderColor = ChatPalette.secondaryText,
)

/**
 * The shared onboarding background drawn over [Onboarding.bg] — a centered purple radial glow plus a
 * vertical fade toward [Onboarding.bgDeep]. 1:1 with the iOS `Palette.background` ZStack used by the
 * Sign-in and Connect screens.
 */
internal fun Modifier.onboardingBackground(): Modifier = drawBehind {
    // Centered purple radial glow.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Onboarding.accent.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.maxDimension * 0.55f,
        ),
    )
    // Vertical fade toward the deep purple-dark at the bottom.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Onboarding.bgDeep),
        ),
    )
}

/** Full-size variant used where a Box needs to fill first. */
internal fun Modifier.onboardingSurface(): Modifier = fillMaxSize().onboardingBackground()

/**
 * Pill tint derived locally from the lifecycle. The Android [SessionLifecycle] only exposes
 * `.label` + `.isActive` (no `.tint`, unlike iOS), so we map the case → accent here.
 */
internal fun SessionLifecycle.pillTint(): Color = when (this) {
    SessionLifecycle.Running -> Brand.accent
    SessionLifecycle.AwaitingPermission -> Color(0xFFFFB020)   // attention amber
    SessionLifecycle.AwaitingInput -> Color(0xFFFFB020)
    SessionLifecycle.Done -> Color(0xFF34C759)                 // success green
    SessionLifecycle.Idle -> ChatPalette.secondaryText
    is SessionLifecycle.Error -> Brand.danger
}
