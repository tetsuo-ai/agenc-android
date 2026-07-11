package ai.tetsuo.agenc.ui.screens

import ai.tetsuo.agenc.model.CoreAccountLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountSectionsTest {
    @Test
    fun `current account plan is Free only`() {
        assertEquals("FREE", CURRENT_ACCOUNT_PLAN_LABEL)
    }

    @Test
    fun `production order places Connected Core directly below AgenC account`() {
        assertEquals(
            listOf(
                AccountSection.AgenCAccount,
                AccountSection.ConnectedCore,
                AccountSection.Notifications,
                AccountSection.Delivery,
                AccountSection.HardwareWallet,
                AccountSection.Security,
            ),
            accountSections(hasNotificationSettings = true, hasHardwareWallet = true),
        )
    }

    @Test
    fun `optional sections disappear without breaking the required account order`() {
        assertEquals(
            listOf(
                AccountSection.AgenCAccount,
                AccountSection.ConnectedCore,
                AccountSection.Security,
            ),
            accountSections(hasNotificationSettings = false, hasHardwareWallet = false),
        )
    }

    @Test
    fun `Unavailable is never presented as Core logout`() {
        assertEquals("UNAVAILABLE", coreAccountStatusLabel(CoreAccountLinkState.Unavailable))
        assertEquals("NOT LOGGED IN", coreAccountStatusLabel(CoreAccountLinkState.NotLoggedIn))
        assertNotEquals(
            coreAccountStatusLabel(CoreAccountLinkState.Unavailable),
            coreAccountStatusLabel(CoreAccountLinkState.NotLoggedIn),
        )
    }
}
