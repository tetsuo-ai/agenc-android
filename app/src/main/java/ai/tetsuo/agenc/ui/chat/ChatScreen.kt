package ai.tetsuo.agenc.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.PermissionDecisionState
import ai.tetsuo.agenc.model.QuestionOption
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.TokenUsage
import ai.tetsuo.agenc.model.ToolCardState
import ai.tetsuo.agenc.model.ToolKind
import ai.tetsuo.agenc.model.TranscriptItem
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The chat screen — a fully STATELESS port of the iOS `ChatThreadView`. All state and callbacks
 * are parameters; this composable never touches networking, the daemon, or any data layer.
 *
 * Visual contract (mirrors iOS):
 *  - pure-black canvas, black header with back / compose / ⋯
 *  - user messages = light rounded bubbles on the right; assistant = flat text on the left
 *  - tool cards hidden, consecutive thinking blocks collapsed (see [forDisplay])
 *  - while a turn runs only animated monochrome dots show; completed reasoning leaves no label
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
    permissionDecisionState: PermissionDecisionState = PermissionDecisionState.Idle,
    pendingElicitation: PendingElicitation?,
    isReadOnly: Boolean,
    connectionState: ChatConnectionState = ChatConnectionState.Connected,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (id: String, scope: String) -> Unit,
    onDeny: (id: String) -> Unit,
    onRespond: (id: String, answer: String) -> Unit,
    onBack: () -> Unit,
    sessionId: String = "",
    historyReady: Boolean = true,
    historyLoadFailed: Boolean = false,
    reduceMotion: Boolean = false,
    /** Retained for store compatibility; durations are intentionally not rendered. */
    thinkingDurations: Map<String, Int> = emptyMap(),
    // Voice recording (demo).
    isRecording: Boolean = false,
    recordSeconds: Int = 0,
    voiceEnabled: Boolean = false,
    onMic: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    /** Rewind target is zero-based among selectable user prompts. */
    onEditSend: (messageOrdinal: Int, msgId: String, revisedText: String) -> Unit = { _, _, _ -> },
) {
    val revealSessionKey = sessionId.ifBlank { "$title|$cwd" }
    val unfilteredDisplay = forDisplay(items)
    // Reasoning is live chrome only. Keep just the current trailing reasoning item, and remove it
    // as soon as assistant text starts so completed turns never retain a stale thinking label/gap.
    val liveThinkingIndex = unfilteredDisplay.indexOfLast { it is TranscriptItem.Thinking }
        .takeIf { index ->
            lifecycle.isInFlight && index >= 0 &&
                unfilteredDisplay.drop(index + 1).none {
                    it is TranscriptItem.User ||
                        it is TranscriptItem.Voice ||
                        it is TranscriptItem.Assistant ||
                        it is TranscriptItem.LedgerTransfer
                }
        }
    val display = unfilteredDisplay.filterIndexed { index, item ->
        item !is TranscriptItem.Thinking || index == liveThinkingIndex
    }
    val userMessageOrdinals = remember(items) {
        buildMap<String, Int> {
            var ordinal = 0
            // Core ordinals restart after the latest compaction boundary. Older prompts remain
            // visible locally but are intentionally not editable because they are no longer in
            // Core's selectable active history.
            val activeStart = items.indexOfLast { it is TranscriptItem.Divider } + 1
            items.drop(activeStart).forEach { item ->
                if (item is TranscriptItem.User) {
                    put(item.msgId, ordinal)
                    ordinal += 1
                }
            }
        }
    }
    var editDraft by remember(revealSessionKey) { mutableStateOf<MessageEditDraft?>(null) }
    val userEditEnabled = !lifecycle.isInFlight && !isReadOnly && !isRecording
    LaunchedEffect(lifecycle, isReadOnly, isRecording) {
        if (!userEditEnabled) editDraft = null
    }
    val lastId = items.lastOrNull()?.id
    val lastUserIndex = display.indexOfLast {
        it is TranscriptItem.User || it is TranscriptItem.Voice
    }
    val latestInputId = display.getOrNull(lastUserIndex)?.id
    val earlierDisplay = if (lastUserIndex >= 0) display.take(lastUserIndex) else display
    val latestTurn = if (lastUserIndex >= 0) display.drop(lastUserIndex) else emptyList()
    val agentRespondingInTurn = display
        .drop(lastUserIndex + 1)
        .any { it is TranscriptItem.Thinking || it is TranscriptItem.Assistant }
    val showTyping = lifecycle == SessionLifecycle.Running && !agentRespondingInTurn

    // The restored transcript is positioned while invisible, then revealed as one layer. A small
    // motion budget is reserved for the visible tail; live token updates keep stable ids and never
    // replay this entrance.
    var initialTranscriptRevealed by remember(revealSessionKey) { mutableStateOf(false) }
    var historyMotionWindowOpen by remember(revealSessionKey) { mutableStateOf(false) }
    var historyMotionOrder by remember(revealSessionKey) {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }
    val transcriptReveal = remember(revealSessionKey) { Animatable(0f) }
    var mountedLatestInputId by remember(revealSessionKey) { mutableStateOf<String?>(null) }
    val enteringInputId = latestInputId.takeIf {
        initialTranscriptRevealed && it != mountedLatestInputId
    }
    SideEffect {
        if (initialTranscriptRevealed) mountedLatestInputId = latestInputId
    }

    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var transcriptViewportHeightPx by remember { mutableStateOf(0) }
    var latestTurnHeightPx by remember(latestInputId) { mutableStateOf(0) }
    val density = LocalDensity.current
    val transcriptRisePx = with(density) { 4.dp.toPx() }
    // With 18dp content padding, the 20dp gap before the 1dp bottom anchor, and another 18dp
    // bottom inset, this runway makes a short latest turn occupy exactly one viewport. The prompt
    // therefore sits near the top while its answer grows below it; once the answer is taller than
    // the viewport the runway naturally reaches zero and normal tail-following takes over.
    val runwayReservePx = with(density) { 57.dp.roundToPx() }
    val responseRunway = with(density) {
        (transcriptViewportHeightPx - latestTurnHeightPx - runwayReservePx)
            .coerceAtLeast(0)
            .toDp()
    }
    // A real item after all transcript content gives us an exact target even when the last agent
    // response is taller than the viewport. Scrolling to the row itself only reveals its beginning.
    val bottomAnchorIndex = earlierDisplay.size +
        (if (latestTurn.isNotEmpty()) 1 else 0) +
        (if (latestTurn.isEmpty() && showTyping) 1 else 0)
    val nearBottomThreshold = with(density) { 64.dp.roundToPx() }
    val isNearBottom by remember(listState, bottomAnchorIndex, nearBottomThreshold) {
        derivedStateOf {
            if (bottomAnchorIndex == 0 || listState.layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastContentIndex = bottomAnchorIndex - 1
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == lastContentIndex }
                    ?.let { it.offset + it.size <= listState.layoutInfo.viewportEndOffset + nearBottomThreshold }
                    ?: false
            }
        }
    }
    var followTail by remember(listState) { mutableStateOf(true) }
    var jumpAnimating by remember(listState) { mutableStateOf(false) }
    var positioningNewTurn by remember(listState) { mutableStateOf(false) }
    var positionedInputId by remember(listState, revealSessionKey) { mutableStateOf<String?>(null) }
    val showJumpToLatest by remember(
        listState,
        followTail,
        isNearBottom,
        initialTranscriptRevealed,
    ) {
        derivedStateOf {
            initialTranscriptRevealed && !followTail && !isNearBottom && listState.canScrollForward
        }
    }

    // Never let a long history visibly render at the top and then jump to the bottom. The list is
    // laid out and positioned behind alpha=0; only then do we reveal it and softly raise its last
    // few conversational rows. Older rows remain static, keeping the work O(1) for huge sessions.
    LaunchedEffect(revealSessionKey, historyReady) {
        if (!historyReady) {
            transcriptReveal.snapTo(0f)
            initialTranscriptRevealed = false
            historyMotionWindowOpen = false
            historyMotionOrder = emptyMap()
            return@LaunchedEffect
        }
        if (initialTranscriptRevealed) return@LaunchedEffect

        val visibleTail = display.filter {
            it is TranscriptItem.User ||
                it is TranscriptItem.Voice ||
                it is TranscriptItem.Assistant ||
                it is TranscriptItem.LedgerTransfer
        }.takeLast(6)
        historyMotionOrder = visibleTail
            .mapIndexed { index, item -> item.id to index * 18 }
            .toMap()
        mountedLatestInputId = latestInputId
        positionedInputId = latestInputId
        followTail = true
        transcriptReveal.snapTo(0f)
        listState.scrollToItem(bottomAnchorIndex)
        withFrameNanos { }

        historyMotionWindowOpen = !reduceMotion && historyMotionOrder.isNotEmpty()
        initialTranscriptRevealed = true
        withFrameNanos { }
        if (reduceMotion) {
            transcriptReveal.snapTo(1f)
        } else {
            transcriptReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = FastOutSlowInEasing,
                ),
            )
            // Last stagger starts at 90ms and runs for 180ms. Close the window after every row has
            // settled so rows composed later while scrolling never replay the initial entrance.
            delay(160)
            historyMotionWindowOpen = false
        }
    }

    // A direct drag means the reader chose an older position. Never fight that gesture with an
    // automatic jump; re-arm following only after they return close to the end.
    LaunchedEffect(isDragged, isNearBottom) {
        if (isDragged && !isNearBottom) followTail = false
        if (!isDragged && isNearBottom) followTail = true
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.lastScrolledBackward }
            .distinctUntilChanged()
            .collect { (scrolling, movingBack) ->
                if (scrolling && movingBack) followTail = false
            }
    }
    // Move the whole transcript smoothly when a prompt is sent. The runway makes the end anchor
    // and the prompt-at-top position equivalent for a short turn, so the generic tail follower can
    // continue from here without snapping the prompt back against the composer.
    LaunchedEffect(latestInputId, bottomAnchorIndex, initialTranscriptRevealed) {
        if (!historyReady || !initialTranscriptRevealed) return@LaunchedEffect
        val inputId = latestInputId ?: return@LaunchedEffect
        if (inputId == positionedInputId) return@LaunchedEffect
        positioningNewTurn = true
        positionedInputId = inputId
        followTail = true
        try {
            if (reduceMotion) {
                listState.scrollToItem(bottomAnchorIndex)
            } else {
                listState.animateScrollToItem(bottomAnchorIndex)
            }
        } finally {
            positioningNewTurn = false
        }
    }
    // `canScrollForward` is layout-backed, so this reacts when the SAME streaming message grows,
    // when final Markdown reflows, and when the keyboard changes viewport height. Token count and
    // item count do not reliably represent any of those cases.
    LaunchedEffect(listState, bottomAnchorIndex) {
        snapshotFlow {
            historyReady && initialTranscriptRevealed &&
                followTail && !isDragged && !jumpAnimating && !positioningNewTurn &&
                listState.canScrollForward
        }
            .distinctUntilChanged()
            .collect { shouldCatchUp ->
                if (shouldCatchUp) listState.scrollToItem(bottomAnchorIndex)
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(ChatPalette.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatHeader(title = title, onBack = onBack)
            if (cwd.isNotEmpty()) CwdLine(cwd)
            ChatStatusBar(lifecycle, usage)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { transcriptViewportHeightPx = it.height },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = transcriptReveal.value
                            translationY = (1f - transcriptReveal.value) * transcriptRisePx
                        },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(earlierDisplay, key = { it.id }) { item ->
                        MessageRow(
                            item = item,
                            streaming = isStreaming(item, lifecycle, lastId),
                            thinkingLive = lifecycle.isInFlight,
                            reduceMotion = reduceMotion,
                            userMessageOrdinal = (item as? TranscriptItem.User)
                                ?.let { userMessageOrdinals[it.msgId] },
                            editUserMessageEnabled = userEditEnabled,
                            onEditUserMessage = { editDraft = it },
                            revealMotion = if (historyMotionWindowOpen) {
                                historyMotionOrder[item.id]?.let { delayMillis ->
                                    MessageRevealMotion(
                                        batchKey = "$revealSessionKey:initial",
                                        delayMillis = delayMillis,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    if (latestTurn.isNotEmpty()) {
                        item(key = "latest-turn-$latestInputId") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { latestTurnHeightPx = it.height },
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    latestTurn.forEach { item ->
                                        androidx.compose.runtime.key(item.id) {
                                            MessageRow(
                                                item = item,
                                                streaming = isStreaming(item, lifecycle, lastId),
                                                thinkingLive = lifecycle.isInFlight,
                                                reduceMotion = reduceMotion,
                                                animateEntry = item.id == enteringInputId,
                                                userMessageOrdinal = (item as? TranscriptItem.User)
                                                    ?.let { userMessageOrdinals[it.msgId] },
                                                editUserMessageEnabled = userEditEnabled,
                                                onEditUserMessage = { editDraft = it },
                                                revealMotion = if (historyMotionWindowOpen) {
                                                    historyMotionOrder[item.id]?.let { delayMillis ->
                                                        MessageRevealMotion(
                                                            batchKey = "$revealSessionKey:initial",
                                                            delayMillis = delayMillis,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }
                                    if (showTyping) {
                                        TypingIndicator(reduceMotion = reduceMotion)
                                    }
                                }
                                Spacer(Modifier.height(responseRunway))
                            }
                        }
                    } else if (showTyping) {
                        item(key = "typing") { TypingIndicator(reduceMotion = reduceMotion) }
                    }
                    item(key = "bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToLatest,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    enter = fadeIn(tween(if (reduceMotion) 0 else 140)),
                    exit = fadeOut(tween(if (reduceMotion) 0 else 100)),
                ) {
                    JumpToLatestButton(
                        onClick = {
                            chatScope.launch {
                                jumpAnimating = true
                                followTail = true
                                try {
                                    if (reduceMotion) {
                                        listState.scrollToItem(bottomAnchorIndex)
                                    } else {
                                        listState.animateScrollToItem(bottomAnchorIndex)
                                    }
                                } finally {
                                    jumpAnimating = false
                                }
                            }
                        },
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !historyReady,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 20.dp),
                    enter = fadeIn(tween(if (reduceMotion) 0 else 100)),
                    exit = fadeOut(tween(if (reduceMotion) 0 else 90)),
                ) {
                    if (historyLoadFailed) {
                        Text(
                            text = "Conversation unavailable",
                            color = ChatPalette.secondaryText,
                            fontSize = 13.sp,
                        )
                    } else {
                        TypingIndicator(
                            reduceMotion = reduceMotion,
                            contentDescription = "Loading conversation",
                        )
                    }
                }
            }

            if (lifecycle is SessionLifecycle.Error) {
                ConnectionBanner(text = lifecycle.message)
            }

            // Float-free attached composer / banner. The composer owns its own draft text.
            // Pad by max(IME, navBar): edge-to-edge means the keyboard would otherwise draw OVER the
            // composer (you'd tap "Ask anything", the keyboard covers the field, and typing looks dead).
            // ime.union(navigationBars) lifts it above the keyboard when open, above the nav bar when closed.
            val composerInsets = WindowInsets.ime.union(WindowInsets.navigationBars)
            if (isReadOnly) {
                ReadOnlyBanner(modifier = Modifier.windowInsetsPadding(composerInsets))
            } else {
                Box(modifier = Modifier.windowInsetsPadding(composerInsets)) {
                    Composer(
                        isInFlight = lifecycle.isInFlight,
                        connectionState = connectionState,
                        editDraft = editDraft,
                        onSubmitEdit = { target, revisedText ->
                            editDraft = null
                            followTail = true
                            onEditSend(
                                target.messageOrdinal,
                                target.messageId,
                                revisedText,
                            )
                        },
                        onCancelEdit = { editDraft = null },
                        onSend = { message ->
                            followTail = true
                            onSend(message)
                        },
                        onStop = onStop,
                        isRecording = isRecording,
                        recordSeconds = recordSeconds,
                        voiceEnabled = voiceEnabled,
                        onMic = onMic,
                        onStopRecording = onStopRecording,
                    )
                }
            }
        }

        // Blocking request sheets — permission takes precedence over an elicitation.
        if (pendingPermission != null) {
            PermissionSheet(
                permission = pendingPermission,
                decisionState = permissionDecisionState,
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

/** Centered transcript affordance shown only after the reader leaves the live tail. */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    val label = "Jump to latest response"
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            }
            .clickable(role = Role.Button, onClickLabel = label) {
                haptics.perform(HapticCue.Action)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = 1.8.dp.toPx()
                val centerX = size.width / 2f
                val tipY = size.height * 0.78f
                drawLine(
                    color = Color.White,
                    start = Offset(centerX, size.height * 0.18f),
                    end = Offset(centerX, tipY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.26f, size.height * 0.52f),
                    end = Offset(centerX, tipY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.74f, size.height * 0.52f),
                    end = Offset(centerX, tipY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
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

/** Black header with the actual session title and no placeholder actions. */
@Composable
private fun ChatHeader(title: String, onBack: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.canvas)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptics.perform(HapticCue.Selection)
                onBack()
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title.ifBlank { "Chat" },
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Spacer(Modifier.size(48.dp))
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
        Text(text, color = Color.White, fontSize = 13.sp)
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
            cwd = "~/projects/agenc-android",
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
