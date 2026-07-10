package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.model.Identity

/**
 * Reconcile the phone bearer identity with the live Core `/login` identity.
 *
 * Stable account ids are authoritative when both sides expose them. During migration from older
 * phone builds (which discarded accountId), an exact normalized email/handle match is accepted. A
 * mismatch never replaces the phone account; it is surfaced explicitly in Account instead.
 */
internal fun linkedAccountIdentity(phone: Identity?, runtime: Identity?): Identity? {
    if (phone == null) return runtime?.copy(runtimeLinked = false, runtimeMismatch = false)
    if (runtime == null) return phone.copy(runtimeLinked = false, runtimeMismatch = false)

    val phoneId = phone.accountId.normalizedIdentityValue()
    val runtimeId = runtime.accountId.normalizedIdentityValue()
    val idsConflict = phoneId != null && runtimeId != null && phoneId != runtimeId
    val handlesMatch = phone.handle.normalizedIdentityValue() != null &&
        phone.handle.normalizedIdentityValue() == runtime.handle.normalizedIdentityValue()
    val linked = !idsConflict && (
        (phoneId != null && runtimeId != null) || handlesMatch
    )

    if (!linked) {
        return phone.copy(runtimeLinked = false, runtimeMismatch = idsConflict)
    }
    return runtime.copy(
        accountId = runtime.accountId ?: phone.accountId,
        tier = if (runtime.tierVerified) runtime.tier else phone.tier,
        tierVerified = runtime.tierVerified || phone.tierVerified,
        runtimeLinked = true,
        runtimeMismatch = false,
    )
}

private fun String?.normalizedIdentityValue(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
