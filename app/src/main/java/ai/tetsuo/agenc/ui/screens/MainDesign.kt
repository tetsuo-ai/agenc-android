package ai.tetsuo.agenc.ui.screens

import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fixed post-login palette. The main shell deliberately does not follow wallpaper or system tint. */
internal object MainPalette {
    val canvas = Color(0xFF000000)
    val surface = Color(0xFF090909)
    val surfaceRaised = Color(0xFF111111)
    val line = Color(0xFF2A2A28)
    val lineSoft = Color(0xFF1B1B1A)
    val ink = Color(0xFFF4F4F1)
    val muted = Color(0xFFA7A7A1)
    val quiet = Color(0xFF858580)
}

/** Bespoke AgenC glyphs drawn in-app; none come from the Material icon catalog. */
internal enum class AgenCGlyph {
    Home, Sessions, Identity, Compose, Arrow, Back, Terminal, Wallet, Exit, Search,
    Lock, Bluetooth, Agent, Check, Warning,
}

@Composable
internal fun AgenCGlyphIcon(
    glyph: AgenCGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = MainPalette.ink,
) {
    val described = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(described) {
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        fun point(x: Float, y: Float) = origin + Offset(side * x, side * y)
        val line = Stroke(
            width = side * 0.075f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        when (glyph) {
            AgenCGlyph.Home -> {
                drawLine(tint, point(.16f, .40f), point(.16f, .17f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.16f, .17f), point(.40f, .17f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.60f, .17f), point(.84f, .17f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.84f, .17f), point(.84f, .40f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.16f, .60f), point(.16f, .83f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.16f, .83f), point(.40f, .83f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.60f, .83f), point(.84f, .83f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.84f, .60f), point(.84f, .83f), strokeWidth = line.width, cap = line.cap)
                drawCircle(tint, radius = side * .075f, center = point(.50f, .50f))
            }

            AgenCGlyph.Sessions -> {
                listOf(.27f, .50f, .73f).forEach { y ->
                    val command = Path().apply {
                        moveTo(point(.16f, y - .07f).x, point(.16f, y - .07f).y)
                        lineTo(point(.27f, y).x, point(.27f, y).y)
                        lineTo(point(.16f, y + .07f).x, point(.16f, y + .07f).y)
                    }
                    drawPath(command, tint, style = line)
                    drawLine(tint, point(.39f, y), point(.84f, y), strokeWidth = line.width, cap = line.cap)
                }
            }

            AgenCGlyph.Identity -> {
                drawArc(
                    color = tint,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = point(.15f, .15f),
                    size = Size(side * .70f, side * .70f),
                    style = line,
                )
                drawArc(
                    color = tint,
                    startAngle = 25f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = point(.15f, .15f),
                    size = Size(side * .70f, side * .70f),
                    style = line,
                )
                val diamond = Path().apply {
                    moveTo(point(.50f, .35f).x, point(.50f, .35f).y)
                    lineTo(point(.65f, .50f).x, point(.65f, .50f).y)
                    lineTo(point(.50f, .65f).x, point(.50f, .65f).y)
                    lineTo(point(.35f, .50f).x, point(.35f, .50f).y)
                    close()
                }
                drawPath(diamond, tint, style = line)
            }

            AgenCGlyph.Compose -> {
                drawLine(tint, point(.22f, .74f), point(.65f, .31f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.31f, .82f), point(.72f, .41f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.22f, .74f), point(.31f, .82f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.77f, .12f), point(.77f, .32f), strokeWidth = line.width * .8f, cap = line.cap)
                drawLine(tint, point(.67f, .22f), point(.87f, .22f), strokeWidth = line.width * .8f, cap = line.cap)
            }

            AgenCGlyph.Arrow -> {
                drawLine(tint, point(.18f, .50f), point(.80f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.61f, .31f), point(.80f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.61f, .69f), point(.80f, .50f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Back -> {
                drawLine(tint, point(.68f, .18f), point(.34f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.34f, .50f), point(.68f, .82f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Terminal -> {
                drawRoundRect(
                    color = tint,
                    topLeft = point(.12f, .20f),
                    size = Size(side * .76f, side * .60f),
                    cornerRadius = CornerRadius(side * .08f),
                    style = line,
                )
                drawLine(tint, point(.25f, .39f), point(.36f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.25f, .61f), point(.36f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.49f, .62f), point(.70f, .62f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Wallet -> {
                drawRoundRect(
                    color = tint,
                    topLeft = point(.13f, .24f),
                    size = Size(side * .74f, side * .55f),
                    cornerRadius = CornerRadius(side * .10f),
                    style = line,
                )
                drawLine(tint, point(.14f, .39f), point(.72f, .39f), strokeWidth = line.width, cap = line.cap)
                drawCircle(tint, radius = side * .045f, center = point(.70f, .59f))
            }

            AgenCGlyph.Exit -> {
                drawLine(tint, point(.30f, .20f), point(.30f, .80f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.30f, .20f), point(.50f, .20f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.30f, .80f), point(.50f, .80f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.44f, .50f), point(.84f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.69f, .35f), point(.84f, .50f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.69f, .65f), point(.84f, .50f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Search -> {
                drawCircle(
                    color = tint,
                    radius = side * .24f,
                    center = point(.41f, .41f),
                    style = line,
                )
                drawLine(tint, point(.59f, .59f), point(.84f, .84f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Lock -> {
                drawRoundRect(
                    color = tint,
                    topLeft = point(.20f, .43f),
                    size = Size(side * .60f, side * .43f),
                    cornerRadius = CornerRadius(side * .07f),
                    style = line,
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = point(.30f, .14f),
                    size = Size(side * .40f, side * .52f),
                    style = line,
                )
                drawCircle(tint, radius = side * .035f, center = point(.50f, .64f))
            }

            AgenCGlyph.Bluetooth -> {
                drawLine(tint, point(.48f, .13f), point(.48f, .87f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.48f, .13f), point(.73f, .36f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.73f, .36f), point(.27f, .70f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.27f, .30f), point(.73f, .66f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.73f, .66f), point(.48f, .87f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Agent -> {
                val mark = Path().apply {
                    moveTo(point(.50f, .16f).x, point(.50f, .16f).y)
                    lineTo(point(.80f, .50f).x, point(.80f, .50f).y)
                    lineTo(point(.50f, .84f).x, point(.50f, .84f).y)
                    lineTo(point(.20f, .50f).x, point(.20f, .50f).y)
                    close()
                }
                drawPath(mark, tint, style = line)
                drawCircle(tint, radius = side * .055f, center = point(.50f, .50f))
            }

            AgenCGlyph.Check -> {
                drawLine(tint, point(.17f, .52f), point(.41f, .75f), strokeWidth = line.width, cap = line.cap)
                drawLine(tint, point(.41f, .75f), point(.84f, .25f), strokeWidth = line.width, cap = line.cap)
            }

            AgenCGlyph.Warning -> {
                val triangle = Path().apply {
                    moveTo(point(.50f, .13f).x, point(.50f, .13f).y)
                    lineTo(point(.88f, .82f).x, point(.88f, .82f).y)
                    lineTo(point(.12f, .82f).x, point(.12f, .82f).y)
                    close()
                }
                drawPath(triangle, tint, style = line)
                drawLine(tint, point(.50f, .36f), point(.50f, .59f), strokeWidth = line.width, cap = line.cap)
                drawCircle(tint, radius = side * .035f, center = point(.50f, .70f))
            }
        }
    }
}

@Composable
internal fun MainScreenHeader(
    section: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = section.uppercase(Locale.US),
            color = MainPalette.quiet,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.8.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = MainPalette.ink,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

@Composable
internal fun SectionHeading(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index,
            color = MainPalette.quiet,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title.uppercase(Locale.US),
            color = MainPalette.muted,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
internal fun ConnectionIndicator(status: ConnectionStatus) {
    val label = when (status) {
        ConnectionStatus.Connected -> "Linked"
        ConnectionStatus.Connecting -> "Linking"
        ConnectionStatus.Offline -> "Offline"
    }
    Row(
        modifier = Modifier
            .border(1.dp, MainPalette.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(3) { index ->
                val activeBars = when (status) {
                    ConnectionStatus.Connected -> 3
                    ConnectionStatus.Connecting -> 2
                    ConnectionStatus.Offline -> 1
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height((5 + index * 2).dp)
                        .background(if (index < activeBars) MainPalette.ink else MainPalette.line, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.uppercase(Locale.US),
            color = if (status == ConnectionStatus.Offline) MainPalette.muted else MainPalette.ink,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .8.sp,
        )
    }
}

@Composable
internal fun PrimarySessionAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Start a session",
    subtitle: String = "Open a new agent workspace",
) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MainPalette.ink)
            .clickable(role = Role.Button) {
                haptics.perform(HapticCue.Action)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Compose,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MainPalette.canvas,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MainPalette.canvas, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MainPalette.canvas.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
        }
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Arrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MainPalette.canvas,
        )
    }
}

@Composable
internal fun LifecycleTag(lifecycle: SessionLifecycle) {
    val attention = lifecycle is SessionLifecycle.AwaitingPermission || lifecycle is SessionLifecycle.AwaitingInput
    val container = when {
        attention -> MainPalette.ink
        lifecycle is SessionLifecycle.Running -> MainPalette.surfaceRaised
        else -> Color.Transparent
    }
    val content = if (attention) MainPalette.canvas else MainPalette.muted
    Row(
        modifier = Modifier
            .background(container, RoundedCornerShape(999.dp))
            .then(
                if (!attention) Modifier.border(1.dp, MainPalette.line, RoundedCornerShape(999.dp))
                else Modifier,
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lifecycle.isActive) {
            Box(Modifier.size(5.dp).background(MainPalette.ink, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = lifecycle.label.uppercase(Locale.US),
            color = content,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .45.sp,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionLine(
    summary: SessionSummary,
    sequence: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val haptics = rememberAgenCHaptics()
    val openWithHaptic = {
        haptics.perform(HapticCue.Selection)
        onOpen()
    }
    val toggleMuteWithHaptic = {
        haptics.perform(if (muted) HapticCue.ToggleOff else HapticCue.ToggleOn)
        onToggleMute?.invoke()
        Unit
    }
    val actionModifier = if (onToggleMute == null) {
        Modifier.clickable(role = Role.Button, onClick = openWithHaptic)
    } else {
        Modifier
            .combinedClickable(
                onClick = openWithHaptic,
                onLongClickLabel = if (muted) "Unmute session" else "Mute session",
                onLongClick = toggleMuteWithHaptic,
            )
            .semantics {
                role = Role.Button
                onLongClick(if (muted) "Unmute session" else "Mute session") {
                    toggleMuteWithHaptic()
                    true
                }
            }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(actionModifier)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sequence.toString().padStart(2, '0'),
                    color = MainPalette.quiet,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (summary.isTerminalOrigin) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "CLI",
                        color = MainPalette.muted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .8.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                LifecycleTag(summary.lifecycle)
            }

            Text(
                text = sessionDisplayTitle(summary),
                color = MainPalette.ink,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val subtitle = sessionDisplaySubtitle(summary)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MainPalette.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val workspace = compactWorkspace(summary.cwd)
                if (workspace.isNotBlank()) {
                    Text(
                        text = workspace,
                        color = MainPalette.quiet,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (onToggleMute != null) {
                    Text(
                        text = if (muted) "MUTED" else "MUTE",
                        color = if (muted) MainPalette.ink else MainPalette.quiet,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .7.sp,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = if (muted) "Unmute session" else "Mute session") {
                                toggleMuteWithHaptic()
                            }
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sessionTimeLabel(summary.updated),
                    color = MainPalette.quiet,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 1.dp,
                color = MainPalette.lineSoft,
            )
        }
    }
}

internal fun sessionDisplayTitle(summary: SessionSummary): String {
    val raw = summary.title.trim()
    val workspaceName = summary.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "Session" }
    return when {
        raw.isBlank() -> workspaceName
        raw.startsWith("--") -> workspaceName
        else -> raw
    }
}

internal fun sessionDisplaySubtitle(summary: SessionSummary): String {
    val raw = summary.subtitle.trim()
    val title = summary.title.trim()
    return when {
        summary.isTerminalOrigin &&
            raw.isNotBlank() &&
            !raw.equals(title, ignoreCase = true) &&
            raw != summary.cwd.trim() &&
            !raw.startsWith("--") -> "$raw · view only"
        summary.isTerminalOrigin -> "Terminal session · view only"
        title.startsWith("--") || raw.startsWith("--") -> "Terminal session"
        raw.equals(title, ignoreCase = true) -> ""
        raw == summary.cwd.trim() -> ""
        else -> raw
    }
}

internal fun compactWorkspace(raw: String): String {
    val path = raw.trim()
    if (path.isBlank()) return ""
    return path.replace(Regex("^/Users/[^/]+/"), "~/")
}

internal fun sessionTimeLabel(raw: String, now: Instant = Instant.now()): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val timestamp = runCatching { Instant.parse(trimmed) }.getOrNull() ?: return trimmed
    val elapsed = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
    return when {
        elapsed < 60 -> "now"
        elapsed < 3_600 -> "${elapsed / 60}m"
        elapsed < 86_400 -> "${elapsed / 3_600}h"
        elapsed < 604_800 -> "${elapsed / 86_400}d"
        else -> DateTimeFormatter.ofPattern("MMM d", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(timestamp)
    }
}
