package ai.tetsuo.agenc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.PendingElicitation
import ai.tetsuo.agenc.model.PendingPermission
import ai.tetsuo.agenc.model.QuestionOption
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette

/**
 * The allow/deny bottom sheet for a tool permission request. Big, full-width capsules:
 * Allow once (filled purple) / Allow for session (soft-purple outline) / Deny (subtle). Each tap
 * fires haptic feedback via [LocalHapticFeedback] — a confirm bump for the affirmative actions,
 * a softer reject for deny. The request blocks the turn, so the sheet cannot be swiped to dismiss
 * (no dismiss callback wired); only Allow/Deny exit it.
 *
 * [onApprove] scope is "once" or "session"; [onDeny] takes no scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    permission: PendingPermission,
    onApprove: (scope: String) -> Unit,
    onDeny: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { /* blocks the turn — only Allow/Deny exit */ },
        sheetState = sheetState,
        containerColor = ChatPalette.canvas,
        dragHandle = null,
    ) {
        PermissionSheetContent(permission, onApprove, onDeny)
    }
}

/** Sheet body split out so it renders standalone in @Preview (ModalBottomSheet needs a host). */
@Composable
internal fun PermissionSheetContent(
    permission: PendingPermission,
    onApprove: (scope: String) -> Unit,
    onDeny: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(60.dp).background(Brand.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = Brand.accent, modifier = Modifier.size(28.dp))
            }
            Text(
                "ALLOW THIS ACTION?",
                color = ChatPalette.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                permission.title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                permission.detail,
                color = ChatPalette.secondaryText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            if (permission.permissions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in permission.permissions) {
                        Text(
                            p,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CapsuleButton("Allow once", Icons.Filled.Check, CapsuleStyle.Primary) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onApprove("once")
            }
            CapsuleButton("Allow for session", Icons.Filled.CheckCircle, CapsuleStyle.SecondaryAccent) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onApprove("session")
            }
            CapsuleButton("Deny", Icons.Filled.Close, CapsuleStyle.Deny) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDeny()
            }
        }
    }
}

private enum class CapsuleStyle { Primary, SecondaryAccent, Deny }

/** A large (~52dp) capsule action button — the three permission weights. */
@Composable
private fun CapsuleButton(
    title: String,
    icon: ImageVector,
    style: CapsuleStyle,
    onClick: () -> Unit,
) {
    val (bg, fg, border) = when (style) {
        CapsuleStyle.Primary -> Triple(Brand.accent, Color.White, Color.Transparent)
        CapsuleStyle.SecondaryAccent -> Triple(Brand.accentSoft, Brand.accent, Brand.accent.copy(alpha = 0.35f))
        CapsuleStyle.Deny -> Triple(Color.White.copy(alpha = 0.05f), ChatPalette.secondaryText, Color.White.copy(alpha = 0.10f))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The elicitation bottom sheet — shown when the agent asks the user a question. Tapping an option
 * answers with its label; if [PendingElicitation.allowsOther] is set, a free-text field lets the
 * user type a custom answer. Blocks the turn, so swipe-dismiss is a no-op; answering exits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElicitationSheet(
    elicitation: PendingElicitation,
    onAnswer: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { /* an open question blocks the turn — answer it */ },
        sheetState = sheetState,
        containerColor = ChatPalette.canvas,
        dragHandle = null,
    ) {
        ElicitationSheetContent(elicitation, onAnswer)
    }
}

@Composable
internal fun ElicitationSheetContent(
    elicitation: PendingElicitation,
    onAnswer: (String) -> Unit,
) {
    var other by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = Brand.accent, modifier = Modifier.size(20.dp))
            Text("The agent needs your input", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(elicitation.question, color = Color.White, fontSize = 15.sp)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (opt in elicitation.options) {
                Text(
                    opt.label,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .clickable { onAnswer(opt.label) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }

        if (elicitation.allowsOther) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (other.isEmpty()) {
                        Text("Type an answer…", color = ChatPalette.secondaryText, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = other,
                        onValueChange = { other = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Brand.accent),
                        maxLines = 3,
                    )
                }
                val enabled = other.trim().isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (enabled) Brand.accent else ChatPalette.sendButtonDisabled, CircleShape)
                        .clickable(enabled = enabled, onClickLabel = "Send answer") {
                            val t = other.trim()
                            if (t.isNotEmpty()) onAnswer(t)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PermissionSheetPreview() {
    AgenCTheme {
        PermissionSheetContent(
            permission = PendingPermission(
                id = "req-1",
                toolName = "bash",
                title = "Run a shell command",
                detail = "The agent wants to run ./gradlew test in your project directory.",
                kind = ai.tetsuo.agenc.model.ToolKind.bash,
                permissions = listOf("read", "exec"),
            ),
            onApprove = {},
            onDeny = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ElicitationSheetPreview() {
    AgenCTheme {
        ElicitationSheetContent(
            elicitation = PendingElicitation(
                id = "elic-1",
                question = "Which environment should I deploy to?",
                options = listOf(
                    QuestionOption("p", "Production"),
                    QuestionOption("s", "Staging"),
                ),
                allowsOther = true,
            ),
            onAnswer = {},
        )
    }
}
