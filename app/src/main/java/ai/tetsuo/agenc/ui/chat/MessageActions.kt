package ai.tetsuo.agenc.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.ChatPalette
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/** A real user-prompt target understood by Core's conversation rewind API. */
internal data class MessageEditDraft(
    val messageOrdinal: Int,
    val messageId: String,
    val text: String,
)

/**
 * Native long-press actions for a transcript message. The transcript stays visually quiet until
 * the user asks for controls; TalkBack exposes the same action through the long-click semantic.
 */
@Composable
internal fun MessageActions(
    text: String,
    copyDescription: String,
    modifier: Modifier = Modifier,
    editEnabled: Boolean = false,
    onEdit: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Do not key this to text: assistant streaming updates every token and would otherwise cancel
    // an in-progress long press or dismiss the menu underneath the user.
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val haptics = rememberAgenCHaptics()
    val showActions = {
        haptics.perform(HapticCue.Action)
        expanded = true
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { showActions() })
                }
                .semantics {
                    onLongClick("Show message actions") {
                        showActions()
                        true
                    }
                },
        ) {
            content()
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 156.dp),
        ) {
            DropdownMenuItem(
                modifier = Modifier.semantics { contentDescription = copyDescription },
                text = { ContextActionLabel("Copy") },
                enabled = text.isNotEmpty(),
                colors = MenuDefaults.itemColors(
                    textColor = Color.White,
                    disabledTextColor = ChatPalette.secondaryText.copy(alpha = 0.45f),
                ),
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    expanded = false
                    haptics.perform(HapticCue.Confirm)
                    view.announceForAccessibility("Message copied")
                },
            )
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { ContextActionLabel("Edit") },
                    enabled = editEnabled,
                    colors = MenuDefaults.itemColors(
                        textColor = Color.White,
                        disabledTextColor = ChatPalette.secondaryText.copy(alpha = 0.45f),
                    ),
                    onClick = {
                        expanded = false
                        haptics.perform(HapticCue.Selection)
                        onEdit()
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextActionLabel(label: String) {
    Text(
        text = label,
        color = Color.Unspecified,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}
