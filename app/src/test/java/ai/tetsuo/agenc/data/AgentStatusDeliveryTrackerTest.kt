package ai.tetsuo.agenc.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusDeliveryTrackerTest {
    @Test fun `event id replay is deduplicated`() {
        val tracker = AgentStatusDeliveryTracker()
        assertTrue(tracker.acceptEvent("s", "e"))
        assertFalse(tracker.acceptEvent("s", "e"))
    }

    @Test fun `terminal turn can complete without seeing running but only once`() {
        val tracker = AgentStatusDeliveryTracker()
        assertTrue(tracker.shouldComplete("s", "turn-1", wasInFlight = false))
        assertFalse(tracker.shouldComplete("s", "turn-1", wasInFlight = false))
    }

    @Test fun `terminal without turn id requires observed in-flight state`() {
        val tracker = AgentStatusDeliveryTracker()
        assertFalse(tracker.shouldComplete("s", null, wasInFlight = false))
        assertTrue(tracker.shouldComplete("s", null, wasInFlight = true))
    }

    @Test fun `delayed running frame for completed turn is ignored`() {
        val tracker = AgentStatusDeliveryTracker()
        assertTrue(tracker.shouldComplete("s", "turn-1", wasInFlight = false))
        assertFalse(tracker.markRunning("s", "turn-1"))
    }
}
