package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.SessionLifecycle
import ai.tetsuo.agenc.model.SessionSummary
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand

/**
 * Home — the Android port of iOS `HomeView`. A greeting, a prominent purple "New chat" gradient
 * button, two count tiles (Sessions / Need you), a "Waiting on you" section, and recent sessions.
 *
 * Follows the SYSTEM appearance. Stateless: sessions + callbacks are passed in.
 *
 * @param sessions the full session list (Home derives counts + recents from it).
 * @param onNewChat fires from the New chat call-to-action.
 * @param onOpen fires when a session row is tapped.
 */
@Composable
fun HomeScreen(
    sessions: List<SessionSummary>,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val waiting = sessions.filter {
        it.lifecycle is SessionLifecycle.AwaitingPermission || it.lifecycle is SessionLifecycle.AwaitingInput
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Home",
                    color = colors.onBackground,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                StatusPill(text = "Connected · This computer (remote)", tint = Brand.success)
            }
        }

        item { NewChatCta(onNewChat) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CountTile(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    value = sessions.size,
                    label = "Sessions",
                    tint = Brand.accent,
                    modifier = Modifier.weight(1f),
                )
                CountTile(
                    icon = Icons.Filled.NotificationsActive,
                    value = waiting.size,
                    label = "Need you",
                    tint = Brand.attention,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (waiting.isNotEmpty()) {
            item {
                SectionHeader(title = "Waiting on you", tint = Brand.attention)
            }
            items(waiting.size) { i ->
                val s = waiting[i]
                HomeSessionRow(summary = s, onClick = { onOpen(s) })
            }
        }

        item {
            SectionHeader(title = "Recent sessions", tint = colors.onSurfaceVariant)
        }
        if (sessions.isEmpty()) {
            item {
                Text(
                    text = "No sessions yet. Start one with “New chat” above.",
                    color = colors.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            }
        } else {
            items(minOf(sessions.size, 5)) { i ->
                val s = sessions[i]
                HomeSessionRow(summary = s, onClick = { onOpen(s) })
            }
        }
    }
}

@Composable
private fun NewChatCta(onNewChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNewChat)
            .background(
                Brush.linearGradient(listOf(Brand.accent, Color(0xFF8A3FD0))),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.AddComment,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("New chat", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CountTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, tint.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "$value",
                color = colors.onSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(label, color = colors.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, tint: Color) {
    Text(
        text = title,
        color = tint,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun HomeSessionRow(summary: SessionSummary, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title,
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            HomeLifecyclePill(summary.lifecycle)
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary.updated,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun HomeLifecyclePill(lifecycle: SessionLifecycle) {
    val tint = lifecycle.pillTint()
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lifecycle.isActive) {
            Box(modifier = Modifier.size(6.dp).background(tint, CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(text = lifecycle.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** A small status capsule: a dot + label, tinted. Mirrors iOS `StatusPill`. */
@Composable
internal fun StatusPill(text: String, tint: Color) {
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun homeSamples(): List<SessionSummary> = listOf(
    SessionSummary("sess-1", "Refactor auth flow", SessionLifecycle.Running, "/Users/pavel/agenc-core", "wiring login", "now"),
    SessionSummary("sess-2", "Port iOS screens", SessionLifecycle.AwaitingPermission, "/Users/pavel/AgenC-Android", "needs approval", "2m"),
)

@Preview(name = "Home", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun HomeScreenPreview() {
    AgenCTheme {
        HomeScreen(sessions = homeSamples(), onNewChat = {}, onOpen = {})
    }
}

@Preview(name = "Home — empty", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun HomeScreenEmptyPreview() {
    AgenCTheme {
        HomeScreen(sessions = emptyList(), onNewChat = {}, onOpen = {})
    }
}
