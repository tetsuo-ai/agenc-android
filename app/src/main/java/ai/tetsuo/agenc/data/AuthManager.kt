package ai.tetsuo.agenc.data

import android.util.Log
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.net.BackendApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Merge an exact `/v1/auth/me` profile over the cache without deriving a handle or paid plan. */
internal fun canonicalAccountIdentity(
    cached: Identity,
    profile: BackendApiClient.CanonicalIdentityDTO,
): Identity {
    require(profile.accountId.isNotBlank()) { "canonical account id is required" }
    return Identity(
        displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: cached.displayName,
        email = profile.email?.takeIf { it.isNotBlank() } ?: cached.email,
        accountId = profile.accountId,
        handle = null,
    )
}

/** Convert old sample credentials into an empty profile without treating them as a real person. */
internal fun restoredAccountIdentity(
    displayName: String?,
    email: String?,
    accountId: String?,
    handle: String?,
): Identity {
    val isLegacyPlaceholder = accountId.isNullOrBlank() &&
        displayName.equals("User", ignoreCase = true) &&
        email.equals("user@example.com", ignoreCase = true)
    return Identity(
        displayName = if (isLegacyPlaceholder) "" else displayName ?: "",
        email = if (isLegacyPlaceholder) "" else email ?: "",
        accountId = accountId?.takeIf { it.isNotBlank() },
        handle = if (isLegacyPlaceholder) null else handle?.takeIf { it.isNotBlank() },
    )
}

/** Project the account inherited from a successful CLI-owned pairing claim. */
internal fun pairingClaimIdentity(auth: BackendApiClient.PairingAuthDTO): Identity {
    require(auth.token.isNotBlank()) { "pairing auth token is required" }
    require(!auth.identity.accountId.isNullOrBlank()) { "pairing account id is required" }
    return Identity(
        displayName = auth.identity.displayName?.takeIf { it.isNotBlank() } ?: "Account",
        email = auth.identity.email?.takeIf { it.isNotBlank() } ?: "",
        accountId = auth.identity.accountId,
        handle = auth.identity.handle?.takeIf { it.isNotBlank() },
    )
}

/**
 * Android's pairing-owned authentication state.
 *
 * Holds the opaque bearer token in [SecureStore] and caches the identity for cold-start. The app
 * never starts OAuth: [linkMac] redeems the code from an authenticated Core CLI and receives the
 * account session and pairing relay ticket together.
 *
 * Observable state for the UI:
 *  - [state] — whether a complete pairing-owned account session is available
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
    private var relayRefreshJob: Job? = null
    private var profileRefreshJob: Job? = null

    val isSignedIn: Boolean get() = _state.value is State.SignedIn
    val identity: Identity? get() = (_state.value as? State.SignedIn)?.identity
    val bearer: String? get() = secure.get(SecureStore.KEY_BEARER)
    /** Stable owner for pending Ledger request/receipt correlation; never a display name. */
    fun ledgerHostBinding(): String = pairings.current?.pairingId ?: LOCAL_LEDGER_HOST_BINDING

    /** Call once on launch to restore the session (mirrors the iOS init). */
    fun restore() {
        var previousRefresh: Job? = null
        var previousProfileRefresh: Job? = null
        var restoredGeneration: Long? = null
        synchronized(authLock) {
            authGeneration += 1
            previousRefresh = relayRefreshJob
            previousProfileRefresh = profileRefreshJob
            relayRefreshJob = null
            profileRefreshJob = null

            _pairedMachine.value = pairings.current?.machineName
            if (secure.get(SecureStore.KEY_BEARER) != null) {
                val legacySecondary = prefs.getString(Prefs.KEY_IDENTITY_HANDLE)
                val cachedEmail = prefs.getString(Prefs.KEY_IDENTITY_EMAIL)
                    ?: legacySecondary?.takeUnless { it.trim().startsWith("@") }
                    ?: ""
                _state.value = State.SignedIn(
                    restoredAccountIdentity(
                        displayName = prefs.getString(Prefs.KEY_IDENTITY_NAME),
                        email = cachedEmail,
                        accountId = prefs.getString(Prefs.KEY_IDENTITY_ACCOUNT_ID),
                        handle = legacySecondary?.takeIf { it.trim().startsWith("@") },
                    ),
                )
                prefs.remove(Prefs.KEY_IDENTITY_TIER)
                restoredGeneration = authGeneration
            } else {
                _state.value = State.SignedOut
            }
        }
        previousRefresh?.cancel()
        previousProfileRefresh?.cancel()

        // A cached ticket may be near expiry. Re-mint it and notify the connection layer when the
        // fresh credential is safely committed so a cold-started app does not keep the stale link.
        restoredGeneration?.let {
            scheduleProfileRefresh(it)
            scheduleRelayRefresh(triggerReconnect = true, it)
        }
    }

    /**
     * Sign out AND drop any computer pairing, so logging out can't silently leave a stale remote
     * link behind.
     */
    fun signOut() {
        var refreshToCancel: Job? = null
        var profileToCancel: Job? = null
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
            refreshToCancel = relayRefreshJob
            profileToCancel = profileRefreshJob
            relayRefreshJob = null
            profileRefreshJob = null

            secure.delete(SecureStore.KEY_BEARER)
            secure.delete(SecureStore.KEY_RELAY_TICKET)
            pairings.clear()
            prefs.remove(Prefs.KEY_RELAY_URL)
            prefs.remove(Prefs.KEY_IDENTITY_NAME)
            prefs.remove(Prefs.KEY_IDENTITY_EMAIL)
            prefs.remove(Prefs.KEY_IDENTITY_HANDLE)
            prefs.remove(Prefs.KEY_IDENTITY_TIER)
            prefs.remove(Prefs.KEY_IDENTITY_ACCOUNT_ID)
            _pairedMachine.value = null
            _lastError.value = null
            _state.value = State.SignedOut
        }
        refreshToCancel?.cancel()
        profileToCancel?.cancel()

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

    /**
     * Fetch + cache a signed relay CLIENT ticket for the signed-in account. A linked computer
     * re-mints the PAIRING's client ticket (its own isolated relay room); otherwise fall back to
     * the legacy account ticket. Both land in the same SecureStore slot the connection reads.
     */
    suspend fun refreshRelayTicket(triggerReconnect: Boolean = false): Boolean {
        val expectedGeneration = synchronized(authLock) { authGeneration }
        return refreshRelayTicketFor(expectedGeneration, triggerReconnect)
    }

    /** Refresh the hosted account profile without discarding a usable cache on deployment/network failure. */
    suspend fun refreshCanonicalProfile(): Boolean {
        val expectedGeneration = synchronized(authLock) { authGeneration }
        return refreshCanonicalProfileFor(expectedGeneration)
    }

    /** The cached relay client ticket, if any (the connection layer dials the relay with it). */
    fun relayTicket(): String? = secure.get(SecureStore.KEY_RELAY_TICKET)

    /** The relay URL the backend last reported (overrides the default RELAY_BASE when present). */
    fun relayUrl(): String? = prefs.getString(Prefs.KEY_RELAY_URL)

    // MARK: - device pairing

    /**
     * Redeem the one-time code shown by an already-authenticated Core CLI. This is Android's only
     * onboarding/authentication ceremony: one backend transaction returns the inherited account
     * session and the selected host's relay credential, which are exposed to the app together.
     */
    suspend fun linkMac(code: String, appLabel: String): Result<String> {
        var refreshToCancel: Job? = null
        var profileToCancel: Job? = null
        var legacyToken: String? = null
        var legacyIdentity: Identity? = null
        val claimGeneration = synchronized(authLock) {
            // A pairing claim supersedes every refresh based on an older account. Keep the visible
            // state stable until the complete claim is validated.
            legacyToken = secure.get(SecureStore.KEY_BEARER)
            legacyIdentity = (_state.value as? State.SignedIn)?.identity
            authGeneration += 1
            refreshToCancel = relayRefreshJob
            profileToCancel = profileRefreshJob
            relayRefreshJob = null
            profileRefreshJob = null
            _lastError.value = null
            authGeneration
        }
        refreshToCancel?.cancel()
        profileToCancel?.cancel()

        return try {
            // During the rolling deploy, production may still run the prior bearer-gated claim.
            // Send an existing phone bearer when available; the new backend ignores it and returns
            // a replacement auth block, while the old backend can keep an already-working pairing.
            val dto = api.pairClaim(code, appLabel, legacyToken)
            val inheritedAuth = dto.auth
            val resolvedToken = inheritedAuth?.token ?: legacyToken
                ?: throw BackendApiClient.PairingException(
                    "The pairing service returned an invalid response.",
                )
            val identity = inheritedAuth?.let(::pairingClaimIdentity) ?: legacyIdentity
                ?: throw BackendApiClient.PairingException(
                    "The pairing service returned an invalid response.",
                )
            val machine = dto.machineName ?: "Computer"
            val committed = synchronized(authLock) {
                if (authGeneration != claimGeneration) {
                    false
                } else {
                    // Commit the complete validated response before publishing SignedIn/Main. No
                    // UI observer can see an authenticated account without its matching host room.
                    authGeneration += 1
                    val relayUrl = dto.relayUrl ?: (prefs.getString(Prefs.KEY_RELAY_URL) ?: "")
                    secure.set(resolvedToken, SecureStore.KEY_BEARER)
                    secure.set(dto.clientTicket, SecureStore.KEY_RELAY_TICKET)
                    pairings.set(PairingStore.Pairing(dto.pairingId, machine, relayUrl))
                    cacheIdentityLocked(identity)
                    dto.relayUrl?.let { prefs.setString(Prefs.KEY_RELAY_URL, it) }
                    _pairedMachine.value = machine
                    _state.value = State.SignedIn(identity)
                    _lastError.value = null
                    _relayTicketVersion.value = _relayTicketVersion.value + 1
                    true
                }
            }
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

    /** Cold-start owner for `/auth/me`; stale responses cannot overwrite a newer login. */
    private fun scheduleProfileRefresh(expectedGeneration: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            refreshCanonicalProfileFor(expectedGeneration)
        }
        var previous: Job? = null
        val shouldStart = synchronized(authLock) {
            if (authGeneration != expectedGeneration ||
                _state.value !is State.SignedIn ||
                secure.get(SecureStore.KEY_BEARER) == null
            ) {
                false
            } else {
                previous = profileRefreshJob
                profileRefreshJob = job
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
                if (profileRefreshJob === job) profileRefreshJob = null
            }
        }
        job.start()
    }

    /**
     * Apply the canonical hosted profile only to the bearer/generation that requested it.
     *
     * A missing/not-yet-deployed endpoint or transient network error leaves the cached identity
     * intact. A 401/403 is authoritative: credentials are cleared and the app returns to sign-in while
     * retaining the pairing record, so pending host-bound Ledger recovery is never silently rebound.
     */
    private suspend fun refreshCanonicalProfileFor(expectedGeneration: Long): Boolean {
        val snapshot = synchronized(authLock) {
            val token = secure.get(SecureStore.KEY_BEARER)
            val signedIn = _state.value as? State.SignedIn
            if (authGeneration != expectedGeneration || token == null || signedIn == null) {
                null
            } else {
                Triple(expectedGeneration, token, signedIn.identity)
            }
        } ?: return false

        val profile = try {
            api.fetchProfile(snapshot.second)
        } catch (error: CancellationException) {
            throw error
        } catch (_: BackendApiClient.UnauthorizedException) {
            expireAuthenticatedSession(snapshot.first, snapshot.second)
            return false
        } catch (error: Throwable) {
            // Rollout-safe: older backends can return 404 until /v1/auth/me is deployed.
            logRemoteFailure("Canonical profile refresh", error)
            return false
        }

        val canonical = canonicalAccountIdentity(snapshot.third, profile.identity)
        return synchronized(authLock) {
            if (!isCurrentAuthLocked(snapshot.first, snapshot.second)) {
                false
            } else {
                cacheIdentityLocked(canonical)
                _state.value = State.SignedIn(canonical)
                _lastError.value = null
                true
            }
        }
    }

    /** Invalidate an expired bearer without dropping the paired-host recovery namespace. */
    private fun expireAuthenticatedSession(expectedGeneration: Long, expectedToken: String) {
        var relayToCancel: Job? = null
        synchronized(authLock) {
            if (!isCurrentAuthLocked(expectedGeneration, expectedToken)) return
            authGeneration += 1
            secure.delete(SecureStore.KEY_BEARER)
            secure.delete(SecureStore.KEY_RELAY_TICKET)
            relayToCancel = relayRefreshJob
            relayRefreshJob = null
            profileRefreshJob = null
            _state.value = State.SignedOut
            _lastError.value = "Your AgenC account session expired. Sign in again."
            _relayTicketVersion.value = _relayTicketVersion.value + 1
        }
        relayToCancel?.cancel()
    }

    /** Persist only exact backend fields. Paid-plan values and derived handles are never cached. */
    private fun cacheIdentityLocked(identity: Identity) {
        prefs.setString(Prefs.KEY_IDENTITY_NAME, identity.displayName)
        if (identity.email.isNotBlank()) {
            prefs.setString(Prefs.KEY_IDENTITY_EMAIL, identity.email)
        } else {
            prefs.remove(Prefs.KEY_IDENTITY_EMAIL)
        }
        identity.handle?.takeIf { it.isNotBlank() }?.let {
            prefs.setString(Prefs.KEY_IDENTITY_HANDLE, it)
        } ?: prefs.remove(Prefs.KEY_IDENTITY_HANDLE)
        identity.accountId?.let {
            prefs.setString(Prefs.KEY_IDENTITY_ACCOUNT_ID, it)
        } ?: prefs.remove(Prefs.KEY_IDENTITY_ACCOUNT_ID)
        prefs.remove(Prefs.KEY_IDENTITY_TIER)
    }

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

    private companion object {
        const val TAG = "AuthManager"
        const val LOCAL_LEDGER_HOST_BINDING = "local-daemon"
    }
}
