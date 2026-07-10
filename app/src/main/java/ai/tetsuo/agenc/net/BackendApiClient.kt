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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct port of the iOS app's BackendAPIClient.swift.
 *
 * REST client for the hosted backend (OAuth 2.0 device authorization grant + pairing), talking to
 * the SAME backend the iOS app uses ([AppConfig.BACKEND_BASE]). All endpoints POST JSON; Bearer
 * auth where noted. Suspending functions run the blocking OkHttp call on [Dispatchers.IO].
 */
class BackendApiClient(
    private val baseUrl: String = AppConfig.BACKEND_BASE,
    private val client: OkHttpClient = JsonRpcConnection.sharedClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    // MARK: - DTOs (mirror the Swift Decodable structs)

    @Serializable
    data class DeviceCodePrompt(
        val deviceCode: String,
        val userCode: String? = null,
        val verificationUri: String? = null,
        val intervalSeconds: Int? = null,
        val expiresInSeconds: Int? = null,
    )

    @Serializable
    data class AuthIdentityDTO(
        val accountId: String? = null,
        val displayName: String? = null,
        val handle: String? = null,
        val email: String? = null,
    )

    @Serializable
    data class LoginSuccess(
        val token: String,
        val identity: AuthIdentityDTO,
        val subscriptionTier: String,
        val expiresAt: String? = null,
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

    /** A pairing failure with a human-readable reason (e.g. wrong/expired code). */
    class PairingException(message: String) : Exception(message)

    /** Auth failure (non-200 on a Bearer-protected endpoint). */
    class AuthException(message: String = "authentication required") : Exception(message)

    /** Device-flow poll outcome (mirrors the Swift PollOutcome enum). */
    sealed interface PollOutcome {
        data class Pending(val intervalSeconds: Int?) : PollOutcome
        data class SlowDown(val intervalSeconds: Int?) : PollOutcome
        data class Success(val body: LoginSuccess) : PollOutcome
        data class Terminal(val reason: String) : PollOutcome
    }

    // MARK: - Endpoints

    suspend fun loginStart(identifier: String?): DeviceCodePrompt {
        val body = if (identifier != null) mapOf("identifier" to identifier) else emptyMap()
        val (data, _) = post(AppConfig.Path.LOGIN_START, body)
        return json.decodeFromString(DeviceCodePrompt.serializer(), data)
    }

    suspend fun loginPoll(deviceCode: String): PollOutcome {
        val (data, status) = post(AppConfig.Path.LOGIN_POLL, mapOf("deviceCode" to deviceCode))
        if (status == 200) {
            runCatching { json.decodeFromString(LoginSuccess.serializer(), data) }
                .getOrNull()?.let { return PollOutcome.Success(it) }
        }
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        return when (val statusField = obj?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "pending" -> PollOutcome.Pending(obj.intervalSeconds())
            "slow_down" -> PollOutcome.SlowDown(obj.intervalSeconds())
            null -> PollOutcome.Terminal("error")
            else -> PollOutcome.Terminal(statusField)
        }
    }

    suspend fun logout(token: String) {
        val (_, status) = post(AppConfig.Path.LOGOUT, emptyMap(), token)
        if (status !in 200..299) {
            throw AuthException("Remote logout failed (error $status).")
        }
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
     * Redeem a pairing code shown on a computer (`agenc remote on`). Bearer-authenticated; returns
     * the pairing's client ticket so the app can dial that host's isolated relay room.
     */
    suspend fun pairClaim(code: String, appLabel: String, token: String): PairingDTO {
        val (data, status) = post(
            AppConfig.Path.PAIR_CLAIM,
            mapOf("code" to code, "appLabel" to appLabel),
            token,
        )
        if (status != 200) throw pairingError(data, status)
        return json.decodeFromString(PairingDTO.serializer(), data)
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

    private fun JsonObject.intervalSeconds(): Int? =
        (this["intervalSeconds"] as? JsonPrimitive)?.intOrNull

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
