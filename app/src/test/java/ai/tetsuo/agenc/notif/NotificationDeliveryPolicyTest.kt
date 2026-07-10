package ai.tetsuo.agenc.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeliveryPolicyTest {
    @Test
    fun `background completion posts when enabled with default zero threshold`() {
        assertTrue(
            NotificationDeliveryPolicy.shouldPostCompletion(
                backgrounded = true,
                enabled = true,
                muted = false,
                quietHours = false,
                durationSeconds = null,
                minimumDurationSeconds = 0,
            ),
        )
    }

    @Test
    fun `foreground completion does not post`() {
        assertFalse(defaultDecision(backgrounded = false))
    }

    @Test
    fun `disabled completion does not post`() {
        assertFalse(defaultDecision(enabled = false))
    }

    @Test
    fun `muted session does not post`() {
        assertFalse(defaultDecision(muted = true))
    }

    @Test
    fun `quiet hours suppress completion`() {
        assertFalse(defaultDecision(quietHours = true))
    }

    @Test
    fun `custom threshold suppresses unknown and short durations`() {
        assertFalse(defaultDecision(durationSeconds = null, minimumDurationSeconds = 20))
        assertFalse(defaultDecision(durationSeconds = 19, minimumDurationSeconds = 20))
    }

    @Test
    fun `custom threshold includes its boundary`() {
        assertTrue(defaultDecision(durationSeconds = 20, minimumDurationSeconds = 20))
    }

    private fun defaultDecision(
        backgrounded: Boolean = true,
        enabled: Boolean = true,
        muted: Boolean = false,
        quietHours: Boolean = false,
        durationSeconds: Int? = null,
        minimumDurationSeconds: Int = 0,
    ): Boolean = NotificationDeliveryPolicy.shouldPostCompletion(
        backgrounded = backgrounded,
        enabled = enabled,
        muted = muted,
        quietHours = quietHours,
        durationSeconds = durationSeconds,
        minimumDurationSeconds = minimumDurationSeconds,
    )
}
