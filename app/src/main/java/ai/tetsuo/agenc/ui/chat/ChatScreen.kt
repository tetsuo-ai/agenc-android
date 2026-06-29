package ai.tetsuo.agenc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.QuestionOption
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.TokenUsage
import ai.tetsuo.agenc.model.ToolCardState
import ai.tetsuo.agenc.model.ToolKind
import ai.tetsuo.agenc.model.TranscriptItem
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette

/**
 * The chat screen — a fully STATELESS port of the iOS `ChatThreadView`. All state and callbacks
 * are parameters; this composable never touches networking, the daemon, or any data layer.
 *
 * Visual contract (mirrors iOS):
 *  - pure-black canvas, black header with back / compose / ⋯
 *  - user messages = light rounded bubbles on the right; assistant = flat text on the left
 *  - tool cards hidden, consecutive thinking blocks collapsed (see [forDisplay])
 *  - while a turn runs only the rainbow "thinking…" row shows (no extra "working…"); a brief
 *    [TypingIndicator] fills the gap before the agent produces visible output
 *  - read-only sessions show a banner instead of the composer
 *  - a pending permission / elicitation surfaces as a blocking bottom sheet
 *
 * @param onApprove (id, scope) where scope is "once" or "session".
 */
@Composable
fun ChatScreen(
    title: String,
    cwd: String,
    items: List<TranscriptItem>,
    lifecycle: SessionLifecycle,
    usage: TokenUsage,
    pendingPermission: PendingPermission?,
    pendingElicitation: PendingElicitation?,
    isReadOnly: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (id: String, scope: String) -> Unit,
    onDeny: (id: String) -> Unit,
    onRespond: (id: String, answer: String) -> Unit,
    onBack: () -> Unit,
    reduceMotion: Boolean = false,
) {
    val display = forDisplay(items)
    val lastId = items.lastOrNull()?.id
    val agentRespondingInTurn = display.any { it is TranscriptItem.Thinking || it is TranscriptItem.Assistant }
    val showTyping = lifecycle == SessionLifecycle.Running && !agentRespondingInTurn

    val listState = rememberLazyListState()
    // Follow the stream: glide to the newest row as items/lifecycle change.
    LaunchedEffect(items.size, lifecycle) {
        val target = display.size + if (showTyping) 1 else 0
        if (target > 0) listState.animateScrollToItem((target - 1).coerceAtLeast(0))
    }

    Box(modifier = Modifier.fillMaxSize().background(ChatPalette.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatHeader(onBack = onBack)
            if (cwd.isNotEmpty()) CwdLine(cwd)
            ChatStatusBar(lifecycle, usage)

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(display, key = { it.id }) { item ->
                    MessageRow(
                        item = item,
                        streaming = isStreaming(item, lifecycle, lastId),
                        thinkingSeconds = null,
                        reduceMotion = reduceMotion,
                    )
                }
                if (showTyping) {
                    item(key = "typing") { TypingIndicator(reduceMotion = reduceMotion) }
                }
            }

            if (lifecycle is SessionLifecycle.Error) {
                ConnectionBanner(text = lifecycle.message)
            }

            // Float-free attached composer / banner. The composer owns its own draft text.
            if (isReadOnly) {
                ReadOnlyBanner(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            } else {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    Composer(
                        isInFlight = lifecycle.isInFlight,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }

        // Blocking request sheets — permission takes precedence over an elicitation.
        if (pendingPermission != null) {
            PermissionSheet(
                permission = pendingPermission,
                onApprove = { scope -> onApprove(pendingPermission.id, scope) },
                onDeny = { onDeny(pendingPermission.id) },
            )
        } else if (pendingElicitation != null) {
            ElicitationSheet(
                elicitation = pendingElicitation,
                onAnswer = { answer -> onRespond(pendingElicitation.id, answer) },
            )
        }
    }
}

/** True when this is the trailing assistant row of a live turn → render as cheap streaming text. */
private fun isStreaming(item: TranscriptItem, lifecycle: SessionLifecycle, lastId: String?): Boolean =
    lifecycle == SessionLifecycle.Running && item is TranscriptItem.Assistant && item.id == lastId

/**
 * What the user actually sees: HIDE tool / toolGroup / divider cards (the thinking row shows the
 * work) and COLLAPSE consecutive thinking blocks into the latest one. Direct port of the iOS
 * `ChatThreadView.forDisplay`.
 */
internal fun forDisplay(items: List<TranscriptItem>): List<TranscriptItem> {
    val out = ArrayList<TranscriptItem>(items.size)
    for (item in items) {
        when (item) {
            is TranscriptItem.Tool, is TranscriptItem.ToolGroup, is TranscriptItem.Divider -> continue
            is TranscriptItem.Thinking -> {
                if (out.lastOrNull() is TranscriptItem.Thinking) out[out.size - 1] = item
                else out += item
            }
            else -> out += item
        }
    }
    return out
}

// MARK: - Chrome

/** Black header: a larger custom back chevron, then new-chat + overflow glyphs on the right. */
@Composable
private fun ChatHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.canvas)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { /* new chat — wired by the host */ }) {
            Icon(Icons.Filled.Edit, contentDescription = "New chat", tint = Color.White)
        }
        IconButton(onClick = { /* overflow menu — wired by the host */ }) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = Color.White)
        }
    }
}

/** The folder this session runs in — a slim, secondary, head-truncated monospace line. */
@Composable
private fun CwdLine(path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = ChatPalette.secondaryText, modifier = Modifier.size(14.dp))
        Text(
            path,
            color = ChatPalette.secondaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A thin status strip: the lifecycle label on the left (only when settled — while a turn runs the
 * rainbow thinking row is the single processing indicator) and the running token count on the right.
 */
@Composable
private fun ChatStatusBar(lifecycle: SessionLifecycle, usage: TokenUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.canvas)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!lifecycle.isActive) {
            Text(lifecycle.label, color = ChatPalette.secondaryText, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (usage.output > 0) {
            Text(
                "${usage.input + usage.output} tok",
                color = ChatPalette.secondaryText,
                fontSize = 11.sp,
            )
        }
    }
}

/** Read-only banner shown instead of the composer for terminal-origin sessions. */
@Composable
private fun ReadOnlyBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "started in your terminal · view only",
            color = ChatPalette.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Error banner above the composer when the session lifecycle is in error. */
@Composable
private fun ConnectionBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.danger.copy(alpha = 0.14f), RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.danger, modifier = Modifier.size(16.dp))
        Text(text, color = Brand.danger, fontSize = 13.sp)
    }
}

// MARK: - Previews

private fun sampleItems(): List<TranscriptItem> = listOf(
    TranscriptItem.User("1", "Summarize this repo and fix the failing test."),
    TranscriptItem.Thinking("2", "Scanning the package tree, then I'll read the reducer."),
    // A tool card + group + divider — these must be filtered out by forDisplay().
    TranscriptItem.Tool(ToolCardState(callId = "c1", toolName = "ls", title = "List dir", kind = ToolKind.bash)),
    TranscriptItem.Thinking("3", "Found the off-by-one in the seq counter."),
    TranscriptItem.Assistant(
        "4",
        "## Result\nFixed the **off-by-one** in `Event.seq`.\n\n- Updated the reducer\n- Added a regression test\n\n```kotlin\nseq = seq + 1\n```",
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 760)
@Composable
private fun ChatScreenIdlePreview() {
    AgenCTheme {
        ChatScreen(
            title = "Fix the test",
            cwd = "/Users/me/projects/agenc-android",
            items = sampleItems(),
            lifecycle = SessionLifecycle.Done,
            usage = TokenUsage(input = 1200, output = 380),
            pendingPermission = null,
            pendingElicitation = null,
            isReadOnly = false,
            onSend = {}, onStop = {}, onApprove = { _, _ -> }, onDeny = {}, onRespond = { _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 760)
@Composable
private fun ChatScreenRunningPreview() {
    AgenCTheme {
        ChatScreen(
            title = "Working",
            cwd = "",
            items = listOf(
                TranscriptItem.User("1", "Build me a landing page."),
                TranscriptItem.Thinking("2", "Sketching the hero section…"),
            ),
            lifecycle = SessionLifecycle.Running,
            usage = TokenUsage(),
            pendingPermission = null,
            pendingElicitation = null,
            isReadOnly = false,
            onSend = {}, onStop = {}, onApprove = { _, _ -> }, onDeny = {}, onRespond = { _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 760)
@Composable
private fun ChatScreenReadOnlyPreview() {
    AgenCTheme {
        ChatScreen(
            title = "Terminal session",
            cwd = "/srv/app",
            items = listOf(TranscriptItem.Assistant("1", "Done. The deploy succeeded.")),
            lifecycle = SessionLifecycle.Done,
            usage = TokenUsage(),
            pendingPermission = null,
            pendingElicitation = null,
            isReadOnly = true,
            onSend = {}, onStop = {}, onApprove = { _, _ -> }, onDeny = {}, onRespond = { _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 760)
@Composable
private fun ChatScreenPermissionPreview() {
    AgenCTheme {
        ChatScreen(
            title = "Needs approval",
            cwd = "",
            items = listOf(TranscriptItem.User("1", "Run the tests.")),
            lifecycle = SessionLifecycle.AwaitingPermission,
            usage = TokenUsage(),
            pendingPermission = PendingPermission(
                id = "req-1",
                toolName = "bash",
                title = "Run a shell command",
                detail = "Run ./gradlew test in the project directory.",
                kind = ToolKind.bash,
                permissions = listOf("read", "exec"),
            ),
            pendingElicitation = null,
            isReadOnly = false,
            onSend = {}, onStop = {}, onApprove = { _, _ -> }, onDeny = {}, onRespond = { _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 760)
@Composable
private fun ChatScreenElicitationPreview() {
    AgenCTheme {
        ChatScreen(
            title = "Question",
            cwd = "",
            items = listOf(TranscriptItem.User("1", "Deploy it.")),
            lifecycle = SessionLifecycle.AwaitingInput,
            usage = TokenUsage(),
            pendingPermission = null,
            pendingElicitation = PendingElicitation(
                id = "elic-1",
                question = "Which environment should I deploy to?",
                options = listOf(QuestionOption("p", "Production"), QuestionOption("s", "Staging")),
                allowsOther = true,
            ),
            isReadOnly = false,
            onSend = {}, onStop = {}, onApprove = { _, _ -> }, onDeny = {}, onRespond = { _, _ -> }, onBack = {},
        )
    }
}
