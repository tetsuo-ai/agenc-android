package ai.tetsuo.agenc.ui.screens

import ai.tetsuo.agenc.data.AppGraph
import ai.tetsuo.agenc.data.Prefs
import ai.tetsuo.agenc.R
import ai.tetsuo.agenc.model.AccountUiState
import ai.tetsuo.agenc.model.CoreAccountLinkState
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.notif.NotificationCoordinator
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class AccountSection {
    AgenCAccount,
    ConnectedCore,
    Notifications,
    Delivery,
    HardwareWallet,
    Security,
}

/** The only product plan currently offered; raw backend/Core tier strings never reach the UI. */
internal const val CURRENT_ACCOUNT_PLAN_LABEL = "FREE"

/** Single source of truth for headings so optional sections never produce duplicate numbers. */
internal fun accountSections(
    hasNotificationSettings: Boolean,
    hasHardwareWallet: Boolean,
): List<AccountSection> = buildList {
    add(AccountSection.AgenCAccount)
    add(AccountSection.ConnectedCore)
    if (hasNotificationSettings) {
        add(AccountSection.Notifications)
        add(AccountSection.Delivery)
    }
    if (hasHardwareWallet) add(AccountSection.HardwareWallet)
    add(AccountSection.Security)
}

internal fun coreAccountStatusLabel(state: CoreAccountLinkState): String = when (state) {
    CoreAccountLinkState.Checking -> "CHECKING"
    CoreAccountLinkState.Offline -> "OFFLINE"
    CoreAccountLinkState.Unavailable -> "UNAVAILABLE"
    CoreAccountLinkState.NotLoggedIn -> "NOT LOGGED IN"
    is CoreAccountLinkState.Linked -> "LINKED"
    is CoreAccountLinkState.Mismatch -> "ACCOUNT MISMATCH"
    CoreAccountLinkState.Unverified -> "UNVERIFIED"
}

/** Account, preferences and hardware wallet controls in the shared black AgenC shell. */
@Composable
fun AccountScreen(
    account: AccountUiState?,
    machineName: String?,
    connectionStatus: ConnectionStatus,
    onRefreshAccount: () -> Unit,
    onLogout: () -> Unit,
    prefs: Prefs? = runCatching { AppGraph.prefs }.getOrNull(),
    notifications: NotificationCoordinator? = runCatching { AppGraph.notifications }.getOrNull(),
    onOpenLedger: (() -> Unit)? = null,
) {
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var notificationStatusRevision by remember { mutableIntStateOf(0) }
    var notificationTestResult by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationStatusRevision += 1
                notificationTestResult = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationStatus = remember(notifications, notificationStatusRevision) {
        notifications?.deliveryStatus()
    }
    val openNotificationSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }
    val sendTestNotification = {
        notificationTestResult = if (notifications?.postTestNotification() == true) {
            "SENT"
        } else {
            "BLOCKED"
        }
        notificationStatusRevision += 1
    }
    val sections = accountSections(
        hasNotificationSettings = prefs != null,
        hasHardwareWallet = onOpenLedger != null,
    )
    fun indexOf(section: AccountSection): String =
        (sections.indexOf(section) + 1).toString().padStart(2, '0')

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
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "account-header") {
            MainScreenHeader(section = "AgenC / Account", title = "Account")
        }

        item(key = "identity-heading") {
            SectionHeading(index = indexOf(AccountSection.AgenCAccount), title = "AgenC account")
        }
        item(key = "identity") { IdentityPanel(account?.phone) }

        item(key = "core-heading") {
            SectionHeading(index = indexOf(AccountSection.ConnectedCore), title = "Connected Core")
        }
        item(key = "core-account") {
            ConnectedCorePanel(
                state = account?.core ?: CoreAccountLinkState.Unavailable,
                machineName = machineName,
                connectionStatus = connectionStatus,
                onRefresh = onRefreshAccount,
            )
        }

        if (prefs != null) {
            item(key = "notification-settings") {
                NotificationsSection(
                    prefs = prefs,
                    notificationIndex = indexOf(AccountSection.Notifications),
                    deliveryIndex = indexOf(AccountSection.Delivery),
                    deliveryStatus = notificationStatus,
                    testResult = notificationTestResult,
                    onOpenSystemSettings = openNotificationSettings,
                    onSendTestNotification = sendTestNotification,
                )
            }
        }

        if (onOpenLedger != null) {
            item(key = "wallet-heading") {
                SectionHeading(index = indexOf(AccountSection.HardwareWallet), title = "Hardware wallet")
            }
            item(key = "wallet") { WalletAction(onOpenLedger) }
        }

        item(key = "security-heading") {
            SectionHeading(index = indexOf(AccountSection.Security), title = "Security")
        }
        item(key = "logout") { LogoutAction { showLogoutConfirm = true } }
    }

    if (showLogoutConfirm) {
        LogoutDialog(
            onDismiss = { showLogoutConfirm = false },
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
        )
    }
}

@Composable
private fun NotificationsSection(
    prefs: Prefs,
    notificationIndex: String,
    deliveryIndex: String,
    deliveryStatus: NotificationCoordinator.DeliveryStatus?,
    testResult: String?,
    onOpenSystemSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
) {
    var notifyAttention by rememberSaveable { mutableStateOf(prefs.notifyAttention) }
    var notifyTurnDone by rememberSaveable { mutableStateOf(prefs.notifyTurnDone) }
    var notifyErrors by rememberSaveable { mutableStateOf(prefs.notifyErrors) }
    var quietEnabled by rememberSaveable { mutableStateOf(prefs.quietHoursEnabled) }
    var quietStart by rememberSaveable { mutableIntStateOf(prefs.quietStartHour) }
    var quietEnd by rememberSaveable { mutableIntStateOf(prefs.quietEndHour) }
    var threshold by rememberSaveable { mutableIntStateOf(prefs.turnDoneThresholdSeconds) }
    val deliveryReady = deliveryStatus?.isReadyFor(
        attention = notifyAttention || notifyErrors,
        turnDone = notifyTurnDone,
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeading(index = notificationIndex, title = "Notifications")
        SettingsGroup {
            SettingsActionRow(
                label = "Android delivery",
                value = when {
                    deliveryStatus == null -> "CHECK"
                    deliveryReady == true -> "ON"
                    else -> "FIX"
                },
                onClick = onOpenSystemSettings,
            )
            SettingsDivider()
            ToggleRow("Approval & input", notifyAttention) {
                notifyAttention = it
                prefs.notifyAttention = it
                if (it && deliveryStatus?.let {
                        !it.runtimePermissionGranted || !it.appEnabled || !it.attentionChannelEnabled
                    } == true
                ) {
                    onOpenSystemSettings()
                }
            }
            SettingsDivider()
            ToggleRow("Turn complete", notifyTurnDone) {
                notifyTurnDone = it
                prefs.notifyTurnDone = it
                if (it && deliveryStatus?.let {
                        !it.runtimePermissionGranted || !it.appEnabled || !it.turnDoneChannelEnabled
                    } == true
                ) {
                    onOpenSystemSettings()
                }
            }
            SettingsDivider()
            ToggleRow("Errors", notifyErrors) {
                notifyErrors = it
                prefs.notifyErrors = it
                if (it && deliveryStatus?.let {
                        !it.runtimePermissionGranted || !it.appEnabled || !it.attentionChannelEnabled
                    } == true
                ) {
                    onOpenSystemSettings()
                }
            }
            SettingsDivider()
            SettingsActionRow(
                label = "Test notification",
                value = testResult ?: "SEND",
                onClick = onSendTestNotification,
            )
            SettingsNote(
                if (deliveryReady == false) {
                    "Android is blocking the app or a notification channel. Open Android delivery to fix it."
                } else {
                    "Alerts are local while AgenC stays connected in the background. The open chat notifies after you leave the app; muted sessions stay quiet."
                },
            )
        }

        SectionHeading(index = deliveryIndex, title = "Delivery")
        SettingsGroup {
            ToggleRow("Quiet hours", quietEnabled, enabled = notifyTurnDone) {
                quietEnabled = it
                prefs.quietHoursEnabled = it
            }
            AnimatedVisibility(
                visible = quietEnabled && notifyTurnDone,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    SettingsDivider()
                    TimePickerRow(
                        label = "From",
                        hour = quietStart,
                        onHourChange = { selectedHour ->
                            quietStart = selectedHour
                            prefs.quietStartHour = selectedHour
                        },
                    )
                    SettingsDivider()
                    TimePickerRow(
                        label = "To",
                        hour = quietEnd,
                        onHourChange = { selectedHour ->
                            quietEnd = selectedHour
                            prefs.quietEndHour = selectedHour
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = notifyTurnDone,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    SettingsDivider()
                    ValueAdjuster(
                        label = "Minimum turn time",
                        value = "${threshold}s",
                        onMinus = {
                            threshold = (threshold - 5).coerceAtLeast(0)
                            prefs.turnDoneThresholdSeconds = threshold
                        },
                        onPlus = {
                            threshold = (threshold + 5).coerceAtMost(300)
                            prefs.turnDoneThresholdSeconds = threshold
                        },
                        minusDescription = "Decrease threshold",
                        plusDescription = "Increase threshold",
                    )
                }
            }
            val timeZone = TimeZone.getDefault()
            val zone = timeZone.getDisplayName(
                timeZone.inDaylightTime(Date()),
                TimeZone.SHORT,
            )
            SettingsNote(
                when {
                    !notifyTurnDone ->
                        "Turn-complete alerts are off. Enable them above to use delivery controls."
                    quietEnabled && quietStart == quietEnd ->
                        "Start and end match, so quiet hours are inactive."
                    quietEnabled ->
                        "Turn-complete alerts are suppressed during this $zone window. Approval, input, and errors remain immediate."
                    else ->
                        "Approval, input, and error alerts remain immediate. Times use $zone."
                },
            )
        }
    }
}

private fun NotificationCoordinator.DeliveryStatus.isReadyFor(
    attention: Boolean,
    turnDone: Boolean,
): Boolean = runtimePermissionGranted && appEnabled &&
    (!attention || attentionChannelEnabled) &&
    (!turnDone || turnDoneChannelEnabled)

@Composable
private fun SettingsActionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button) {
                haptics.perform(HapticCue.Action)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MainPalette.ink,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = MainPalette.muted,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .8.sp,
        )
    }
}

@Composable
private fun ConnectedCorePanel(
    state: CoreAccountLinkState,
    machineName: String?,
    connectionStatus: ConnectionStatus,
    onRefresh: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    val status = coreAccountStatusLabel(state)
    val statusColor = if (state is CoreAccountLinkState.Mismatch) {
        MaterialTheme.colorScheme.error
    } else {
        MainPalette.ink
    }
    val detail = when (state) {
        CoreAccountLinkState.Checking ->
            "Reading the AgenC account currently active in Core."
        CoreAccountLinkState.Offline ->
            "This phone cannot reach the paired Core right now."
        CoreAccountLinkState.Unavailable ->
            "Core is connected, but its account status could not be read."
        CoreAccountLinkState.NotLoggedIn ->
            "No AgenC account is active in Core. Run /login in the Core TUI, then refresh."
        is CoreAccountLinkState.Linked ->
            "This phone and Core are authenticated as the same AgenC account."
        is CoreAccountLinkState.Mismatch ->
            "Core is authenticated as another account. Run /logout, then /login in Core."
        CoreAccountLinkState.Unverified ->
            "Core is authenticated, but it did not return enough identity data to verify the link."
    }
    val connectionLabel = when (connectionStatus) {
        ConnectionStatus.Connected -> "ONLINE"
        ConnectionStatus.Connecting -> "CONNECTING"
        ConnectionStatus.Offline -> "OFFLINE"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MainPalette.surface)
            .border(1.dp, MainPalette.line, RoundedCornerShape(18.dp))
            .semantics { stateDescription = status },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = machineName?.takeIf { it.isNotBlank() } ?: "Paired computer",
                        color = MainPalette.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = connectionLabel,
                        color = MainPalette.quiet,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .8.sp,
                    )
                }
                Text(
                    text = status,
                    color = statusColor,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = .7.sp,
                    textAlign = TextAlign.End,
                )
            }
            Text(detail, color = MainPalette.muted, style = MaterialTheme.typography.bodyMedium)

        }
        HorizontalDivider(thickness = 1.dp, color = MainPalette.lineSoft)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClickLabel = "Refresh Core account") {
                    haptics.perform(HapticCue.Action)
                    onRefresh()
                }
                .padding(horizontal = 17.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "REFRESH",
                color = MainPalette.ink,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = .9.sp,
            )
        }
    }
}

@Composable
private fun IdentityPanel(identity: Identity?) {
    val displayName = identity?.displayName?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .border(1.dp, MainPalette.line, RoundedCornerShape(20.dp))
            .background(MainPalette.surface, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MainPalette.surfaceRaised)
                .border(1.dp, MainPalette.line, RoundedCornerShape(18.dp))
                .semantics { contentDescription = "Profile picture" },
            contentAlignment = Alignment.Center,
        ) {
            identity?.let(::initials)?.let { monogram ->
                Text(
                    text = monogram,
                    color = MainPalette.ink,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = .8.sp,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (displayName != null) {
                Text(
                    text = displayName,
                    color = MainPalette.ink,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(156.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MainPalette.lineSoft)
                        .semantics { contentDescription = "Account profile pending" },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FreeTierLabel()
    }
}

@Composable
private fun FreeTierLabel() {
    Text(
        text = CURRENT_ACCOUNT_PLAN_LABEL,
        color = MainPalette.ink,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = .9.sp,
        modifier = Modifier
            .border(1.dp, MainPalette.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun WalletAction(onOpenLedger: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MainPalette.ink)
            .clickable(role = Role.Button) {
                haptics.perform(HapticCue.Action)
                onOpenLedger()
            }
            .padding(horizontal = 17.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Wallet,
            contentDescription = null,
            tint = MainPalette.canvas,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("LEDGER FLEX", color = MainPalette.canvas, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
            Text("Fund an agent · keys stay on device", color = MainPalette.canvas.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
        }
        Text("OPEN  →", color = MainPalette.canvas, style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MainPalette.surface)
            .border(1.dp, MainPalette.line, RoundedCornerShape(18.dp)),
        content = content,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MainPalette.lineSoft,
    )
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        color = MainPalette.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MainPalette.surfaceRaised)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { value ->
                    haptics.perform(if (value) HapticCue.ToggleOn else HapticCue.ToggleOff)
                    onChange(value)
                },
            )
            .alpha(if (enabled) 1f else .46f)
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MainPalette.ink,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MainPalette.canvas,
                checkedTrackColor = MainPalette.ink,
                checkedBorderColor = MainPalette.ink,
                uncheckedThumbColor = MainPalette.muted,
                uncheckedTrackColor = MainPalette.surfaceRaised,
                uncheckedBorderColor = MainPalette.line,
            ),
        )
    }
}

@Composable
private fun TimePickerRow(
    label: String,
    hour: Int,
    onHourChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberAgenCHaptics()
    val openPicker = {
        haptics.perform(HapticCue.Adjust)
        TimePickerDialog(
            context,
            R.style.Theme_AgenC_TimePicker,
            { _, selectedHour, _ -> onHourChange(selectedHour) },
            hour,
            0,
            DateFormat.is24HourFormat(context),
        ).show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClickLabel = "Change $label time", onClick = openPicker)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MainPalette.ink, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            text = localizedHour(hour, DateFormat.is24HourFormat(context)),
            color = MainPalette.ink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "CHANGE",
            color = MainPalette.muted,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .7.sp,
        )
    }
}

@Composable
private fun ValueAdjuster(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusDescription: String,
    plusDescription: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MainPalette.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        StepButton(symbol = "−", contentDescription = minusDescription, onClick = onMinus)
        Text(
            text = value,
            color = MainPalette.ink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        StepButton(symbol = "+", contentDescription = plusDescription, onClick = onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, contentDescription: String, onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, MainPalette.line, RoundedCornerShape(13.dp))
            .clickable(role = Role.Button, onClickLabel = contentDescription) {
                haptics.perform(HapticCue.Adjust)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = MainPalette.ink, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LogoutAction(onClick: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MainPalette.line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) {
                haptics.perform(HapticCue.Action)
                onClick()
            }
            .padding(horizontal = 17.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgenCGlyphIcon(
            glyph = AgenCGlyph.Exit,
            contentDescription = null,
            tint = MainPalette.muted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("LOG OUT", color = MainPalette.muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("CLEAR LOCAL DATA", color = MainPalette.quiet, style = MaterialTheme.typography.labelSmall, letterSpacing = .6.sp)
    }
}

@Composable
private fun LogoutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MainPalette.surface)
                .border(1.dp, MainPalette.line, RoundedCornerShape(22.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Log out?", color = MainPalette.ink, style = MaterialTheme.typography.headlineSmall)
            Text(
                "This disconnects the phone, clears local chats and credentials, and asks Core to revoke this pairing. You will need to sign in and pair again.",
                color = MainPalette.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogAction(
                    label = "CANCEL",
                    foreground = MainPalette.ink,
                    background = MainPalette.surfaceRaised,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                DialogAction(
                    label = "LOG OUT",
                    foreground = MainPalette.canvas,
                    background = MainPalette.ink,
                    cue = HapticCue.Reject,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    foreground: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cue: HapticCue = HapticCue.Action,
) {
    val haptics = rememberAgenCHaptics()
    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(role = Role.Button) {
                haptics.perform(cue)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelMedium, letterSpacing = .8.sp)
    }
}

private fun localizedHour(hour: Int, use24Hour: Boolean): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
    }
    val pattern = if (use24Hour) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.time)
}

private fun initials(identity: Identity): String? {
    val parts = identity.displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return parts.take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .takeIf { it.isNotBlank() }
}

@Preview(name = "Account / dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AccountScreenPreview() {
    val identity = Identity(
        displayName = "AgenC User",
        email = "user@example.invalid",
        accountId = "acct-preview",
    )
    AgenCTheme {
        AccountScreen(
            account = AccountUiState(
                phone = identity,
                core = CoreAccountLinkState.Linked(identity),
            ),
            machineName = "Workstation",
            connectionStatus = ConnectionStatus.Connected,
            onRefreshAccount = {},
            onLogout = {},
        )
    }
}

@Preview(name = "Account / Core not logged in", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AccountScreenCoreLoggedOutPreview() {
    AgenCTheme {
        AccountScreen(
            account = AccountUiState(
                phone = Identity("AgenC User", "user@example.invalid", "acct-preview"),
                core = CoreAccountLinkState.NotLoggedIn,
            ),
            machineName = "Workstation",
            connectionStatus = ConnectionStatus.Connected,
            onRefreshAccount = {},
            onLogout = {},
        )
    }
}
