package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.TranscriptItem
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette

/**
 * Renders one display [TranscriptItem]. Mirrors the iOS `TranscriptRow` switch — but only the
 * user-visible cases reach here: tool / toolGroup / divider are filtered out upstream by
 * [forDisplay] (the thinking row shows the work, command cards are hidden).
 *
 * Stateless: [streaming] and [thinkingSeconds] are computed by the caller from lifecycle.
 */
@Composable
fun MessageRow(
    item: TranscriptItem,
    streaming: Boolean,
    thinkingSeconds: Int?,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item.text, modifier)
        is TranscriptItem.Assistant -> AssistantText(item.text, streaming, reduceMotion, modifier)
        is TranscriptItem.Thinking -> ThinkingRow(item.text, thinkingSeconds, reduceMotion, modifier)
        // Hidden categories — never reach here once items pass through forDisplay(), but render
        // nothing defensively so the composable stays total.
        is TranscriptItem.Tool,
        is TranscriptItem.ToolGroup,
        is TranscriptItem.Divider,
        -> Unit
    }
}

/**
 * A user message: a light, near-white rounded bubble with dark text, right-aligned, capped at
 * ~78% of the screen width. No accent fill — a quiet chip, exactly like ChatGPT.
 */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            color = ChatPalette.userBubbleText,
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(ChatPalette.userBubble, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * An assistant message: flat, full-width, no bubble/avatar.
 *
 * CRITICAL fidelity: while [streaming], render cheap PLAIN [Text] + a caret. Re-running the full
 * markdown parser on every token is O(n²) and hangs the UI on big outputs. It formats to full
 * markdown the instant the turn completes.
 */
@Composable
fun AssistantText(
    text: String,
    streaming: Boolean,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (streaming) {
        Text(
            text = "$text ▌",
            color = Color.White,
            fontSize = 15.sp,
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        MarkdownText(text = text, modifier = modifier)
    }
}

/**
 * The reasoning row. A single lowercase line that, WHILE reasoning ([seconds] == null) and motion
 * is allowed, fills its glyphs with a rainbow gradient and slides a continuous hue sweep through
 * them so the colours shimmer in place — one smooth cycle, no blink. Once done it settles to a
 * plain gray "thought for Ns". Tapping reveals the raw reasoning text. Respects reduce-motion.
 */
@Composable
fun ThinkingRow(
    text: String,
    seconds: Int?,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val animating = seconds == null && !reduceMotion
    val headline = if (seconds != null) "thought for ${seconds}s" else "thinking…"

    // Continuous 0→1 phase that drives the rainbow gradient's horizontal offset.
    val phase = if (animating) {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "hue",
        ).value
    } else {
        0f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (animating) {
                // A wide rainbow brush slid by `phase` so the colours travel through the glyphs.
                val rainbow = remember(phase) {
                    val shift = phase * 600f
                    Brush.linearGradient(
                        colors = RAINBOW,
                        start = androidx.compose.ui.geometry.Offset(-300f + shift, 0f),
                        end = androidx.compose.ui.geometry.Offset(300f + shift, 0f),
                    )
                }
                Text(
                    text = headline,
                    fontSize = 14.sp,
                    style = androidx.compose.ui.text.TextStyle(brush = rainbow),
                )
            } else {
                Text(headline, color = ChatPalette.secondaryText, fontSize = 14.sp)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ChatPalette.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = text,
                color = ChatPalette.secondaryText.copy(alpha = 0.75f),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private val RAINBOW = listOf(
    Color(0xFF8B5CF6), // purple
    Color(0xFF3B82F6), // blue
    Color(0xFF06B6D4), // cyan
    Color(0xFF22C55E), // green
    Color(0xFFEAB308), // yellow
    Color(0xFFF97316), // orange
    Color(0xFFEC4899), // pink
    Color(0xFF8B5CF6), // purple (wrap)
)

/**
 * The three-dot "typing…" indicator shown for the brief gap before the agent produces visible
 * output. Once the thinking row or streaming text appears, the caller stops rendering this.
 */
@Composable
fun TypingIndicator(reduceMotion: Boolean = false, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier.padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val scale = if (reduceMotion) {
                0.7f
            } else {
                transition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 550, delayMillis = i * 180, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                ).value
            }
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .alpha(if (reduceMotion) 0.6f else scale)
                    .background(Brand.accent.copy(alpha = 0.7f), CircleShape),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun MessageRowsPreview() {
    AgenCTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            UserBubble("Can you summarize what this repo does and find the bug?")
            ThinkingRow("Let me scan the package tree and read the entry point…", seconds = null)
            ThinkingRow("Examined the reducer and found the off-by-one.", seconds = 7)
            AssistantText(
                "Here is the summary, still streaming as tokens arrive",
                streaming = true,
            )
            AssistantText(
                "## Summary\nThe repo is an **AgenC** client.\n\n- It connects over JSON-RPC\n- It renders a `chat` transcript",
                streaming = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypingIndicator()
            }
            // Reference LocalContentColor + TextOverflow so the imports stay meaningful in isolation.
            Text("idle", color = LocalContentColor.current, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
    }
}
