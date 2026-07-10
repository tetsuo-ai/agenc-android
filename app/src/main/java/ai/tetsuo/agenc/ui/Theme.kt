package ai.tetsuo.agenc.ui

import ai.tetsuo.agenc.model.SessionLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AgenC's shared visual language. Every surface now uses the same always-dark monochrome canvas;
 * hierarchy comes from typography, tone and line rather than wallpaper tint or generic color.
 */
object Brand {
    // A middle neutral remains legible on both the light shell and the always-dark chat surface.
    val accent = Color(0xFF757575)
    val accentSoft = Color(0x1FFFFFFF)

    // Semantic colors target a similar luminance so status text remains readable in both modes.
    val running = Color(0xFF2F6FE4)
    val attention = Color(0xFFAD6200)
    val success = Color(0xFF20864D)
    val danger = Color(0xFFC94B4B)
    val secondary = Color(0xFF757575)
}

/** Onboarding (login/pairing) palette — intentionally dark and independent of system appearance. */
object Onboarding {
    val bg = Color(0xFF080808)
    val bgDeep = Color(0xFF000000)
    val accent = Color(0xFFF4F4F1)
    val accentLight = Color(0xFFF4F4F1)
    val accentDeep = Color(0xFF3A3A38)
    val ink = Color(0xFFF7F7F4)
}

/** Dark monochrome chat palette (the chat is always dark). */
object ChatPalette {
    val canvas = Color(0xFF000000)
    val userBubble = Color(0xFFF1F1EE)
    val userBubbleText = Color(0xFF111110)
    val headerPill = Color(0xFF161615)
    val composerFill = Color(0xFF181817)
    val composerControl = Color(0xFF333331)
    val composerGlyph = Color(0xFFF2F2EF)
    val sendButton = Color(0xFFF5F5F2)
    val sendButtonDisabled = Color(0xFF454541)
    val sendGlyph = Color(0xFF000000)
    val divider = Color(0xFF2A2A28)
    val secondaryText = Color(0xFFA7A7A1)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F1),
    onPrimary = Color(0xFF121211),
    primaryContainer = Color(0xFF2B2B29),
    onPrimaryContainer = Color(0xFFF8F8F5),
    inversePrimary = Color(0xFF1A1A18),
    secondary = Color(0xFFD0D0CB),
    onSecondary = Color(0xFF1A1A18),
    secondaryContainer = Color(0xFF383835),
    onSecondaryContainer = Color(0xFFF1F1EE),
    tertiary = Color(0xFFB7B7B1),
    onTertiary = Color(0xFF171716),
    tertiaryContainer = Color(0xFF30302D),
    onTertiaryContainer = Color(0xFFEAEAE6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F2),
    surface = Color(0xFF090909),
    onSurface = Color(0xFFF5F5F2),
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Color(0xFFBEBEB8),
    surfaceTint = Color(0xFFF4F4F1),
    inverseSurface = Color(0xFFEFEFEC),
    inverseOnSurface = Color(0xFF1B1B19),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF6F6F6A),
    outlineVariant = Color(0xFF2A2A28),
    scrim = Color.Black,
    surfaceBright = Color(0xFF30302E),
    surfaceDim = Color(0xFF000000),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1C1C1B),
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainerLowest = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111110),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E4),
    onPrimaryContainer = Color(0xFF111110),
    inversePrimary = Color(0xFFF4F4F1),
    secondary = Color(0xFF3F3F3C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDEA),
    onSecondaryContainer = Color(0xFF1D1D1B),
    tertiary = Color(0xFF5B5B57),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E5E1),
    onTertiaryContainer = Color(0xFF1C1C1A),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF10100F),
    surface = Color.White,
    onSurface = Color(0xFF10100F),
    surfaceVariant = Color(0xFFE9E9E5),
    onSurfaceVariant = Color(0xFF4D4D49),
    surfaceTint = Color(0xFF111110),
    inverseSurface = Color(0xFF1C1C1A),
    inverseOnSurface = Color(0xFFF4F4F1),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74746F),
    outlineVariant = Color(0xFFC8C8C2),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDDDD8),
    surfaceContainer = Color(0xFFEFEFEB),
    surfaceContainerHigh = Color(0xFFE9E9E5),
    surfaceContainerHighest = Color(0xFFE2E2DD),
    surfaceContainerLow = Color(0xFFF4F4F1),
    surfaceContainerLowest = Color.White,
)

private val AgenCTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25f).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.2f).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

private val AgenCShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

/** AgenC is deliberately monochrome and always dark across onboarding, shell and chat. */
@Composable
fun AgenCTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AgenCTypography,
        shapes = AgenCShapes,
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
