package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.CoreAccountState
import ai.tetsuo.agenc.net.jsonObject
import ai.tetsuo.agenc.net.jv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAccountStateTest {
    @Test
    fun `authoritative unauthenticated whoami is NotLoggedIn`() {
        val parsed = parseCoreAccountState(jsonObject("authenticated" to false.jv()))
        assertTrue(parsed === CoreAccountState.NotLoggedIn)
    }

    @Test
    fun `authenticated whoami preserves exact identity and ignores paid plan`() {
        val parsed = parseCoreAccountState(
            jsonObject(
                "authenticated" to true.jv(),
                "subscriptionTier" to "enterprise".jv(),
                "identity" to jsonObject(
                    "accountId" to "acct-1".jv(),
                    "displayName" to "Core User".jv(),
                    "email" to "core@agenc.tech".jv(),
                    "handle" to "@core".jv(),
                    "plan" to "pro".jv(),
                ),
            ),
        )

        assertTrue(parsed is CoreAccountState.Authenticated)
        val identity = (parsed as CoreAccountState.Authenticated).identity!!
        assertEquals("acct-1", identity.accountId)
        assertEquals("Core User", identity.displayName)
        assertEquals("core@agenc.tech", identity.email)
        assertEquals("@core", identity.handle)
    }

    @Test
    fun `authenticated whoami without verifiable identity is explicit unverified input`() {
        val parsed = parseCoreAccountState(
            jsonObject(
                "authenticated" to true.jv(),
                "identity" to jsonObject("displayName" to "Core User".jv()),
            ),
        )

        assertTrue(parsed is CoreAccountState.Authenticated)
        assertNull((parsed as CoreAccountState.Authenticated).identity)
    }

    @Test
    fun `missing authenticated field is Unavailable not NotLoggedIn`() {
        val parsed = parseCoreAccountState(jsonObject("provider" to "remote".jv()))
        assertTrue(parsed === CoreAccountState.Unavailable)
    }

    @Test
    fun `authenticated response without identity remains distinct from logout`() {
        val parsed = parseCoreAccountState(jsonObject("authenticated" to true.jv()))
        assertTrue(parsed is CoreAccountState.Authenticated)
        assertTrue(parsed !== CoreAccountState.NotLoggedIn)
    }
}
