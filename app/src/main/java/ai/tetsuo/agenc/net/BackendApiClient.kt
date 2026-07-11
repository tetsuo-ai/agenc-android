package ai.tetsuo.agenc.net

import ai.tetsuo.agenc.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct port of the iOS app's BackendAPIClient.swift.
 *
 * REST client for the hosted backend. Android authenticates by redeeming the one-time pairing code
 * created by the already-authenticated Core CLI; it never starts its own browser/OAuth login.
 * All endpoints POST JSON; Bearer auth where noted. Suspending functions run the blocking OkHttp
 * call on [Dispatchers.IO].
 */
class BackendApiClient(
    private val baseUrl: String = AppConfig.BACKEND_BASE,
    private val client: OkHttpClient = JsonRpcConnection.sharedClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    // MARK: - DTOs (mirror the Swift Decodable structs)

    @Serializable
    data class AuthIdentityDTO(
        val accountId: String? = null,
        val displayName: String? = null,
        val handle: String? = null,
        val email: String? = null,
    )

    /** Canonical hosted account profile. `/v1/auth/me` intentionally exposes no handle field. */
    @Serializable
    data class CanonicalIdentityDTO(
        val accountId: String,
        val displayName: String? = null,
        val email: String? = null,
    )

    @Serializable
    data class AuthProfileDTO(
        val identity: CanonicalIdentityDTO,
        val subscriptionTier: String = "free",
    )

    @Serializable
    data class RelayTicketDTO(
        val ticket: String,
        val accountId: String? = null,
        val relayUrl: String? = null,
        val expiresAt: String? = null,
    )

    @Serializable
    data class PairingDTO(
        val pairingId: String,
        val machineName: String? = null,
        val clientTicket: String,
        val relayUrl: String? = null,
        val expiresAt: String? = null,
    )

    /** Account session inherited from the authenticated CLI that created the pairing code. */
    @Serializable
    data class PairingAuthDTO(
        val token: String,
        val identity: AuthIdentityDTO,
        val subscriptionTier: String = "free",
        val expiresAt: String? = null,
    )

    /** Atomic pairing claim: account session + relay-room credential arrive in one response. */
    @Serializable
    data class PairClaimDTO(
        val pairingId: String,
        val machineName: String? = null,
        val clientTicket: String,
        val relayUrl: String? = null,
        val expiresAt: String? = null,
        val auth: PairingAuthDTO? = null,
    )

    /** A pairing failure with a human-readable reason (e.g. wrong/expired code). */
    class PairingException(message: String) : Exception(message)

    /** Auth failure (non-200 on a Bearer-protected endpoint). */
    class AuthException(message: String = "authentication required") : Exception(message)

    /** The bearer is no longer valid; callers must not continue as an authenticated account. */
    class UnauthorizedException : Exception("authentication expired")

    // MARK: - Endpoints

    suspend fun logout(token: String) {
        val (_, status) = post(AppConfig.Path.LOGOUT, emptyMap(), token)
        if (status !in 200..299) {
            throw AuthException("Remote logout failed (error $status).")
        }
    }

    /** Fetch the canonical hosted profile after restore/login without trusting stale login copy. */
    suspend fun fetchProfile(token: String): AuthProfileDTO {
        val (data, status) = post(AppConfig.Path.AUTH_ME, emptyMap(), token)
        if (status == 401 || status == 403) throw UnauthorizedException()
        if (status != 200) throw AuthException("Profile refresh failed (error $status).")
        val profile = json.decodeFromString(AuthProfileDTO.serializer(), data)
        if (profile.identity.accountId.isBlank()) {
            throw AuthException("Profile refresh returned an invalid account id.")
        }
        return profile
    }

    /**
     * Mint a signed relay CLIENT ticket for the signed-in account. Bearer-authenticated; the phone
     * never holds the shared signing secret.
     */
    suspend fun fetchRelayTicket(token: String): RelayTicketDTO {
        val (data, status) = post(AppConfig.Path.RELAY_TICKET, emptyMap(), token)
        if (status != 200) throw AuthException()
        return json.decodeFromString(RelayTicketDTO.serializer(), data)
    }

    /**
     * Redeem the one-time code shown by `agenc remote on`. The code is the phone's only onboarding
     * credential: the backend binds it to the account that authenticated `/v1/pair/start`, then
     * returns that account session together with the pairing's relay ticket.
     */
    suspend fun pairClaim(
        code: String,
        appLabel: String,
        legacyToken: String? = null,
    ): PairClaimDTO {
        val (data, status) = post(
            AppConfig.Path.PAIR_CLAIM,
            mapOf("code" to code, "appLabel" to appLabel),
            legacyToken,
        )
        if (status != 200) throw pairingError(data, status)
        val claim = runCatching { json.decodeFromString(PairClaimDTO.serializer(), data) }
            .getOrElse { throw PairingException("The pairing service returned an invalid response.") }
        val inheritedAuthIsInvalid = claim.auth?.let {
            it.token.isBlank() || it.identity.accountId.isNullOrBlank()
        } ?: legacyToken.isNullOrBlank()
        if (claim.pairingId.isBlank() || claim.clientTicket.isBlank() || inheritedAuthIsInvalid) {
            throw PairingException("The pairing service returned an invalid response.")
        }
        return claim
    }

    /** Re-mint the pairing's short-lived client ticket on (re)connect — no re-pairing. */
    suspend fun pairClientTicket(pairingId: String, token: String): PairingDTO {
        val (data, status) = post(
            AppConfig.Path.PAIR_CLIENT_TICKET,
            mapOf("pairingId" to pairingId),
            token,
        )
        if (status != 200) throw pairingError(data, status)
        return json.decodeFromString(PairingDTO.serializer(), data)
    }

    /** Unpair this phone from a computer. The caller decides whether failure is best-effort. */
    suspend fun pairRevoke(pairingId: String, token: String) {
        val (_, status) = post(
            AppConfig.Path.PAIR_REVOKE,
            mapOf("pairingId" to pairingId),
            token,
        )
        if (status !in 200..299) {
            throw PairingException(
                if (status == 404) "This pairing is already unavailable."
                else "Couldn't unlink this computer (error $status).",
            )
        }
    }

    // MARK: - helpers

    private fun pairingError(data: String, status: Int): PairingException {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        val msg = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        if (msg != null) return PairingException(msg)
        return PairingException(
            if (status == 404) "That code is invalid or already used."
            else "Couldn't link this computer (error $status).",
        )
    }

    /** POST a flat string map as JSON and return (body, statusCode). */
    private suspend fun post(
        path: String,
        body: Map<String, String>,
        token: String? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val payload: JsonObject = buildJsonObject {
            body.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
            .toRequestBody(jsonMedia)
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(requestBody)
            .header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            text to resp.code
        }
    }
}
