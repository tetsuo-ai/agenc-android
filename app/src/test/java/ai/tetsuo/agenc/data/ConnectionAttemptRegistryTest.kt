package ai.tetsuo.agenc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptRegistryTest {

    @Test
    fun targetChangeMakesOldCompletionUnableToClearNewAttempt() {
        val registry = ConnectionAttemptRegistry<Any>()
        val localSocket = Any()
        val relaySocket = Any()

        val local = requireNotNull(
            registry.claim("ws://127.0.0.1:7766", relayMode = false) { _, _ -> localSocket },
        )
        assertSame(localSocket, registry.invalidate())

        val relay = requireNotNull(
            registry.claim("wss://relay.example", relayMode = true) { _, _ -> relaySocket },
        )

        assertFalse(registry.complete(local))
        assertTrue("stale completion must leave the relay attempt owned", registry.isRunning)
        assertTrue(registry.complete(relay))
        assertFalse(registry.isRunning)
    }

    @Test
    fun claimSnapshotsEndpointAndTransportMode() {
        val registry = ConnectionAttemptRegistry<String>()

        val attempt = requireNotNull(
            registry.claim("ws://local", relayMode = false) { url, relay -> "$url/$relay" },
        )

        assertEquals("ws://local", attempt.endpoint)
        assertFalse(attempt.relayMode)
        assertEquals("ws://local/false", attempt.resource)
    }

    @Test
    fun concurrentClaimIsSingleFlightAndDoesNotConstructSecondResource() {
        val registry = ConnectionAttemptRegistry<String>()
        var created = 0

        requireNotNull(registry.claim("wss://relay", relayMode = true) { _, _ ->
            created += 1
            "first"
        })
        val duplicate = registry.claim("wss://relay", relayMode = true) { _, _ ->
            created += 1
            "second"
        }

        assertNull(duplicate)
        assertEquals(1, created)
    }
}
