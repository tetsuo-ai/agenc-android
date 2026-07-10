package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.TranscriptItem
import ai.tetsuo.agenc.model.LedgerTransferResult
import ai.tetsuo.agenc.model.LedgerTransferStatus
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.ChatPalette
import kotlinx.coroutines.delay
import java.math.BigDecimal

/** One bounded initial-history animation. Stable keys prevent streaming updates from replaying it. */
internal data class MessageRevealMotion(
    val batchKey: String,
    val delayMillis: Int,
)

/** Renders one user-visible transcript item. Tool cards and dividers are filtered upstream. */
@Composable
internal fun MessageRow(
    item: TranscriptItem,
    streaming: Boolean,
    thinkingLive: Boolean = true,
    reduceMotion: Boolean = false,
    animateEntry: Boolean = false,
    userMessageOrdinal: Int? = null,
    editUserMessageEnabled: Boolean = false,
    onEditUserMessage: (MessageEditDraft) -> Unit = {},
    revealMotion: MessageRevealMotion? = null,
    modifier: Modifier = Modifier,
) {
    // Allocate animators only for the six restored tail rows selected by ChatScreen. Every other
    // row stays a plain modifier, so opening a transcript with thousands of messages remains cheap.
    val rowModifier = if (revealMotion != null && !reduceMotion) {
        val revealProgress = remember(item.id, revealMotion.batchKey) { Animatable(0f) }
        LaunchedEffect(item.id, revealMotion.batchKey) {
            revealProgress.snapTo(0f)
            delay(revealMotion.delayMillis.toLong())
            revealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        modifier.graphicsLayer {
            alpha = revealProgress.value
            translationY = (1f - revealProgress.value) * 6.dp.toPx()
        }
    } else {
        modifier
    }

    when (item) {
        is TranscriptItem.User -> UserBubble(
            text = item.text,
            modifier = rowModifier,
            animateEntry = animateEntry,
            reduceMotion = reduceMotion,
            editEnabled = editUserMessageEnabled && userMessageOrdinal != null,
            onEdit = userMessageOrdinal?.let { ordinal ->
                {
                    onEditUserMessage(
                        MessageEditDraft(
                            messageOrdinal = ordinal,
                            messageId = item.msgId,
                            text = item.text,
                        ),
                    )
                }
            },
        )
        is TranscriptItem.Voice -> VoiceBubble(item.seconds, item.transcript, rowModifier)
        is TranscriptItem.Assistant -> AssistantText(item.text, streaming, reduceMotion, rowModifier)
        is TranscriptItem.Thinking -> ThinkingRow(thinkingLive, reduceMotion, rowModifier)
        is TranscriptItem.LedgerTransfer -> LedgerTransferRow(item.result, rowModifier)
        is TranscriptItem.Tool,
        is TranscriptItem.ToolGroup,
        is TranscriptItem.Divider,
        -> Unit
    }
}

private val LedgerViolet = Color(0xFFB69CFF)
private val LedgerSurface = Color(0xFF171022)
private val LedgerOutline = Color(0xFF4B3671)

internal fun ledgerTransferAmountLabel(lamports: String): String =
    "${BigDecimal(lamports).movePointLeft(9).stripTrailingZeros().toPlainString()} SOL"

internal fun ledgerTransferStatusLabel(result: LedgerTransferResult): String =
    when (result.status) {
        LedgerTransferStatus.Submitted -> "Submitted"
        LedgerTransferStatus.Cancelled -> "Cancelled"
    }

private fun shortLedgerValue(value: String): String =
    if (value.length <= 16) value else "${value.take(7)}…${value.takeLast(7)}"

/** Core-validated Ledger result. `Submitted` is deliberately never presented as confirmed/final. */
@Composable
private fun LedgerTransferRow(result: LedgerTransferResult, modifier: Modifier = Modifier) {
    val status = ledgerTransferStatusLabel(result)
    val amount = ledgerTransferAmountLabel(result.lamports)
    val copyValue = result.signature ?: result.toJson()
    MessageActions(
        text = copyValue,
        copyDescription = if (result.signature == null) "Copy Ledger result" else "Copy signature",
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Ledger transfer $status, $amount"
                }
                .background(LedgerSurface, RoundedCornerShape(18.dp))
                .border(1.dp, LedgerOutline, RoundedCornerShape(18.dp))
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(LedgerViolet.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("@", color = LedgerViolet, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = "LEDGER TRANSFER",
                        color = LedgerViolet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                    )
                    Text(
                        text = status,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(amount, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "to ${shortLedgerValue(result.to)}",
                    color = ChatPalette.secondaryText,
                    fontSize = 12.sp,
                )
            }
            if (result.status == LedgerTransferStatus.Submitted) {
                Text(
                    text = "Submitted to Solana · awaiting confirmation",
                    color = LedgerViolet.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                )
                result.signature?.let { signature ->
                    Text(
                        text = "Signature ${shortLedgerValue(signature)}",
                        color = ChatPalette.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = result.reason.orEmpty().replace('_', ' '),
                    color = ChatPalette.secondaryText,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** A user voice message. Long-press exposes Copy; there is no pretend playback or edit action. */
@Composable
fun VoiceBubble(seconds: Int, transcript: String, modifier: Modifier = Modifier) {
    val bars = listOf(6, 11, 16, 9, 20, 13, 7, 17, 22, 12, 8, 15, 19, 10, 6, 14, 21, 9, 13, 7)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        MessageActions(
            text = transcript,
            copyDescription = "Copy voice transcript",
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    modifier = Modifier
                        .background(ChatPalette.userBubble, RoundedCornerShape(22.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (height in bars) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(height.dp)
                                    .background(
                                        ChatPalette.userBubbleText.copy(alpha = 0.72f),
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                    Text(
                        text = "0:%02d".format(seconds.coerceIn(1, 59)),
                        color = ChatPalette.userBubbleText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (transcript.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = transcript,
                        color = ChatPalette.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** ChatGPT-like user bubble. Copy and real edit controls appear only after a long press. */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    animateEntry: Boolean = false,
    reduceMotion: Boolean = false,
    editEnabled: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val risePx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val visibleState = remember {
        MutableTransitionState(!animateEntry || reduceMotion).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = tween(160, easing = FastOutSlowInEasing),
        ) + slideInVertically(
            animationSpec = tween(210, easing = FastOutSlowInEasing),
            initialOffsetY = { risePx },
        ),
    ) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MessageActions(
                text = text,
                copyDescription = "Copy your message",
                editEnabled = editEnabled,
                onEdit = onEdit,
            ) {
                Text(
                    text = ledgerKeywordText(text),
                    color = ChatPalette.userBubbleText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .background(ChatPalette.userBubble, RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Flat assistant text. Long-press exposes Copy without a persistent action rail. */
@Composable
fun AssistantText(
    text: String,
    streaming: Boolean,
    @Suppress("UNUSED_PARAMETER") reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    MessageActions(
        text = text,
        copyDescription = "Copy assistant message",
        modifier = modifier.fillMaxWidth(),
    ) {
        if (streaming) {
            Text(
                text = "$text ▌",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Copy uses the original source, so Markdown fences and tables survive the paste.
            MarkdownText(text = text)
        }
    }
}

/**
 * Reasoning is transient chrome rather than a transcript label. Completed reasoning renders
 * nothing; while live it reuses the quiet three-dot activity cue.
 */
@Composable
fun ThinkingRow(
    live: Boolean = true,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (live) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TypingIndicator(reduceMotion = reduceMotion)
        }
    }
}

/** Three moving monochrome dots, or a static reduced-motion equivalent. */
@Composable
fun TypingIndicator(
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
    contentDescription: String = "Agent is thinking",
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier
            .padding(start = 2.dp)
            .semantics {
                this.contentDescription = contentDescription
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val scale = if (reduceMotion) {
                0.7f
            } else {
                transition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 550,
                            delayMillis = index * 180,
                            easing = LinearEasing,
                        ),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                ).value
            }
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .alpha(if (reduceMotion) 0.6f else scale)
                    .background(Color.White.copy(alpha = 0.72f), CircleShape),
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
            ThinkingRow(live = true)
            AssistantText(
                "Here is the summary, still streaming as tokens arrive",
                streaming = true,
            )
            AssistantText(
                "## Summary\nThe repo is an **AgenC** client.\n\n- It connects over JSON-RPC\n- It renders a `chat` transcript",
                streaming = false,
            )
            Text("idle", color = LocalContentColor.current, overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
    }
}
