package ai.tetsuo.agenc.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendApiClientPairClaimTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BackendApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BackendApiClient(baseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pair claim needs only CLI code and returns account plus relay credentials`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                        "pairingId":"pr_123",
                        "machineName":"Ada's Mac",
                        "clientTicket":"relay-client-ticket",
                        "relayUrl":"wss://relay.example",
                        "expiresAt":"2026-07-11T12:00:00.000Z",
                        "auth":{
                            "token":"agenc_live_phone",
                            "identity":{
                                "accountId":"acct-1",
                                "displayName":"Ada",
                                "email":"ada@agenc.tech"
                            },
                            "subscriptionTier":"free",
                            "expiresAt":"2027-07-11T12:00:00.000Z"
                        }
                    }""".trimIndent(),
                ),
        )

        val claim = api.pairClaim("ABCD-1234", "AgenC Android")
        val request = server.takeRequest()

        assertEquals("/v1/pair/claim", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"code\":\"ABCD-1234\""))
        assertEquals("pr_123", claim.pairingId)
        assertEquals("relay-client-ticket", claim.clientTicket)
        val inheritedAuth = requireNotNull(claim.auth)
        assertEquals("agenc_live_phone", inheritedAuth.token)
        assertEquals("acct-1", inheritedAuth.identity.accountId)
        assertEquals("free", inheritedAuth.subscriptionTier)
    }

    @Test
    fun `pair claim rejects a response without inherited auth`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"pairingId":"pr_123","clientTicket":"relay-client-ticket"}""",
            ),
        )

        val result = runCatching { api.pairClaim("ABCD-1234", "AgenC Android") }

        assertTrue(result.exceptionOrNull() is BackendApiClient.PairingException)
    }

    @Test
    fun `pair claim preserves the prior bearer-gated contract during backend rollout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                        "pairingId":"pr_legacy",
                        "machineName":"Legacy Mac",
                        "clientTicket":"legacy-client-ticket"
                    }""".trimIndent(),
                ),
        )

        val claim = api.pairClaim(
            "ABCD-1234",
            "AgenC Android",
            legacyToken = "agenc_live_existing_phone",
        )
        val request = server.takeRequest()

        assertEquals("Bearer agenc_live_existing_phone", request.getHeader("Authorization"))
        assertEquals("pr_legacy", claim.pairingId)
        assertNull(claim.auth)
    }

    @Test
    fun `pair claim rejects blank stable account identity`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "pairingId":"pr_123",
                    "clientTicket":"relay-client-ticket",
                    "auth":{
                        "token":"agenc_live_phone",
                        "identity":{"accountId":" "},
                        "subscriptionTier":"free"
                    }
                }""".trimIndent(),
            ),
        )

        val result = runCatching { api.pairClaim("ABCD-1234", "AgenC Android") }

        assertTrue(result.exceptionOrNull() is BackendApiClient.PairingException)
    }
}
