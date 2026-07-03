package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand

/**
 * The session list — the Android port of iOS `SessionListView` + `SessionRow`.
 *
 * Follows the SYSTEM appearance (light like the reference screenshot, or dark): a big bold
 * "Sessions" title, rows for sessions, and an empty state with a chat-bubble icon, copy, and a
 * #7D4FEB "New chat" pill with a + icon. The floating bottom tab bar lives in the Main shell, so
 * this screen leaves room at the bottom and renders no bar of its own.
 *
 * Stateless: the list and every callback are passed in.
 *
 * @param sessions the sessions to render (already sorted by the caller).
 * @param onOpen fires when a row is tapped.
 * @param onNewChat fires from the empty-state button (and from the host where wired).
 * @param onAccount account action (kept for signature compatibility; the tab bar owns navigation).
 * @param mutedSessions ids the user has muted — no background notifications; shown with a bell-slash.
 * @param onToggleMute long-press action to mute/unmute a session (Android's swipe-action equivalent).
 */
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    onOpen: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onAccount: () -> Unit,
    mutedSessions: Set<String> = emptySet(),
    onToggleMute: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Edge-to-edge: keep the big title clear of the status bar / notification area.
            .statusBarsPadding(),
    ) {
        Text(
            text = "Sessions",
            color = colors.onBackground,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        )

        if (sessions.isEmpty()) {
            EmptySessions(onNewChat = onNewChat)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Leave room for the floating tab bar.
                contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        summary = session,
                        muted = mutedSessions.contains(session.id),
                        onClick = { onOpen(session) },
                        onLongClick = { onToggleMute(session.id) },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: SessionSummary,
    muted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary.isTerminalOrigin) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = "Started in terminal, view only",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = summary.title,
                    color = colors.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (muted) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.NotificationsOff,
                        contentDescription = "Muted",
                        tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (summary.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary.subtitle,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = summary.cwd.ifBlank { "default" },
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(horizontalAlignment = Alignment.End) {
            LifecyclePill(summary.lifecycle)
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary.updated,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

/** A small tinted capsule for the session lifecycle, with a live dot when the agent is active. */
@Composable
private fun LifecyclePill(lifecycle: SessionLifecycle) {
    val tint = lifecycle.pillTint()
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lifecycle.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(tint, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = lifecycle.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptySessions(onNewChat: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .padding(bottom = 60.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No sessions yet",
                color = colors.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Start a conversation to spin up a coding session on your host.",
                color = colors.onSurfaceVariant,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .background(Brand.accent, RoundedCornerShape(22.dp))
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("New chat", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

private fun sampleSessions(): List<SessionSummary> = listOf(
    SessionSummary(
        id = "sess-1",
        title = "Refactor auth flow",
        lifecycle = SessionLifecycle.Running,
        cwd = "/Users/pavel/agenc-core",
        subtitle = "wiring the device-flow login",
        updated = "now",
    ),
    SessionSummary(
        id = "sess-2",
        title = "Port iOS screens to Compose",
        lifecycle = SessionLifecycle.AwaitingPermission,
        cwd = "/Users/pavel/AgenC-Android",
        subtitle = "needs approval to edit build.gradle",
        updated = "2m",
    ),
    SessionSummary(
        id = "conv-3",
        title = "Investigate flaky test",
        lifecycle = SessionLifecycle.Done,
        cwd = "/Users/pavel/agenc-relay",
        subtitle = "started in terminal",
        updated = "1h",
    ),
)

@Preview(name = "Sessions — list", showBackground = true, backgroundColor = 0xFFFFFFFF)
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

@Preview(name = "Sessions — empty", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun SessionsScreenEmptyPreview() {
    AgenCTheme {
        SessionsScreen(
            sessions = emptyList(),
            onOpen = {},
            onNewChat = {},
            onAccount = {},
        )
    }
}
