package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityLinkTest {
    @Test
    fun `matching stable account ids link runtime and preserve verified phone tier`() {
        val linked = linkedAccountIdentity(
            phone = identity("phone@agenc.tech", "acct-1", Tier.pro, tierVerified = true),
            runtime = identity("phone@agenc.tech", "acct-1", Tier.free, tierVerified = false),
        )!!

        assertTrue(linked.runtimeLinked)
        assertFalse(linked.runtimeMismatch)
        assertEquals("acct-1", linked.accountId)
        assertEquals(Tier.pro, linked.tier)
        assertTrue(linked.tierVerified)
    }

    @Test
    fun `different stable account ids never replace phone identity`() {
        val linked = linkedAccountIdentity(
            phone = identity("same@agenc.tech", "acct-phone", Tier.pro),
            runtime = identity("same@agenc.tech", "acct-core", Tier.team),
        )!!

        assertFalse(linked.runtimeLinked)
        assertTrue(linked.runtimeMismatch)
        assertEquals("acct-phone", linked.accountId)
        assertEquals(Tier.pro, linked.tier)
    }

    @Test
    fun `legacy phone without account id links by exact normalized email`() {
        val linked = linkedAccountIdentity(
            phone = identity(" User@Example.com ", null, Tier.free),
            runtime = identity("user@example.com", "acct-runtime", Tier.pro),
        )!!

        assertTrue(linked.runtimeLinked)
        assertEquals("acct-runtime", linked.accountId)
        assertEquals(Tier.pro, linked.tier)
    }

    @Test
    fun `runtime logout falls back to phone identity without stale link state`() {
        val phone = identity("phone@agenc.tech", "acct-1", Tier.pro).copy(runtimeLinked = true)
        val result = linkedAccountIdentity(phone, runtime = null)!!

        assertFalse(result.runtimeLinked)
        assertFalse(result.runtimeMismatch)
        assertEquals("acct-1", result.accountId)
    }

    private fun identity(
        handle: String,
        accountId: String?,
        tier: Tier,
        tierVerified: Boolean = true,
    ) = Identity(
        displayName = handle.substringBefore('@').trim(),
        handle = handle,
        tier = tier,
        accountId = accountId,
        tierVerified = tierVerified,
    )
}
