package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.CoreAccountLinkState
import ai.tetsuo.agenc.model.CoreAccountState
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.ui.screens.coreAccountStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityLinkTest {
    @Test
    fun `matching stable ids link while preserving canonical phone profile`() {
        val phone = identity("Phone profile", "phone@agenc.tech", "acct-1")
        val runtime = identity("Core profile", "different-label@agenc.tech", "acct-1")

        val result = reconcileAccountIdentity(
            phone = phone,
            core = CoreAccountState.Authenticated(runtime),
        )!!

        assertSame(phone, result.phone)
        assertTrue(result.core is CoreAccountLinkState.Linked)
        assertEquals(runtime, (result.core as CoreAccountLinkState.Linked).core)
    }

    @Test
    fun `conflicting stable ids mismatch even when emails match`() {
        val result = reconcileAccountIdentity(
            phone = identity("Phone", "same@agenc.tech", "acct-phone"),
            core = CoreAccountState.Authenticated(
                identity("Core", "same@agenc.tech", "acct-core"),
            ),
        )!!

        assertTrue(result.core is CoreAccountLinkState.Mismatch)
    }

    @Test
    fun `legacy identities without both ids link by exact normalized email`() {
        val result = reconcileAccountIdentity(
            phone = identity("Phone", " User@Example.com ", null),
            core = CoreAccountState.Authenticated(
                identity("Core", "user@example.com", "acct-runtime"),
            ),
        )!!

        assertTrue(result.core is CoreAccountLinkState.Linked)
    }

    @Test
    fun `different legacy emails fail closed as mismatch`() {
        val result = reconcileAccountIdentity(
            phone = identity("Phone", "one@example.com", null),
            core = CoreAccountState.Authenticated(
                identity("Core", "two@example.com", null),
            ),
        )!!

        assertTrue(result.core is CoreAccountLinkState.Mismatch)
    }

    @Test
    fun `Core not logged in remains distinct from transport states`() {
        val phone = identity("Phone", "phone@agenc.tech", "acct-1")

        assertTrue(
            reconcileAccountIdentity(phone, CoreAccountState.NotLoggedIn)?.core ===
                CoreAccountLinkState.NotLoggedIn,
        )
        assertTrue(
            reconcileAccountIdentity(phone, CoreAccountState.Offline)?.core ===
                CoreAccountLinkState.Offline,
        )
        assertTrue(
            reconcileAccountIdentity(phone, CoreAccountState.Unavailable)?.core ===
                CoreAccountLinkState.Unavailable,
        )
    }

    @Test
    fun `network failure Unavailable is never labelled Not Logged In`() {
        assertEquals("UNAVAILABLE", coreAccountStatusLabel(CoreAccountLinkState.Unavailable))
        assertTrue(
            coreAccountStatusLabel(CoreAccountLinkState.Unavailable) !=
                coreAccountStatusLabel(CoreAccountLinkState.NotLoggedIn),
        )
    }

    @Test
    fun `authenticated Core without identity is unverified`() {
        val result = reconcileAccountIdentity(
            phone = identity("Phone", "phone@agenc.tech", "acct-1"),
            core = CoreAccountState.Authenticated(identity = null),
        )!!

        assertTrue(result.core === CoreAccountLinkState.Unverified)
    }

    @Test
    fun `runtime identity is never adopted without canonical phone login`() {
        assertNull(
            reconcileAccountIdentity(
                phone = null,
                core = CoreAccountState.Authenticated(
                    identity("Core", "core@agenc.tech", "acct-core"),
                ),
            ),
        )
    }

    private fun identity(displayName: String, email: String, accountId: String?) = Identity(
        displayName = displayName,
        email = email,
        accountId = accountId,
    )
}
