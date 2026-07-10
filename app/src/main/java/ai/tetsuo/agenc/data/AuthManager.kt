package ai.tetsuo.agenc.data

import android.util.Log
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.net.BackendApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    private val authLock = Any()
    private var authGeneration = 0L
    private var loginJob: Job? = null
    private var relayRefreshJob: Job? = null

    val isSignedIn: Boolean get() = _state.value is State.SignedIn
    val identity: Identity? get() = (_state.value as? State.SignedIn)?.identity
    val bearer: String? get() = secure.get(SecureStore.KEY_BEARER)
    /** Stable owner for pending Ledger request/receipt correlation; never a display name. */
    fun ledgerHostBinding(): String = pairings.current?.pairingId ?: LOCAL_LEDGER_HOST_BINDING

    /** Call once on launch to restore the session (mirrors the iOS init). */
    fun restore() {
        var previousLogin: Job? = null
        var previousRefresh: Job? = null
        var restoredGeneration: Long? = null
        synchronized(authLock) {
            authGeneration += 1
            previousLogin = loginJob
            previousRefresh = relayRefreshJob
            loginJob = null
            relayRefreshJob = null

            _pairedMachine.value = pairings.current?.machineName
            if (secure.get(SecureStore.KEY_BEARER) != null) {
                val cachedTier = prefs.getString(Prefs.KEY_IDENTITY_TIER)
                _state.value = State.SignedIn(
                    Identity(
                        displayName = prefs.getString(Prefs.KEY_IDENTITY_NAME) ?: "Account",
                        handle = prefs.getString(Prefs.KEY_IDENTITY_HANDLE) ?: "",
                        tier = tierFrom(cachedTier),
                        accountId = prefs.getString(Prefs.KEY_IDENTITY_ACCOUNT_ID),
                        tierVerified = cachedTier != null,
                    ),
                )
                restoredGeneration = authGeneration
            } else {
                _state.value = State.SignedOut
            }
        }
        previousLogin?.cancel()
        previousRefresh?.cancel()

        // A cached ticket may be near expiry. Re-mint it and notify the connection layer when the
        // fresh credential is safely committed so a cold-started app does not keep the stale link.
        restoredGeneration?.let { scheduleRelayRefresh(triggerReconnect = true, it) }
    }

    fun startLogin(identifier: String? = null) {
        var previousLogin: Job? = null
        var previousRefresh: Job? = null
        var generation: Long? = null
        synchronized(authLock) {
            if (_state.value is State.SignedIn) return
            authGeneration += 1
            generation = authGeneration
            previousLogin = loginJob
            previousRefresh = relayRefreshJob
            loginJob = null
            relayRefreshJob = null
            _lastError.value = null
        }
        previousLogin?.cancel()
        previousRefresh?.cancel()

        val expectedGeneration = generation ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runLogin(identifier, expectedGeneration)
        }
        val shouldStart = synchronized(authLock) {
            if (authGeneration != expectedGeneration || _state.value is State.SignedIn) {
                false
            } else {
                loginJob = job
                true
            }
        }
        job.invokeOnCompletion {
            synchronized(authLock) {
                if (loginJob === job) loginJob = null
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    fun cancelLogin() {
        val job = synchronized(authLock) {
            if (_state.value is State.SignedIn) return
            authGeneration += 1
            loginJob.also {
                loginJob = null
                _state.value = State.SignedOut
            }
        }
        job?.cancel()
    }

    /**
     * Sign out AND drop any computer pairing, so logging out can't silently leave a stale remote
     * link behind.
     */
    fun signOut() {
        var loginToCancel: Job? = null
        var refreshToCancel: Job? = null
        var token: String? = null
        var pairing: PairingStore.Pairing? = null
        var signedOutGeneration = 0L

        // Capture remote credentials, invalidate every in-flight auth operation, and clear local
        // state as one atomic transition. Network revocation deliberately happens afterwards.
        synchronized(authLock) {
            token = secure.get(SecureStore.KEY_BEARER)
            pairing = pairings.current
            authGeneration += 1
            signedOutGeneration = authGeneration
            loginToCancel = loginJob
            refreshToCancel = relayRefreshJob
            loginJob = null
            relayRefreshJob = null

            secure.delete(SecureStore.KEY_BEARER)
            secure.delete(SecureStore.KEY_RELAY_TICKET)
            pairings.clear()
            prefs.remove(Prefs.KEY_RELAY_URL)
            prefs.remove(Prefs.KEY_IDENTITY_NAME)
            prefs.remove(Prefs.KEY_IDENTITY_HANDLE)
            prefs.remove(Prefs.KEY_IDENTITY_TIER)
            prefs.remove(Prefs.KEY_IDENTITY_ACCOUNT_ID)
            _pairedMachine.value = null
            _lastError.value = null
            _state.value = State.SignedOut
        }
        loginToCancel?.cancel()
        refreshToCancel?.cancel()

        // Revoke the pairing before logout because logout may invalidate the bearer. Each call is
        // independently best-effort so one failure never prevents the other or delays local exit.
        token?.let { capturedToken ->
            val capturedPairing = pairing
            scope.launch {
                var remoteFailure = false
                if (capturedPairing != null) {
                    try {
                        api.pairRevoke(capturedPairing!!.pairingId, capturedToken)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        remoteFailure = true
                        logRemoteFailure("Pairing revoke during sign-out", error)
                    }
                }
                try {
                    api.logout(capturedToken)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    remoteFailure = true
                    logRemoteFailure("Remote logout", error)
                }
                if (remoteFailure) {
                    reportRemoteFailure(
                        signedOutGeneration,
                        requireSignedOut = true,
                        "Signed out on this device, but the server couldn't confirm every " +
                            "remote revocation. Check your connection before signing in again.",
                    )
                }
            }
        }
    }

    // MARK: - device flow

    private suspend fun runLogin(identifier: String?, expectedGeneration: Long) {
        try {
            val prompt = api.loginStart(identifier)
            val stillCurrent = synchronized(authLock) {
                if (authGeneration != expectedGeneration) {
                    false
                } else {
                    _state.value = State.Polling(prompt.userCode, prompt.verificationUri)
                    true
                }
            }
            if (stillCurrent) {
                poll(prompt.deviceCode, prompt.intervalSeconds ?: 2, expectedGeneration)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            synchronized(authLock) {
                if (authGeneration == expectedGeneration) {
                    _lastError.value = "Couldn't start sign-in. Check your connection and try again."
                    _state.value = State.SignedOut
                }
            }
        }
    }

    private suspend fun poll(deviceCode: String, interval: Int, expectedGeneration: Long) {
        var delaySeconds = maxOf(1, interval)
        while (currentCoroutineContext().isActive) {
            delay(delaySeconds * 1000L)
            val outcome = try {
                api.loginPoll(deviceCode)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                BackendApiClient.PollOutcome.Terminal("error")
            }
            when (outcome) {
                is BackendApiClient.PollOutcome.Success -> {
                    if (persist(outcome.body, expectedGeneration)) {
                        refreshRelayTicketFor(expectedGeneration, triggerReconnect = true)
                    }
                    return
                }
                is BackendApiClient.PollOutcome.Pending ->
                    outcome.intervalSeconds?.let { delaySeconds = it }
                is BackendApiClient.PollOutcome.SlowDown ->
                    delaySeconds = outcome.intervalSeconds ?: (interval + 5)
                is BackendApiClient.PollOutcome.Terminal -> {
                    synchronized(authLock) {
                        if (authGeneration == expectedGeneration) {
                            _lastError.value = loginErrorMessage(outcome.reason)
                            _state.value = State.SignedOut
                        }
                    }
                    return
                }
            }
        }
    }

    private fun persist(success: BackendApiClient.LoginSuccess, expectedGeneration: Long): Boolean {
        val identity = Identity(
            displayName = success.identity.displayName ?: "Account",
            handle = success.identity.handle ?: success.identity.email ?: "",
            tier = tierFrom(success.subscriptionTier),
            accountId = success.identity.accountId?.takeIf { it.isNotBlank() },
        )
        return synchronized(authLock) {
            if (authGeneration != expectedGeneration) {
                false
            } else {
                secure.set(success.token, SecureStore.KEY_BEARER)
                prefs.setString(Prefs.KEY_IDENTITY_NAME, identity.displayName)
                prefs.setString(Prefs.KEY_IDENTITY_HANDLE, identity.handle)
                prefs.setString(Prefs.KEY_IDENTITY_TIER, identity.tier.name)
                identity.accountId?.let {
                    prefs.setString(Prefs.KEY_IDENTITY_ACCOUNT_ID, it)
                } ?: prefs.remove(Prefs.KEY_IDENTITY_ACCOUNT_ID)
                _state.value = State.SignedIn(identity)
                true
            }
        }
    }

    /**
     * Fetch + cache a signed relay CLIENT ticket for the signed-in account. A linked computer
     * re-mints the PAIRING's client ticket (its own isolated relay room); otherwise fall back to
     * the legacy account ticket. Both land in the same SecureStore slot the connection reads.
     */
    suspend fun refreshRelayTicket(triggerReconnect: Boolean = false): Boolean {
        val expectedGeneration = synchronized(authLock) { authGeneration }
        return refreshRelayTicketFor(expectedGeneration, triggerReconnect)
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
        val snapshot = synchronized(authLock) {
            val token = secure.get(SecureStore.KEY_BEARER)
            if (token == null || _state.value !is State.SignedIn) null
            else AuthSnapshot(authGeneration, token, pairings.current?.pairingId)
        } ?: return Result.failure(BackendApiClient.PairingException("Sign in first."))

        return try {
            val dto = api.pairClaim(code, appLabel, snapshot.token)
            val machine = dto.machineName ?: "Computer"
            var refreshToCancel: Job? = null
            val committed = synchronized(authLock) {
                if (!isCurrentAuthLocked(snapshot.generation, snapshot.token)) {
                    false
                } else {
                    // Pairing changes which relay room the ticket authorizes. Advance the
                    // generation before replacing it so any account-ticket refresh becomes stale.
                    authGeneration += 1
                    refreshToCancel = relayRefreshJob
                    relayRefreshJob = null
                    val relayUrl = dto.relayUrl ?: (prefs.getString(Prefs.KEY_RELAY_URL) ?: "")
                    pairings.set(PairingStore.Pairing(dto.pairingId, machine, relayUrl))
                    _pairedMachine.value = machine
                    secure.set(dto.clientTicket, SecureStore.KEY_RELAY_TICKET)
                    dto.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
                    _relayTicketVersion.value = _relayTicketVersion.value + 1
                    true
                }
            }
            refreshToCancel?.cancel()
            if (committed) {
                Result.success(machine)
            } else {
                Result.failure(sessionChangedPairingError())
            }
        } catch (error: CancellationException) {
            throw error
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
        var pairing: PairingStore.Pairing? = null
        var token: String? = null
        var refreshToCancel: Job? = null
        var unlinkedGeneration = 0L
        synchronized(authLock) {
            pairing = pairings.current
            token = secure.get(SecureStore.KEY_BEARER)
            authGeneration += 1
            unlinkedGeneration = authGeneration
            refreshToCancel = relayRefreshJob
            relayRefreshJob = null
            pairings.clear()
            _pairedMachine.value = null
            secure.delete(SecureStore.KEY_RELAY_TICKET)
            _relayTicketVersion.value = _relayTicketVersion.value + 1
        }
        refreshToCancel?.cancel()

        val capturedPairing = pairing
        val capturedToken = token
        if (capturedPairing != null && capturedToken != null) {
            scope.launch {
                try {
                    api.pairRevoke(capturedPairing!!.pairingId, capturedToken!!)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logRemoteFailure("Pairing revoke", error)
                    reportRemoteFailure(
                        unlinkedGeneration,
                        requireSignedOut = false,
                        "This phone was unlinked locally, but the server couldn't confirm the " +
                            "remote revoke. Check your connection before pairing again.",
                    )
                }
            }
        }
    }

    // MARK: - helpers

    private data class AuthSnapshot(
        val generation: Long,
        val token: String,
        val pairingId: String?,
    )

    private data class RelayCredential(
        val ticket: String,
        val relayUrl: String?,
    )

    /** Launch an owned refresh that can be cancelled as soon as auth or pairing changes. */
    private fun scheduleRelayRefresh(triggerReconnect: Boolean, expectedGeneration: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            refreshRelayTicketFor(expectedGeneration, triggerReconnect)
        }
        var previous: Job? = null
        val shouldStart = synchronized(authLock) {
            if (authGeneration != expectedGeneration ||
                _state.value !is State.SignedIn ||
                secure.get(SecureStore.KEY_BEARER) == null
            ) {
                false
            } else {
                previous = relayRefreshJob
                relayRefreshJob = job
                true
            }
        }
        if (!shouldStart) {
            job.cancel()
            return
        }
        previous?.cancel()
        job.invokeOnCompletion {
            synchronized(authLock) {
                if (relayRefreshJob === job) relayRefreshJob = null
            }
        }
        job.start()
    }

    /**
     * Fetch outside the lock, then commit only if auth generation, bearer, and pairing still match.
     * This is the critical guard that keeps a late response from resurrecting credentials after
     * sign-out or replacing a newer pairing's ticket.
     */
    private suspend fun refreshRelayTicketFor(
        expectedGeneration: Long,
        triggerReconnect: Boolean,
    ): Boolean {
        val snapshot = synchronized(authLock) {
            val token = secure.get(SecureStore.KEY_BEARER)
            if (authGeneration != expectedGeneration ||
                token == null ||
                _state.value !is State.SignedIn
            ) {
                null
            } else {
                AuthSnapshot(expectedGeneration, token, pairings.current?.pairingId)
            }
        } ?: return false

        val credential = try {
            if (snapshot.pairingId != null) {
                val dto = api.pairClientTicket(snapshot.pairingId, snapshot.token)
                RelayCredential(dto.clientTicket, dto.relayUrl)
            } else {
                val dto = api.fetchRelayTicket(snapshot.token)
                RelayCredential(dto.ticket, dto.relayUrl)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logRemoteFailure("Relay ticket refresh", error)
            return false
        }

        return synchronized(authLock) {
            val pairingStillMatches = pairings.current?.pairingId == snapshot.pairingId
            if (!pairingStillMatches || !isCurrentAuthLocked(snapshot.generation, snapshot.token)) {
                false
            } else {
                secure.set(credential.ticket, SecureStore.KEY_RELAY_TICKET)
                credential.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
                if (triggerReconnect) {
                    _relayTicketVersion.value = _relayTicketVersion.value + 1
                }
                true
            }
        }
    }

    private fun isCurrentAuthLocked(expectedGeneration: Long, expectedToken: String): Boolean =
        authGeneration == expectedGeneration &&
            _state.value is State.SignedIn &&
            secure.get(SecureStore.KEY_BEARER) == expectedToken

    private fun reportRemoteFailure(
        expectedGeneration: Long,
        requireSignedOut: Boolean,
        message: String,
    ) {
        synchronized(authLock) {
            val stateMatches = if (requireSignedOut) {
                _state.value is State.SignedOut
            } else {
                _state.value is State.SignedIn
            }
            if (authGeneration == expectedGeneration && stateMatches) {
                _lastError.value = message
            }
        }
    }

    private fun logRemoteFailure(operation: String, error: Throwable) {
        // Exception type is enough for diagnostics and deliberately avoids logging request data,
        // bearer tokens, relay tickets, pairing codes, or potentially sensitive server bodies.
        Log.w(TAG, "$operation failed (${error.javaClass.simpleName})")
    }

    private fun sessionChangedPairingError(): BackendApiClient.PairingException =
        BackendApiClient.PairingException("Your session changed. Sign in and try pairing again.")

    private fun tierFrom(raw: String?): Tier =
        Tier.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Tier.free

    private fun loginErrorMessage(reason: String): String = when (reason) {
        "access_denied", "denied" -> "Sign-in was denied. Tap Sign in to try again."
        "expired_token", "expired" -> "The sign-in request expired. Tap Sign in to try again."
        else -> "Sign-in didn't complete. Check your connection and try again."
    }

    private companion object {
        const val TAG = "AuthManager"
        const val LOCAL_LEDGER_HOST_BINDING = "local-daemon"
    }
}
