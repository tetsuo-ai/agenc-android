package ai.tetsuo.agenc.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLinkPolicyTest {
    @Test
    fun `background paired signed-in app keeps link`() {
        assertTrue(BackgroundLinkPolicy.shouldRun(false, true, true))
    }

    @Test
    fun `foreground app stops background owner`() {
        assertFalse(BackgroundLinkPolicy.shouldRun(true, true, true))
    }

    @Test
    fun `signed-out or unpaired app never starts background link`() {
        assertFalse(BackgroundLinkPolicy.shouldRun(false, false, true))
        assertFalse(BackgroundLinkPolicy.shouldRun(false, true, false))
    }

    @Test
    fun `watchdog is slow and leaves retry cadence to DaemonHub`() {
        assertEquals(30_000L, BackgroundLinkPolicy.WATCHDOG_MILLIS)
    }
}
