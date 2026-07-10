package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.ChatPalette
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/** Connection truth shown by the chat composer instead of inferring it from a paired account. */
enum class ChatConnectionState { Connected, Connecting, Offline }

private enum class CommandAction(val label: String) {
    Send("SEND"),
    Stop("STOP"),
    Voice("VOICE"),
    Wait("WAIT"),
}

/**
 * AgenC's attached command deck. Its geometry never changes when focus moves, so opening the
 * keyboard or typing cannot make the control jump. Connection state is explicit: the action is
 * disabled until the daemon handshake has completed, avoiding a local "working" state for a
 * message that never reached Core.
 */
@Composable
internal fun Composer(
    isInFlight: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: ChatConnectionState = ChatConnectionState.Connected,
    editDraft: MessageEditDraft? = null,
    onSubmitEdit: (MessageEditDraft, String) -> Unit = { _, _ -> },
    onCancelEdit: () -> Unit = {},
    // Voice recording (demo): the empty-field VOICE action starts/stops a recording.
    isRecording: Boolean = false,
    recordSeconds: Int = 0,
    voiceEnabled: Boolean = false,
    onMic: () -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    if (isRecording) {
        RecordingDeck(recordSeconds, onStopRecording, modifier)
        return
    }

    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var activeEditId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftBeforeEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(editDraft?.messageId) {
        val request = editDraft
        if (request == null) {
            if (activeEditId != null) {
                val restored = draftBeforeEdit.orEmpty()
                text = TextFieldValue(restored, TextRange(restored.length))
                activeEditId = null
                draftBeforeEdit = null
            }
            return@LaunchedEffect
        }
        if (request.messageId != activeEditId) {
            if (activeEditId == null) draftBeforeEdit = text.text
            activeEditId = request.messageId
            text = TextFieldValue(
                text = request.text,
                selection = TextRange(request.text.length),
            )
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val canSend = text.text.trim().isNotEmpty()
    val connected = connectionState == ChatConnectionState.Connected
    val action = when {
        !connected -> CommandAction.Wait
        isInFlight -> CommandAction.Stop
        canSend -> CommandAction.Send
        voiceEnabled -> CommandAction.Voice
        else -> CommandAction.Send
    }
    val actionEnabled = connected && (isInFlight || canSend || voiceEnabled)
    val status = when {
        connectionState == ChatConnectionState.Connecting -> "CONNECTING"
        connectionState == ChatConnectionState.Offline -> "CORE OFFLINE"
        isInFlight -> "RUNNING"
        editDraft != null -> "EDITING"
        else -> "READY"
    }
    val statusDotColor = if (connected) Color.White else Color(0xFF555555)

    CommandDeck(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0E0E0E), RoundedCornerShape(14.dp)),
        ) {
            if (editDraft != null) {
                EditingBanner(
                    onCancel = {
                        val restored = draftBeforeEdit.orEmpty()
                        text = TextFieldValue(restored, TextRange(restored.length))
                        activeEditId = null
                        draftBeforeEdit = null
                        onCancelEdit()
                    },
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .heightIn(min = 52.dp, max = 124.dp)
                    .semantics {
                        contentDescription = if (editDraft == null) {
                            "Message input"
                        } else {
                            "Edit prompt input"
                        }
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                visualTransformation = LedgerKeywordVisualTransformation,
                cursorBrush = SolidColor(Color.White),
                interactionSource = remember { MutableInteractionSource() },
                minLines = 1,
                maxLines = 5,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (text.text.isEmpty()) {
                            Text(
                                if (connected) "Message the agent" else "Waiting for Core…",
                                color = ChatPalette.secondaryText,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF242424)),
            )
            ComposerFooter(
                status = status,
                dotColor = statusDotColor,
                action = action,
                actionEnabled = actionEnabled,
                onAction = {
                    when (action) {
                        CommandAction.Send -> {
                            val message = text.text.trim()
                            if (message.isNotEmpty()) {
                                val editing = editDraft
                                text = TextFieldValue()
                                activeEditId = null
                                draftBeforeEdit = null
                                if (editing == null) {
                                    onSend(message)
                                } else {
                                    onSubmitEdit(editing, message)
                                }
                            }
                        }
                        CommandAction.Stop -> onStop()
                        CommandAction.Voice -> onMic()
                        CommandAction.Wait -> Unit
                    }
                },
            )
        }
    }
}

/** Explicit rewind state: the user can always cancel and sees that later replies will be replaced. */
@Composable
private fun EditingBanner(onCancel: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Color(0xFF151515))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "EDITING PROMPT",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = "Later replies will be replaced",
                color = ChatPalette.secondaryText,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .height(44.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Cancel editing"
                }
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Cancel editing",
                    onClick = {
                        haptics.perform(HapticCue.Reject)
                        onCancel()
                    },
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CANCEL",
                color = ChatPalette.secondaryText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF242424)),
    )
}

/** Recording uses the same stable deck and bespoke action vocabulary as text input. */
@Composable
private fun RecordingDeck(seconds: Int, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "recording")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "recordingDot",
    )
    CommandDeck(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0E0E0E), RoundedCornerShape(14.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .alpha(dotAlpha)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
                Text(
                    "0:%02d".format(seconds.coerceAtMost(59)),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF242424)),
            )
            ComposerFooter(
                status = "RECORDING",
                dotColor = Color.White,
                action = CommandAction.Send,
                actionEnabled = true,
                onAction = onSend,
            )
        }
    }
}

@Composable
private fun CommandDeck(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
private fun ComposerFooter(
    status: String,
    dotColor: Color,
    action: CommandAction,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(dotColor, RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            status,
            color = ChatPalette.secondaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
        CommandButton(
            action = action,
            enabled = actionEnabled,
            onClick = onAction,
        )
    }
}

@Composable
private fun CommandButton(
    action: CommandAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed && enabled) Color(0xFF191919) else Color.Transparent,
        animationSpec = tween(90),
        label = "commandFill",
    )
    val foreground by animateColorAsState(
        targetValue = if (enabled) Color.White else Color(0xFF555555),
        animationSpec = tween(120),
        label = "commandForeground",
    )
    val description = when (action) {
        CommandAction.Send -> if (enabled) "Send message" else "Type a message to send"
        CommandAction.Stop -> "Stop the running turn"
        CommandAction.Voice -> "Record voice message"
        CommandAction.Wait -> "Waiting for Core connection"
    }
    val state = when {
        action == CommandAction.Wait -> "Core is not connected"
        action == CommandAction.Stop -> "Agent is running"
        enabled -> "Available"
        else -> "Disabled"
    }

    Row(
        modifier = Modifier
            .width(88.dp)
            .height(48.dp)
            .background(fill, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                stateDescription = state
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = {
                    haptics.perform(
                        when (action) {
                            CommandAction.Send -> HapticCue.Confirm
                            CommandAction.Stop -> HapticCue.Reject
                            CommandAction.Voice -> HapticCue.Action
                            CommandAction.Wait -> HapticCue.Action
                        },
                    )
                    onClick()
                },
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            action.label,
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
        Spacer(Modifier.width(10.dp))
        CommandGlyph(action = action, color = foreground)
    }
}

/** Small custom-drawn glyph set; none of the command actions use generic Material icons. */
@Composable
private fun CommandGlyph(action: CommandAction, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        val midY = size.height / 2f
        when (action) {
            CommandAction.Send -> {
                val left = size.width * 0.12f
                val right = size.width * 0.88f
                drawLine(color, Offset(left, midY), Offset(right, midY), stroke, StrokeCap.Square)
                drawLine(
                    color,
                    Offset(size.width * 0.58f, size.height * 0.22f),
                    Offset(right, midY),
                    stroke,
                    StrokeCap.Square,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.58f, size.height * 0.78f),
                    Offset(right, midY),
                    stroke,
                    StrokeCap.Square,
                )
            }
            CommandAction.Stop -> {
                val side = size.minDimension * 0.48f
                drawRect(
                    color = color,
                    topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                    size = androidx.compose.ui.geometry.Size(side, side),
                )
            }
            CommandAction.Voice -> {
                val capsuleWidth = size.width * 0.34f
                val capsuleHeight = size.height * 0.58f
                drawRoundRect(
                    color = color,
                    topLeft = Offset((size.width - capsuleWidth) / 2f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2f),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.30f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.46f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawLine(
                    color,
                    Offset(size.width / 2f, size.height * 0.76f),
                    Offset(size.width / 2f, size.height * 0.92f),
                    stroke,
                )
            }
            CommandAction.Wait -> {
                val radius = 1.35.dp.toPx()
                for (x in listOf(0.24f, 0.5f, 0.76f)) {
                    drawCircle(color, radius, Offset(size.width * x, midY))
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposerIdlePreview() {
    AgenCTheme { Composer(isInFlight = false, onSend = {}, onStop = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposerInFlightPreview() {
    AgenCTheme { Composer(isInFlight = true, onSend = {}, onStop = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ComposerOfflinePreview() {
    AgenCTheme {
        Composer(
            isInFlight = false,
            onSend = {},
            onStop = {},
            connectionState = ChatConnectionState.Offline,
        )
    }
}
