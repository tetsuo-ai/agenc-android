package ai.tetsuo.agenc.ui

import ai.tetsuo.agenc.model.SessionLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Design system ported from the iOS app's Theme.swift (Brand) + ThemeManager (default accent =
 * purple) + SignInView (the onboarding palette). Three surfaces, matching iOS:
 *  - Onboarding (login / pairing): always dark, the marketplace gradient palette.
 *  - Main shell (Home / Sessions / Account): follows the SYSTEM light/dark, brand purple accent.
 *  - Chat: always dark (ChatGPT-style).
 */
object Brand {
    val accent = Color(0xFF7D4FEB)        // ThemeManager .purple = rgb(0.49,0.31,0.92)
    val accentSoft = Color(0x1F7D4FEB)    // ~12%
    val running = Color(0xFF5C8CF7)       // rgb(0.36,0.55,0.97)
    val attention = Color(0xFFEE9430)     // rgb(0.93,0.58,0.18)
    val success = Color(0xFF45A86B)       // rgb(0.27,0.66,0.42)
    val danger = Color(0xFFD94D52)        // rgb(0.85,0.30,0.32)
    val secondary = Color(0xFF8E8E93)
}

/** Onboarding (login/pairing) palette — hardcoded dark, from SignInView (marketplace.agenc.tech). */
object Onboarding {
    val bg = Color(0xFF101116)            // rgb(0.063,0.067,0.086)
    val bgDeep = Color(0xFF14101F)        // rgb(0.078,0.063,0.122)
    val accent = Color(0xFFBA63EF)        // rgb(0.729,0.388,0.937)
    val accentLight = Color(0xFFE0A8FF)
    val accentDeep = Color(0xFF8A3FD0)
    val ink = Color(0xFFF8F7FF)
}

/** Dark, ChatGPT-style chat palette (the chat is always dark). */
object ChatPalette {
    val canvas = Color(0xFF000000)
    val userBubble = Color(0xFFEBEBEB)
    val userBubbleText = Color(0xFF141414)
    val headerPill = Color(0xFF1C1C1E)
    val composerFill = Color(0xFF1C1C1E)
    val composerControl = Color(0xFF3A3A3C)
    val composerGlyph = Color(0xFFEBEBEB)
    val sendButton = Color(0xFFFFFFFF)
    val sendButtonDisabled = Color(0xFF48484A)
    val sendGlyph = Color(0xFF000000)
    val divider = Color(0xFF2A2A2A)
    val secondaryText = Color(0xFF9A9A9E)
}

private val DarkColors = darkColorScheme(
    primary = Brand.accent, onPrimary = Color.White,
    background = Color(0xFF000000), onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFB9B9C0),
    error = Brand.danger,
)
private val LightColors = lightColorScheme(
    primary = Brand.accent, onPrimary = Color.White,
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F7), onSurfaceVariant = Color(0xFF6C6C70),
    error = Brand.danger,
)

/** Follows the system light/dark like iOS. Onboarding + Chat force their own dark backgrounds. */
@Composable
fun AgenCTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Lifecycle → status tint (port of iOS SessionLifecycle.tint). */
val SessionLifecycle.tint: Color
    get() = when (this) {
        SessionLifecycle.Idle -> Brand.secondary
        SessionLifecycle.Running -> Brand.running
        SessionLifecycle.AwaitingPermission, SessionLifecycle.AwaitingInput -> Brand.attention
        SessionLifecycle.Done -> Brand.success
        is SessionLifecycle.Error -> Brand.danger
    }
