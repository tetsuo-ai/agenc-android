package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.net.BackendApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingClaimIdentityTest {
    @Test
    fun `pairing claim projects exact CLI account identity`() {
        val identity = pairingClaimIdentity(
            BackendApiClient.PairingAuthDTO(
                token = "agenc_live_phone",
                identity = BackendApiClient.AuthIdentityDTO(
                    accountId = "acct-cli",
                    displayName = "Ada",
                    email = "ada@agenc.tech",
                ),
            ),
        )

        assertEquals("acct-cli", identity.accountId)
        assertEquals("Ada", identity.displayName)
        assertEquals("ada@agenc.tech", identity.email)
        assertNull(identity.handle)
    }

    @Test
    fun `pairing claim cannot publish identity without token and account id`() {
        val missingToken = runCatching {
            pairingClaimIdentity(
                BackendApiClient.PairingAuthDTO(
                    token = " ",
                    identity = BackendApiClient.AuthIdentityDTO(accountId = "acct-cli"),
                ),
            )
        }
        val missingAccount = runCatching {
            pairingClaimIdentity(
                BackendApiClient.PairingAuthDTO(
                    token = "agenc_live_phone",
                    identity = BackendApiClient.AuthIdentityDTO(accountId = null),
                ),
            )
        }

        assertTrue(missingToken.isFailure)
        assertTrue(missingAccount.isFailure)
    }
}
