package ai.tetsuo.agenc.ui.screens

import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Full session index with a compact, technical, strictly monochrome visual hierarchy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onOpen: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onAccount: () -> Unit,
    mutedSessions: Set<String> = emptySet(),
    onToggleMute: (String) -> Unit = {},
    onDeleteSession: suspend (String) -> Result<Unit> = { Result.success(Unit) },
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAgenCHaptics()
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var deletingSessionId by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val normalizedQuery = remember(query) { normalizeSessionSearchText(query) }
    val visibleSessions = remember(sessions, mutedSessions, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            sessions
        } else {
            sessions.filter { session ->
                session.matchesSessionQuery(normalizedQuery, muted = session.id in mutedSessions)
            }
        }
    }
    val active = visibleSessions.count { it.lifecycle.isInFlight }
    val hasQuery = normalizedQuery.isNotEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MainPalette.canvas)
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 18.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "sessions-header") {
            MainScreenHeader(
                section = "AgenC / Workspace",
                title = "Sessions",
                trailing = { NewSessionButton(onClick = onNewChat) },
            )
        }

        if (sessions.isNotEmpty()) {
            stickyHeader(key = "sessions-search") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MainPalette.canvas)
                        .padding(bottom = 4.dp),
                ) {
                    SessionSearchField(
                        query = query,
                        sessionCount = sessions.size,
                        onQueryChange = onQueryChange,
                        onClear = { onQueryChange("") },
                    )
                }
            }
        }

        item(key = "sessions-summary") {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (hasQuery) {
                        "${visibleSessions.size} of ${sessions.size} sessions match, $active active"
                    } else {
                        "${sessions.size} sessions total, $active active"
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasQuery) {
                        "${visibleSessions.size}/${sessions.size}"
                    } else {
                        sessions.size.toString().padStart(2, '0')
                    },
                    color = MainPalette.ink,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasQuery) "MATCHES" else "TOTAL",
                    color = MainPalette.quiet,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = .9.sp,
                )
                Spacer(Modifier.width(18.dp))
                Box(Modifier.size(3.dp).background(MainPalette.line, RoundedCornerShape(999.dp)))
                Spacer(Modifier.width(18.dp))
                Text(
                    text = active.toString().padStart(2, '0'),
                    color = MainPalette.ink,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text("ACTIVE", color = MainPalette.quiet, style = MaterialTheme.typography.labelSmall, letterSpacing = .9.sp)
            }
        }

        item(key = "sessions-section") {
            SectionHeading(index = "01", title = if (hasQuery) "Search results" else "All sessions")
        }

        if (sessions.isEmpty()) {
            item(key = "sessions-empty") { EmptySessions(onNewChat) }
        } else if (visibleSessions.isEmpty()) {
            item(key = "sessions-no-matches") {
                NoSessionMatches(query = query, onClear = { onQueryChange("") })
            }
        } else {
            itemsIndexed(
                items = visibleSessions,
                key = { _, session -> session.id },
            ) { index, session ->
                SwipeableSessionLine(
                    summary = session,
                    sequence = index + 1,
                    muted = session.id in mutedSessions,
                    onOpen = { onOpen(session) },
                    onToggleMute = { onToggleMute(session.id) },
                    onDeleteIntent = {
                        deleteError = null
                        pendingDelete = session
                    },
                    showDivider = index != visibleSessions.lastIndex,
                )
            }
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(
            session = session,
            deleting = deletingSessionId == session.id,
            error = deleteError,
            onDismiss = {
                if (deletingSessionId == null) {
                    pendingDelete = null
                    deleteError = null
                }
            },
            onConfirm = {
                if (deletingSessionId == null) {
                    deletingSessionId = session.id
                    deleteError = null
                    scope.launch {
                        val result = onDeleteSession(session.id)
                        deletingSessionId = null
                        result.fold(
                            onSuccess = {
                                haptics.perform(HapticCue.Confirm)
                                pendingDelete = null
                            },
                            onFailure = { error ->
                                haptics.perform(HapticCue.Reject)
                                deleteError = error.message
                                    ?.takeIf(String::isNotBlank)
                                    ?: "Couldn't delete this session. Try again."
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SwipeableSessionLine(
    summary: SessionSummary,
    sequence: Int,
    muted: Boolean,
    onOpen: () -> Unit,
    onToggleMute: () -> Unit,
    onDeleteIntent: () -> Unit,
    showDivider: Boolean,
) {
    val haptics = rememberAgenCHaptics()
    val density = LocalDensity.current
    val currentOnDeleteIntent by rememberUpdatedState(onDeleteIntent)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                currentOnDeleteIntent()
                false
            } else {
                true
            }
        },
        positionalThreshold = { distance ->
            minOf(distance * .32f, with(density) { 96.dp.toPx() })
        },
    )

    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .distinctUntilChanged()
            .collect { target ->
                if (target == SwipeToDismissBoxValue.EndToStart) {
                    haptics.perform(HapticCue.Adjust)
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Delete session") {
                            currentOnDeleteIntent()
                            true
                        },
                    )
                },
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MainPalette.ink)
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = "DELETE",
                        color = MainPalette.canvas,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.1.sp,
                    )
                }
            },
        ) {
            SessionLine(
                summary = summary,
                sequence = sequence,
                muted = muted,
                onOpen = onOpen,
                onToggleMute = onToggleMute,
                showDivider = false,
                modifier = Modifier.background(MainPalette.canvas),
            )
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

@Composable
private fun DeleteSessionDialog(
    session: SessionSummary,
    deleting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !deleting,
            dismissOnClickOutside = !deleting,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MainPalette.surfaceRaised)
                .border(1.dp, MainPalette.line, RoundedCornerShape(22.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Delete session?",
                color = MainPalette.ink,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = sessionDisplayTitle(session),
                color = MainPalette.muted,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "This stops the session on Core and removes its local chat history from this device.",
                color = MainPalette.quiet,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (error != null) {
                Text(
                    text = error,
                    color = MainPalette.ink,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            enabled = !deleting,
                            role = Role.Button,
                            onClick = onDismiss,
                        )
                        .padding(horizontal = 17.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "CANCEL",
                        color = if (deleting) MainPalette.quiet else MainPalette.muted,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = .8.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (deleting) MainPalette.line else MainPalette.ink)
                        .clickable(
                            enabled = !deleting,
                            role = Role.Button,
                            onClick = onConfirm,
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (deleting) "DELETING…" else "DELETE",
                        color = if (deleting) MainPalette.quiet else MainPalette.canvas,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = .8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSearchField(
    query: String,
    sessionCount: Int,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MainPalette.surface)
            .border(
                width = 1.dp,
                color = if (focused) MainPalette.muted else MainPalette.line,
                shape = RoundedCornerShape(17.dp),
            )
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Search,
            contentDescription = null,
            tint = if (focused) MainPalette.ink else MainPalette.muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Search sessions" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MainPalette.ink),
            cursorBrush = SolidColor(MainPalette.ink),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search $sessionCount sessions",
                            color = MainPalette.quiet,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Clear session search") {
                        haptics.perform(HapticCue.Action)
                        onClear()
                        focusRequester.requestFocus()
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "CLEAR",
                    color = MainPalette.muted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = .7.sp,
                )
            }
        }
    }
}

@Composable
private fun NoSessionMatches(query: String, onClear: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    val shownQuery = query.trim().let { value ->
        if (value.length <= 64) value else value.take(61) + "…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MainPalette.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Search,
            contentDescription = null,
            tint = MainPalette.muted,
            modifier = Modifier.size(38.dp),
        )
        Text(
            text = "No matching sessions",
            color = MainPalette.ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Nothing matches “$shownQuery”. Try a title, workspace, status or session ID.",
            color = MainPalette.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MainPalette.line, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) {
                    haptics.perform(HapticCue.Action)
                    onClear()
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CLEAR SEARCH",
                color = MainPalette.ink,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = .8.sp,
            )
        }
    }
}

internal fun SessionSummary.matchesSessionQuery(rawQuery: String, muted: Boolean = false): Boolean {
    val terms = normalizeSessionSearchText(rawQuery)
        .split(SESSION_QUERY_SEPARATOR)
        .filter(String::isNotEmpty)
    if (terms.isEmpty()) return true

    val aliases = when (val state = lifecycle) {
        SessionLifecycle.Idle -> "idle"
        SessionLifecycle.Running -> "running working active"
        SessionLifecycle.AwaitingPermission -> "approval permission waiting active"
        SessionLifecycle.AwaitingInput -> "input question waiting active"
        SessionLifecycle.Done -> "done complete completed finished"
        is SessionLifecycle.Error -> "error failed ${state.message}"
    }
    val searchable = normalizeSessionSearchText(
        buildString {
            append(id).append(' ')
            append(title).append(' ')
            append(cwd).append(' ')
            append(subtitle).append(' ')
            append(lifecycle.label).append(' ')
            append(aliases).append(' ')
            if (isTerminalOrigin) append("cli terminal read-only read only ")
            if (muted) append("muted")
        },
    )
    return terms.all(searchable::contains)
}

internal fun normalizeSessionSearchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(SESSION_COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .trim()

private val SESSION_QUERY_SEPARATOR = Regex("\\s+")
private val SESSION_COMBINING_MARKS = Regex("\\p{Mn}+")

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MainPalette.ink)
            .clickable(role = Role.Button) {
                haptics.perform(HapticCue.Action)
                onClick()
            }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Compose,
            contentDescription = null,
            tint = MainPalette.canvas,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "NEW",
            color = MainPalette.canvas,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = .9.sp,
        )
    }
}

@Composable
private fun EmptySessions(onNewChat: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MainPalette.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Sessions,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MainPalette.muted,
        )
        Text(
            text = "Nothing running yet",
            color = MainPalette.ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Start a session and it will appear here with its live state, workspace and history.",
            color = MainPalette.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(1.dp))
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MainPalette.ink)
                .clickable(role = Role.Button) {
                    haptics.perform(HapticCue.Action)
                    onNewChat()
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("START A SESSION", color = MainPalette.canvas, style = MaterialTheme.typography.labelMedium, letterSpacing = .8.sp)
        }
    }
}

private fun sampleSessions(): List<SessionSummary> = listOf(
    SessionSummary(
        id = "sess-1",
        title = "Refactor auth flow",
        lifecycle = SessionLifecycle.Running,
        cwd = "~/agenc-core",
        subtitle = "Wiring the device-flow login",
        updated = "now",
    ),
    SessionSummary(
        id = "sess-2",
        title = "Port iOS screens to Compose",
        lifecycle = SessionLifecycle.AwaitingPermission,
        cwd = "~/agenc-android",
        subtitle = "Needs approval to edit build.gradle",
        updated = "2m",
    ),
    SessionSummary(
        id = "conv-3",
        title = "Investigate flaky test",
        lifecycle = SessionLifecycle.Done,
        cwd = "~/agenc-relay",
        subtitle = "Started in terminal",
        updated = "1h",
    ),
)

@Preview(name = "Sessions / dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SessionsScreenPreview() {
    AgenCTheme {
        SessionsScreen(
            sessions = sampleSessions(),
            onOpen = {},
            onNewChat = {},
            onAccount = {},
        )
    }
}

@Preview(name = "Sessions / empty", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SessionsScreenEmptyPreview() {
    AgenCTheme {
        SessionsScreen(sessions = emptyList(), onOpen = {}, onNewChat = {}, onAccount = {})
    }
}
