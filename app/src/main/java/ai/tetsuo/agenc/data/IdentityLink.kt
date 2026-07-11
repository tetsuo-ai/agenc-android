package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.AccountUiState
import ai.tetsuo.agenc.model.CoreAccountLinkState
import ai.tetsuo.agenc.model.CoreAccountState
import ai.tetsuo.agenc.model.Identity

/**
 * Reconcile the canonical hosted-login account with Core's live `/login` state.
 *
 * Android's account always remains the displayed profile. Stable account ids are authoritative;
 * the email fallback exists only for older records that do not expose an id. An authenticated Core
 * that cannot be proven to be the same account never becomes linked implicitly.
 */
internal fun reconcileAccountIdentity(
    phone: Identity?,
    core: CoreAccountState,
): AccountUiState? {
    val canonicalPhone = phone ?: return null
    val link = when (core) {
        CoreAccountState.Checking -> CoreAccountLinkState.Checking
        CoreAccountState.Offline -> CoreAccountLinkState.Offline
        CoreAccountState.Unavailable -> CoreAccountLinkState.Unavailable
        CoreAccountState.NotLoggedIn -> CoreAccountLinkState.NotLoggedIn
        is CoreAccountState.Authenticated -> reconcileAuthenticatedCore(
            phone = canonicalPhone,
            runtime = core.identity,
        )
    }
    return AccountUiState(phone = canonicalPhone, core = link)
}

private fun reconcileAuthenticatedCore(
    phone: Identity,
    runtime: Identity?,
): CoreAccountLinkState {
    runtime ?: return CoreAccountLinkState.Unverified

    val phoneId = phone.accountId.normalizedIdentityValue()
    val runtimeId = runtime.accountId.normalizedIdentityValue()
    if (phoneId != null && runtimeId != null) {
        return if (phoneId == runtimeId) {
            CoreAccountLinkState.Linked(runtime)
        } else {
            CoreAccountLinkState.Mismatch(runtime)
        }
    }

    val phoneEmail = phone.email.normalizedIdentityValue()
    val runtimeEmail = runtime.email.normalizedIdentityValue()
    if (phoneEmail != null && runtimeEmail != null) {
        return if (phoneEmail == runtimeEmail) {
            CoreAccountLinkState.Linked(runtime)
        } else {
            CoreAccountLinkState.Mismatch(runtime)
        }
    }

    // A legacy exact handle match is accepted only when neither side can provide email. Android
    // never derives this value; it must have been supplied verbatim by both auth providers.
    val phoneHandle = phone.handle.normalizedIdentityValue()
    val runtimeHandle = runtime.handle.normalizedIdentityValue()
    if (phoneEmail == null && runtimeEmail == null && phoneHandle != null && runtimeHandle != null) {
        return if (phoneHandle == runtimeHandle) {
            CoreAccountLinkState.Linked(runtime)
        } else {
            CoreAccountLinkState.Mismatch(runtime)
        }
    }

    return CoreAccountLinkState.Unverified
}

private fun String?.normalizedIdentityValue(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
