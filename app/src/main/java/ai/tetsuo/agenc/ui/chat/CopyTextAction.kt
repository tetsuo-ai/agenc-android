package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ai.tetsuo.agenc.ui.ChatPalette
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import kotlinx.coroutines.delay

/** Compact clipboard glyph for fenced code. Whole-message actions live behind a long press. */
@Composable
internal fun CopyTextAction(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberAgenCHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }

    val surface by animateColorAsState(
        targetValue = if (pressed) Color(0xFF191919) else Color.Transparent,
        animationSpec = tween(90),
        label = "copySurface",
    )
    val foreground by animateColorAsState(
        targetValue = if (copied) Color.White else ChatPalette.secondaryText,
        animationSpec = tween(120),
        label = "copyForeground",
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .background(surface, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
                stateDescription = if (copied) "Copied" else "Available"
                liveRegion = LiveRegionMode.Polite
            }
            .clickable(
                enabled = text.isNotEmpty(),
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                    haptics.perform(HapticCue.Confirm)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val stroke = 1.5.dp.toPx()
            if (copied) {
                drawLine(
                    color = foreground,
                    start = Offset(size.width * 0.12f, size.height * 0.54f),
                    end = Offset(size.width * 0.40f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = foreground,
                    start = Offset(size.width * 0.40f, size.height * 0.82f),
                    end = Offset(size.width * 0.90f, size.height * 0.20f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                drawRect(
                    color = foreground,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.30f),
                    size = Size(size.width * 0.60f, size.height * 0.62f),
                    style = Stroke(width = stroke),
                )
                drawRect(
                    color = foreground,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.08f),
                    size = Size(size.width * 0.60f, size.height * 0.62f),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}
