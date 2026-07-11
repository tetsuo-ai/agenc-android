package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.net.BackendApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalAccountIdentityTest {
    @Test
    fun `restore never presents the legacy sample account as a real identity`() {
        val restored = restoredAccountIdentity(
            displayName = "User",
            email = "user@example.com",
            accountId = null,
            handle = null,
        )

        assertEquals("", restored.displayName)
        assertEquals("", restored.email)
        assertNull(restored.accountId)
        assertNull(restored.handle)
    }

    @Test
    fun `restore preserves a real cached account while canonical refresh is unavailable`() {
        val restored = restoredAccountIdentity(
            displayName = "Ada Lovelace",
            email = "ada@agenc.tech",
            accountId = "acct-ada",
            handle = null,
        )

        assertEquals("Ada Lovelace", restored.displayName)
        assertEquals("ada@agenc.tech", restored.email)
        assertEquals("acct-ada", restored.accountId)
    }

    @Test
    fun `auth me replaces the legacy placeholder with exact backend fields`() {
        val result = canonicalAccountIdentity(
            cached = Identity(
                displayName = "User",
                email = "user@example.com",
                accountId = null,
                handle = "@legacy",
            ),
            profile = BackendApiClient.CanonicalIdentityDTO(
                accountId = "acct-real",
                displayName = "Ada Lovelace",
                email = "ada@agenc.tech",
            ),
        )

        assertEquals("Ada Lovelace", result.displayName)
        assertEquals("ada@agenc.tech", result.email)
        assertEquals("acct-real", result.accountId)
        assertNull(result.handle)
    }

    @Test
    fun `partial canonical profile keeps usable cached copy without inventing a handle`() {
        val result = canonicalAccountIdentity(
            cached = Identity(
                displayName = "Cached name",
                email = "cached@agenc.tech",
                accountId = "old-id",
                handle = "@cached",
            ),
            profile = BackendApiClient.CanonicalIdentityDTO(accountId = "acct-canonical"),
        )

        assertEquals("Cached name", result.displayName)
        assertEquals("cached@agenc.tech", result.email)
        assertEquals("acct-canonical", result.accountId)
        assertNull(result.handle)
    }

    @Test
    fun `blank canonical account id is rejected before it can replace stable cache`() {
        val cached = Identity("Cached", "cached@agenc.tech", "acct-stable")

        val result = runCatching {
            canonicalAccountIdentity(
                cached = cached,
                profile = BackendApiClient.CanonicalIdentityDTO(accountId = " "),
            )
        }

        assertTrue(result.isFailure)
        assertEquals("acct-stable", cached.accountId)
    }
}
