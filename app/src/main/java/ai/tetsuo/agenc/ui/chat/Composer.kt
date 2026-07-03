package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette

/**
 * The floating composer. COLLAPSED by default (a compact "Ask anything" pill pulled in from the
 * edges); focusing the field or typing expands it to full width. A circular control on the right
 * is the send arrow normally and becomes a ■ stop while a turn is in flight ([isInFlight]).
 *
 * Stateless w.r.t. the conversation — it owns only its own draft text, which it clears and hands
 * to [onSend]. Networking/data is entirely the caller's concern.
 */
@Composable
fun Composer(
    isInFlight: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    // Voice recording (demo): the empty-field mic starts/stops a recording; while recording the
    // composer becomes a live capture bar. Owned by the host so it can drive the scripted flow.
    isRecording: Boolean = false,
    recordSeconds: Int = 0,
    onMic: () -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    if (isRecording) {
        RecordingBar(recordSeconds, onStopRecording, modifier)
        return
    }
    var text by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val expanded = focused || text.isNotEmpty()
    val canSend = text.trim().isNotEmpty()

    val sidePadding by animateDpAsState(
        targetValue = if (expanded) 0.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "composerSide",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The spring is underdamped (dampingRatio < 1) so it overshoots; collapsing 16.dp→0.dp
                // it briefly goes negative, and Compose's padding() throws on negative values. Clamp it.
                .padding(horizontal = sidePadding.coerceAtLeast(0.dp))
                .background(ChatPalette.composerFill, RoundedCornerShape(26.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp, vertical = if (expanded) 6.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (expanded) 34.dp else 42.dp)
                    .padding(start = if (expanded) 10.dp else 12.dp, end = 4.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Brand.accent),
                interactionSource = interaction,
                maxLines = 6,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text("Ask anything", color = ChatPalette.secondaryText, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )

            if (isInFlight) {
                CircleControl(
                    background = ChatPalette.sendButton,
                    onClick = onStop,
                    contentDescription = "Stop the running turn",
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        tint = ChatPalette.sendGlyph,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                // Empty field → an ENABLED mic (starts a recording); text present → the send arrow.
                CircleControl(
                    background = ChatPalette.sendButton,
                    onClick = {
                        if (canSend) {
                            val t = text.trim()
                            if (t.isNotEmpty()) { text = ""; onSend(t) }
                        } else {
                            onMic()
                        }
                    },
                    enabled = true,
                    contentDescription = if (canSend) "Send message" else "Record voice message",
                ) {
                    Icon(
                        if (canSend) Icons.Filled.ArrowUpward else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = ChatPalette.sendGlyph,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** The composer while recording: a pulsing red dot, a live timer, and a send/stop circle. */
@Composable
private fun RecordingBar(seconds: Int, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(650),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChatPalette.composerFill, RoundedCornerShape(26.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(26.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(dotAlpha)
                    .background(Color(0xFFE5484D), CircleShape),
            )
            Text("Recording", color = Color.White, fontSize = 15.sp)
            Text(
                "0:%02d".format(seconds.coerceAtMost(59)),
                color = ChatPalette.secondaryText, fontSize = 15.sp,
            )
            Box(Modifier.weight(1f))
            CircleControl(
                background = ChatPalette.sendButton,
                onClick = onStop,
                contentDescription = "Send voice message",
            ) {
                Icon(
                    Icons.Filled.ArrowUpward, contentDescription = null,
                    tint = ChatPalette.sendGlyph, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CircleControl(
    background: Color,
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(background, CircleShape)
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposerIdlePreview() {
    AgenCTheme {
        Composer(isInFlight = false, onSend = {}, onStop = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposerInFlightPreview() {
    AgenCTheme {
        Composer(isInFlight = true, onSend = {}, onStop = {})
    }
}
