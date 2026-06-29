package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.net.BackendApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Direct port of the iOS app's AuthManager.swift — the device-flow sign-in state machine.
 *
 * Holds the opaque bearer token in [SecureStore], caches the identity for cold-start, and drives
 * the poll loop. On success it stores the token + identity, then fetches and stores a relay ticket
 * (account ticket, or the pairing's client ticket when a Mac is linked).
 *
 * Observable state for the UI:
 *  - [state] — the sign-in state machine ([State.SignedOut] / [State.Polling] / [State.SignedIn])
 *  - [lastError] — a human-readable reason the last attempt failed
 *  - [relayTicketVersion] — bumps whenever a fresh relay ticket is cached, so the connection layer
 *    can reconnect the remote host
 *  - [pairedMachine] — the linked computer's name, if any
 */
class AuthManager(
    private val secure: SecureStore,
    private val prefs: Prefs,
    private val api: BackendApiClient = BackendApiClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    sealed interface State {
        data object SignedOut : State
        data class Polling(val userCode: String?, val verificationUri: String?) : State
        data class SignedIn(val identity: Identity) : State
    }

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _relayTicketVersion = MutableStateFlow(0)
    val relayTicketVersion: StateFlow<Int> = _relayTicketVersion.asStateFlow()

    private val _pairedMachine = MutableStateFlow<String?>(null)
    val pairedMachine: StateFlow<String?> = _pairedMachine.asStateFlow()

    private val pairings = PairingStore(prefs)
    private var loginJob: Job? = null

    val isSignedIn: Boolean get() = _state.value is State.SignedIn
    val identity: Identity? get() = (_state.value as? State.SignedIn)?.identity
    val bearer: String? get() = secure.get(SecureStore.KEY_BEARER)

    /** Call once on launch to restore the session (mirrors the iOS init). */
    fun restore() {
        _pairedMachine.value = pairings.current?.machineName
        if (secure.get(SecureStore.KEY_BEARER) != null) {
            _state.value = State.SignedIn(
                Identity(
                    displayName = prefs.getString(Prefs.KEY_IDENTITY_NAME) ?: "Account",
                    handle = prefs.getString(Prefs.KEY_IDENTITY_HANDLE) ?: "",
                    tier = tierFrom(prefs.getString(Prefs.KEY_IDENTITY_TIER)),
                ),
            )
            scope.launch { refreshRelayTicket() } // keep the cached relay ticket fresh
        }
    }

    fun startLogin(identifier: String? = null) {
        if (isSignedIn) return
        _lastError.value = null
        loginJob?.cancel()
        loginJob = scope.launch { runLogin(identifier) }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _state.value = State.SignedOut
    }

    /**
     * Sign out AND drop any computer pairing, so logging out can't silently leave a stale remote
     * link behind.
     */
    fun signOut() {
        loginJob?.cancel()
        bearer?.let { token -> scope.launch { api.logout(token) } }
        secure.delete(SecureStore.KEY_BEARER)
        unlinkMac() // clear PairingStore + relay ticket and fall back to the local host
        _state.value = State.SignedOut
    }

    // MARK: - device flow

    private suspend fun runLogin(identifier: String?) {
        try {
            val prompt = api.loginStart(identifier)
            _state.value = State.Polling(prompt.userCode, prompt.verificationUri)
            poll(prompt.deviceCode, prompt.intervalSeconds ?: 2)
        } catch (_: Throwable) {
            _lastError.value = "Couldn't start sign-in. Check your connection and try again."
            _state.value = State.SignedOut
        }
    }

    private suspend fun poll(deviceCode: String, interval: Int) {
        var delaySeconds = maxOf(1, interval)
        while (scope.isActive) {
            delay(delaySeconds * 1000L)
            val outcome = runCatching { api.loginPoll(deviceCode) }
                .getOrDefault(BackendApiClient.PollOutcome.Terminal("error"))
            when (outcome) {
                is BackendApiClient.PollOutcome.Success -> {
                    persist(outcome.body)
                    refreshRelayTicket(triggerReconnect = true)
                    return
                }
                is BackendApiClient.PollOutcome.Pending ->
                    outcome.intervalSeconds?.let { delaySeconds = it }
                is BackendApiClient.PollOutcome.SlowDown ->
                    delaySeconds = outcome.intervalSeconds ?: (interval + 5)
                is BackendApiClient.PollOutcome.Terminal -> {
                    _lastError.value = loginErrorMessage(outcome.reason)
                    _state.value = State.SignedOut
                    return
                }
            }
        }
    }

    private fun persist(success: BackendApiClient.LoginSuccess) {
        secure.set(success.token, SecureStore.KEY_BEARER)
        val identity = Identity(
            displayName = success.identity.displayName ?: "Account",
            handle = success.identity.handle ?: success.identity.email ?: "",
            tier = tierFrom(success.subscriptionTier),
        )
        prefs.setString(Prefs.KEY_IDENTITY_NAME, identity.displayName)
        prefs.setString(Prefs.KEY_IDENTITY_HANDLE, identity.handle)
        prefs.setString(Prefs.KEY_IDENTITY_TIER, identity.tier.name)
        _state.value = State.SignedIn(identity)
    }

    /**
     * Fetch + cache a signed relay CLIENT ticket for the signed-in account. A linked computer
     * re-mints the PAIRING's client ticket (its own isolated relay room); otherwise fall back to
     * the legacy account ticket. Both land in the same SecureStore slot the connection reads.
     */
    suspend fun refreshRelayTicket(triggerReconnect: Boolean = false) {
        val token = bearer ?: return
        val pairing = pairings.current
        if (pairing != null) {
            val dto = runCatching { api.pairClientTicket(pairing.pairingId, token) }.getOrNull() ?: return
            secure.set(dto.clientTicket, SecureStore.KEY_RELAY_TICKET)
            dto.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
        } else {
            val dto = runCatching { api.fetchRelayTicket(token) }.getOrNull() ?: return
            secure.set(dto.ticket, SecureStore.KEY_RELAY_TICKET)
            dto.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
        }
        if (triggerReconnect) _relayTicketVersion.value = _relayTicketVersion.value + 1
    }

    /** The cached relay client ticket, if any (the connection layer dials the relay with it). */
    fun relayTicket(): String? = secure.get(SecureStore.KEY_RELAY_TICKET)

    /** The relay URL the backend last reported (overrides the default RELAY_BASE when present). */
    fun relayUrl(): String? = prefs.getString(Prefs.KEY_RELAY_URL)

    // MARK: - device pairing

    /**
     * Redeem a pairing code shown on a computer (`agenc remote on`). On success the pairing's
     * client ticket replaces the relay ticket and the remote host is selected. Returns the linked
     * machine's name, or a failure with a user-facing message.
     */
    suspend fun linkMac(code: String, appLabel: String): Result<String> {
        val token = bearer ?: return Result.failure(
            BackendApiClient.PairingException("Sign in first."),
        )
        return try {
            val dto = api.pairClaim(code, appLabel, token)
            val machine = dto.machineName ?: "Computer"
            val relayUrl = dto.relayUrl ?: (prefs.getString(Prefs.KEY_RELAY_URL) ?: "")
            pairings.set(PairingStore.Pairing(dto.pairingId, machine, relayUrl))
            _pairedMachine.value = machine
            secure.set(dto.clientTicket, SecureStore.KEY_RELAY_TICKET)
            dto.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
            _relayTicketVersion.value = _relayTicketVersion.value + 1
            Result.success(machine)
        } catch (e: BackendApiClient.PairingException) {
            Result.failure(e)
        } catch (_: Throwable) {
            Result.failure(
                BackendApiClient.PairingException(
                    "Couldn't link. Check the code and your connection.",
                ),
            )
        }
    }

    /** Unlink this phone from its computer (best-effort server revoke + local clear). */
    fun unlinkMac() {
        val pairing = pairings.current
        val token = bearer
        if (pairing != null && token != null) {
            scope.launch { api.pairRevoke(pairing.pairingId, token) }
        }
        pairings.clear()
        _pairedMachine.value = null
        secure.delete(SecureStore.KEY_RELAY_TICKET)
        _relayTicketVersion.value = _relayTicketVersion.value + 1
    }

    // MARK: - helpers

    private fun tierFrom(raw: String?): Tier =
        Tier.entries.firstOrNull { it.name == raw } ?: Tier.free

    private fun loginErrorMessage(reason: String): String = when (reason) {
        "access_denied", "denied" -> "Sign-in was denied. Tap Sign in to try again."
        "expired_token", "expired" -> "The sign-in request expired. Tap Sign in to try again."
        else -> "Sign-in didn't complete. Check your connection and try again."
    }
}
