package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.data.AppGraph
import ai.tetsuo.agenc.data.Prefs
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand

/**
 * Account screen — the Android port of the identity + logout slice of iOS `AccountView`.
 *
 * Follows the SYSTEM appearance (light or dark): a big bold "Account" title, an identity card, and a
 * Log out action. The floating bottom tab bar lives in the Main shell, so this screen leaves room at
 * the bottom and renders no bar of its own.
 *
 * Stateless: identity is passed in (null while it loads), logout is a callback.
 *
 * @param identity the signed-in identity, or null while loading.
 * @param onLogout fires when the user taps Log out.
 */
@Composable
fun AccountScreen(
    identity: Identity?,
    onLogout: () -> Unit,
    prefs: Prefs? = runCatching { AppGraph.prefs }.getOrNull(),
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Account",
            color = colors.onBackground,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
        )

        ProfileCard(identity)

        if (prefs != null) {
            Spacer(Modifier.height(20.dp))
            NotificationsSection(prefs)
        }

        Spacer(Modifier.height(20.dp))
        LogoutButton(onLogout)
        // Leave room for the floating tab bar.
        Spacer(Modifier.height(110.dp))
    }
}

/**
 * The Notifications settings — the Android port of the iOS Screens.swift Notifications section:
 * the three toggles (Approval needed / Turn complete / Errors), a quiet-hours toggle with From/To
 * hour pickers, and a turn-done threshold stepper. Writes straight through to [Prefs] (not reactive,
 * so local Compose state mirrors it).
 */
@Composable
private fun NotificationsSection(prefs: Prefs) {
    val colors = MaterialTheme.colorScheme

    var notifyAttention by remember { mutableStateOf(prefs.notifyAttention) }
    var notifyTurnDone by remember { mutableStateOf(prefs.notifyTurnDone) }
    var notifyErrors by remember { mutableStateOf(prefs.notifyErrors) }
    var quietEnabled by remember { mutableStateOf(prefs.quietHoursEnabled) }
    var quietStart by remember { mutableIntStateOf(prefs.quietStartHour) }
    var quietEnd by remember { mutableIntStateOf(prefs.quietEndHour) }
    var threshold by remember { mutableIntStateOf(prefs.turnDoneThresholdSeconds) }

    SettingsCard {
        SectionHeader("Notifications")
        ToggleRow("Approval needed", notifyAttention) {
            notifyAttention = it; prefs.notifyAttention = it
        }
        ToggleRow("Turn complete", notifyTurnDone) {
            notifyTurnDone = it; prefs.notifyTurnDone = it
        }
        ToggleRow("Errors", notifyErrors) {
            notifyErrors = it; prefs.notifyErrors = it
        }
        SectionFooter(
            "Get pulled back when your agent needs you, finishes a turn, or hits an error while " +
                "the app is in the background.",
        )
    }

    Spacer(Modifier.height(14.dp))

    SettingsCard {
        SectionHeader("When to notify")
        ToggleRow("Quiet hours", quietEnabled) {
            quietEnabled = it; prefs.quietHoursEnabled = it
        }
        if (quietEnabled) {
            HourPickerRow("From", quietStart) { quietStart = it; prefs.quietStartHour = it }
            HourPickerRow("To", quietEnd) { quietEnd = it; prefs.quietEndHour = it }
        }
        if (notifyTurnDone) {
            StepperRow(
                label = "Turn-done after",
                value = "${threshold}s",
                onMinus = {
                    threshold = (threshold - 5).coerceAtLeast(0); prefs.turnDoneThresholdSeconds = threshold
                },
                onPlus = {
                    threshold = (threshold + 5).coerceAtMost(300); prefs.turnDoneThresholdSeconds = threshold
                },
            )
        }
        SectionFooter(
            "During quiet hours, only urgent “needs you” and error alerts come through — " +
                "turn-complete is held back. Turns shorter than the turn-done threshold don't notify.",
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.accent),
        )
    }
}

@Composable
private fun HourPickerRow(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        StepButton(Icons.Filled.Remove) { onChange((hour + 23) % 24) }
        Spacer(Modifier.width(10.dp))
        Text(
            text = hourLabel(hour),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(72.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        StepButton(Icons.Filled.Add) { onChange((hour + 1) % 24) }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        StepButton(Icons.Filled.Remove, onMinus)
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        StepButton(Icons.Filled.Add, onPlus)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Brand.accent.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Brand.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 12-hour clock label like "10 PM" / "7 AM" — matches the iOS `hourLabel`. */
private fun hourLabel(h: Int): String {
    val period = if (h < 12) "AM" else "PM"
    val twelve = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$twelve $period"
}

@Composable
private fun ProfileCard(identity: Identity?) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Brand.accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Brand.accent,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = identity?.displayName ?: "Loading…",
            color = colors.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (identity != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = identity.handle,
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            TierBadge(identity.tier)
        }
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    Box(
        modifier = Modifier
            .background(Brand.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = tier.label,
            color = Brand.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LogoutButton(onLogout: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.danger.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable(onClick = onLogout)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            tint = Brand.danger,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Log out", color = Brand.danger, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Preview(name = "Account — signed in", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AccountScreenPreview() {
    AgenCTheme {
        AccountScreen(
            identity = Identity(displayName = "Pavel C.", handle = "@pavel", tier = Tier.pro),
            onLogout = {},
        )
    }
}

@Preview(name = "Account — loading", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AccountScreenLoadingPreview() {
    AgenCTheme {
        AccountScreen(identity = null, onLogout = {})
    }
}
