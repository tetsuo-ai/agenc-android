package ai.tetsuo.agenc.ui.screens

import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.AgenCTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Monochrome post-login control surface. All state and callbacks remain host-owned. */
@Composable
fun HomeScreen(
    sessions: List<SessionSummary>,
    connectionStatus: ConnectionStatus = ConnectionStatus.Offline,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
) {
    val waiting = sessions.filter {
        it.lifecycle is SessionLifecycle.AwaitingPermission ||
            it.lifecycle is SessionLifecycle.AwaitingInput
    }
    val waitingIds = waiting.mapTo(mutableSetOf()) { it.id }
    val recent = sessions.filterNot { it.id in waitingIds }.take(5)
    val recentSectionIndex = if (waiting.isEmpty()) "02" else "03"

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
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "home-header") {
            MainScreenHeader(
                section = "AgenC / Control",
                title = "Home",
                trailing = { ConnectionIndicator(connectionStatus) },
            )
        }

        item(key = "new-session") {
            PrimarySessionAction(onClick = onNewChat)
        }

        item(key = "home-metrics") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading(index = "01", title = "Overview")
                HomeMetricStrip(sessionCount = sessions.size, attentionCount = waiting.size)
            }
        }

        if (waiting.isNotEmpty()) {
            item(key = "attention-heading") {
                SectionHeading(index = "02", title = "Needs attention")
            }
            itemsIndexed(
                items = waiting,
                key = { _, session -> "attention-${session.id}" },
            ) { index, session ->
                SessionLine(
                    summary = session,
                    sequence = index + 1,
                    onOpen = { onOpen(session) },
                    showDivider = index != waiting.lastIndex,
                )
            }
        }

        item(key = "recent-heading") {
            SectionHeading(index = recentSectionIndex, title = "Recent")
        }

        if (recent.isEmpty()) {
            item(key = "recent-empty") {
                EmptyHomeMessage(
                    text = if (sessions.isEmpty()) {
                        "No sessions yet. Start one above and it will stay within reach here."
                    } else {
                        "Everything active is already grouped under needs attention."
                    },
                )
            }
        } else {
            itemsIndexed(
                items = recent,
                key = { _, session -> "recent-${session.id}" },
            ) { index, session ->
                SessionLine(
                    summary = session,
                    sequence = index + 1,
                    onOpen = { onOpen(session) },
                    showDivider = index != recent.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun HomeMetricStrip(sessionCount: Int, attentionCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MainPalette.line, RoundedCornerShape(18.dp))
            .background(MainPalette.surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeMetric(
            value = sessionCount,
            label = "Sessions",
            note = if (sessionCount == 1) "workspace" else "workspaces",
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(52.dp)
                .background(MainPalette.line),
        )
        Spacer(Modifier.width(18.dp))
        HomeMetric(
            value = attentionCount,
            label = "Need you",
            note = if (attentionCount == 0) "all clear" else "action required",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeMetric(
    value: Int,
    label: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value.toString().padStart(2, '0'),
            color = MainPalette.ink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label.uppercase(),
            color = MainPalette.muted,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .9.sp,
        )
        Text(note, color = MainPalette.quiet, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyHomeMessage(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MainPalette.lineSoft, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "—",
            color = MainPalette.quiet,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = MainPalette.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun homeSamples(): List<SessionSummary> = listOf(
    SessionSummary("sess-1", "Refactor auth flow", SessionLifecycle.Running, "~/agenc-core", "Wiring login", "now"),
    SessionSummary("sess-2", "Port Android shell", SessionLifecycle.AwaitingPermission, "~/agenc-android", "Needs approval", "2m"),
)

@Preview(name = "Home / dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun HomeScreenPreview() {
    AgenCTheme {
        HomeScreen(
            sessions = homeSamples(),
            connectionStatus = ConnectionStatus.Connected,
            onNewChat = {},
            onOpen = {},
        )
    }
}

@Preview(name = "Home / empty", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun HomeScreenEmptyPreview() {
    AgenCTheme { HomeScreen(sessions = emptyList(), onNewChat = {}, onOpen = {}) }
}
