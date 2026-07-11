package ai.tetsuo.agenc

import ai.tetsuo.agenc.data.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSafetyTest {
    @Test
    fun publicBuildDefaultsFailClosed() {
        assertFalse(Demo.ENABLED)
        assertEquals("", Demo.AGENT_WALLET)
        assertEquals(0L, Demo.AMOUNT_LAMPORTS)
        assertEquals(0.0, Demo.AMOUNT_SOL, 0.0)
        assertEquals("", AppGraph.daemonCookie)
        assertNull(AppGraph.DAEMON_COOKIE_OVERRIDE)
    }
}
