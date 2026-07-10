package ai.tetsuo.agenc.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** Small semantic vocabulary for AgenC touch feedback. */
internal enum class HapticCue {
    Selection,
    Adjust,
    ToggleOn,
    ToggleOff,
    Action,
    Confirm,
    Reject,
}

/**
 * Uses Android's view haptics instead of driving the vibrator directly. This respects the user's
 * system haptic setting and needs no runtime permission.
 */
@Stable
internal class AgenCHaptics(private val view: View) {
    fun perform(cue: HapticCue) {
        val feedback = when (cue) {
            HapticCue.Selection -> HapticFeedbackConstantsCompat.SEGMENT_TICK
            HapticCue.Adjust -> HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
            HapticCue.ToggleOn -> HapticFeedbackConstantsCompat.TOGGLE_ON
            HapticCue.ToggleOff -> HapticFeedbackConstantsCompat.TOGGLE_OFF
            HapticCue.Action -> HapticFeedbackConstantsCompat.VIRTUAL_KEY
            HapticCue.Confirm -> HapticFeedbackConstantsCompat.CONFIRM
            HapticCue.Reject -> HapticFeedbackConstantsCompat.REJECT
        }
        ViewCompat.performHapticFeedback(view, feedback)
    }
}

@Composable
internal fun rememberAgenCHaptics(): AgenCHaptics {
    val view = LocalView.current
    return remember(view) { AgenCHaptics(view) }
}
