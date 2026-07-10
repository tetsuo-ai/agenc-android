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
import ai.tetsuo.agenc.ui.tint

/**
 * Shared visual helpers for the ported auth/pairing/sessions/account screens.
 * Kept separate so the individual screen files stay focused on layout.
 */

/** Monochrome outlined-text-field colors tuned for the dark onboarding and chat canvases. */
@Composable
internal fun brandedFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Onboarding.ink,
    unfocusedTextColor = Onboarding.ink,
    disabledTextColor = Onboarding.ink.copy(alpha = 0.38f),
    errorTextColor = Onboarding.ink,
    focusedBorderColor = Onboarding.accentLight,
    unfocusedBorderColor = ChatPalette.divider,
    disabledBorderColor = ChatPalette.divider.copy(alpha = 0.55f),
    errorBorderColor = Brand.danger,
    cursorColor = Onboarding.ink,
    errorCursorColor = Brand.danger,
    focusedLabelColor = Onboarding.ink,
    unfocusedLabelColor = ChatPalette.secondaryText,
    disabledLabelColor = ChatPalette.secondaryText.copy(alpha = 0.55f),
    errorLabelColor = Brand.danger,
    focusedPlaceholderColor = ChatPalette.secondaryText,
    unfocusedPlaceholderColor = ChatPalette.secondaryText,
    disabledPlaceholderColor = ChatPalette.secondaryText.copy(alpha = 0.55f),
    errorPlaceholderColor = ChatPalette.secondaryText,
)

/**
 * Shared onboarding treatment drawn over [Onboarding.bg]: a restrained off-white halo near the top
 * and a near-black vertical fade. It adds depth without introducing a chromatic accent.
 */
internal fun Modifier.onboardingBackground(): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Onboarding.accentLight.copy(alpha = 0.07f), Color.Transparent),
            center = Offset(size.width / 2f, size.height * 0.26f),
            radius = size.maxDimension * 0.68f,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Onboarding.bgDeep.copy(alpha = 0.92f)),
            startY = size.height * 0.34f,
            endY = size.height,
        ),
    )
}

/** Full-size variant used where a Box needs to fill first. */
internal fun Modifier.onboardingSurface(): Modifier = fillMaxSize().onboardingBackground()

/**
 * Kept as a screen-level compatibility helper; the canonical semantic mapping lives in
 * [SessionLifecycle.tint].
 */
internal fun SessionLifecycle.pillTint(): Color = tint
