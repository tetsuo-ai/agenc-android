package ai.tetsuo.agenc.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendApiClientProfileTest {
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
    fun `auth me posts bearer and decodes canonical profile without handle`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"identity":{"accountId":"acct-1","email":"ada@agenc.tech","displayName":"Ada"},"subscriptionTier":"enterprise"}""",
                ),
        )

        val profile = api.fetchProfile("bearer-secret")
        val request = server.takeRequest()

        assertEquals("/v1/auth/me", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals("acct-1", profile.identity.accountId)
        assertEquals("ada@agenc.tech", profile.identity.email)
        assertEquals("Ada", profile.identity.displayName)
    }

    @Test
    fun `auth me maps 401 to authoritative unauthorized error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val result = runCatching { api.fetchProfile("expired") }

        assertTrue(result.exceptionOrNull() is BackendApiClient.UnauthorizedException)
    }

    @Test
    fun `auth me maps 403 to authoritative unauthorized error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))

        val result = runCatching { api.fetchProfile("revoked") }

        assertTrue(result.exceptionOrNull() is BackendApiClient.UnauthorizedException)
    }

    @Test
    fun `auth me rejects missing or blank stable account id`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"identity":{"email":"ada@agenc.tech","displayName":"Ada"},"subscriptionTier":"free"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"identity":{"accountId":" ","email":"ada@agenc.tech"},"subscriptionTier":"free"}""",
            ),
        )

        val missing = runCatching { api.fetchProfile("valid") }
        val blank = runCatching { api.fetchProfile("valid") }

        assertTrue(missing.isFailure)
        assertTrue(blank.exceptionOrNull() is BackendApiClient.AuthException)
    }
}
