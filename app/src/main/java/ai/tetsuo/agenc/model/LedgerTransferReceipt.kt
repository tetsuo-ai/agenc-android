package ai.tetsuo.agenc.model

import ai.tetsuo.agenc.ledger.Base58
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Exact, correlated result returned to Core after one typed mobile Ledger request. */
@ConsistentCopyVisibility
@Serializable
data class LedgerTransferReceipt internal constructor(
    val type: String,
    val intentId: String,
    val responseNonce: String,
    val status: LedgerTransferStatus,
    val network: String,
    val to: String,
    /** Decimal text on the wire; never a JSON number. */
    val lamports: String,
    val from: String? = null,
    val signature: String? = null,
    val reason: String? = null,
) {
    fun toJson(): String = CODEC.encodeToString(this)

    companion object {
        const val TYPE = "ledger_solana_transfer_receipt_v1"
        const val NETWORK = "mainnet-beta"

        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
        private val REASON_CODE = Regex("[a-z0-9_]+")
        private val CODEC = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }

        fun submitted(
            intentId: String,
            responseNonce: String,
            to: String,
            lamports: Long,
            from: String,
            signature: String,
        ): LedgerTransferReceipt = checked(
            LedgerTransferReceipt(
                type = TYPE,
                intentId = intentId,
                responseNonce = responseNonce,
                status = LedgerTransferStatus.Submitted,
                network = NETWORK,
                to = to,
                lamports = lamports.toString(),
                from = from,
                signature = signature,
            ),
        )

        fun cancelled(
            intentId: String,
            responseNonce: String,
            to: String,
            lamports: Long,
            from: String? = null,
            reason: String,
        ): LedgerTransferReceipt = checked(
            LedgerTransferReceipt(
                type = TYPE,
                intentId = intentId,
                responseNonce = responseNonce,
                status = LedgerTransferStatus.Cancelled,
                network = NETWORK,
                to = to,
                lamports = lamports.toString(),
                from = from?.takeIf { it.isNotBlank() },
                reason = reason,
            ),
        )

        /** Strictly rejects malformed, ambiguous, or forward-extended receipts. */
        fun parse(raw: String): LedgerTransferReceipt? = runCatching {
            checked(CODEC.decodeFromString<LedgerTransferReceipt>(raw))
        }.getOrNull()

        fun isValidResponseNonce(value: String): Boolean =
            value.length in 32..128 && SAFE_ID.matches(value)

        private fun checked(receipt: LedgerTransferReceipt): LedgerTransferReceipt {
            require(receipt.type == TYPE) { "wrong Ledger receipt type" }
            require(receipt.network == NETWORK) { "wrong Ledger receipt network" }
            require(
                receipt.intentId.startsWith("ledger_") &&
                    receipt.intentId.length in 15..200 &&
                    SAFE_ID.matches(receipt.intentId),
            ) { "invalid Ledger receipt intent" }
            require(isValidResponseNonce(receipt.responseNonce)) {
                "invalid Ledger response nonce"
            }
            require(isAddress(receipt.to)) { "invalid Ledger receipt recipient" }
            require(
                POSITIVE_INTEGER.matches(receipt.lamports) &&
                    receipt.lamports.toLongOrNull()?.let { it > 0L } == true,
            ) { "invalid Ledger receipt amount" }
            require(receipt.from == null || isAddress(receipt.from)) {
                "invalid Ledger receipt source"
            }

            when (receipt.status) {
                LedgerTransferStatus.Submitted -> {
                    require(receipt.from != null) { "submitted Ledger receipt requires source" }
                    require(receipt.signature?.let(::isSignature) == true) {
                        "submitted Ledger receipt requires signature"
                    }
                    require(receipt.reason == null) {
                        "submitted Ledger receipt cannot contain a cancellation reason"
                    }
                }
                LedgerTransferStatus.Cancelled -> {
                    require(receipt.signature == null) {
                        "cancelled Ledger receipt cannot contain a signature"
                    }
                    require(receipt.reason?.let {
                        it.length in 1..80 && REASON_CODE.matches(it)
                    } == true) { "cancelled Ledger receipt requires a reason code" }
                }
            }
            return receipt
        }

        private fun isAddress(value: String): Boolean =
            value.length in 32..44 &&
                runCatching { Base58.decode(value).size == 32 }.getOrDefault(false)

        private fun isSignature(value: String): Boolean =
            value.length in 64..90 &&
                runCatching { Base58.decode(value).size == 64 }.getOrDefault(false)
    }
}

/** Non-secret, nonce-free result Core emits only after validating the phone receipt. */
@ConsistentCopyVisibility
@Serializable
data class LedgerTransferResult internal constructor(
    val type: String,
    val intentId: String,
    val status: LedgerTransferStatus,
    val network: String,
    val to: String,
    val lamports: String,
    val from: String? = null,
    val signature: String? = null,
    val reason: String? = null,
    /** Always false in v1: submitted is not the same as finalized on Solana. */
    val confirmed: Boolean,
) {
    fun toJson(): String = CODEC.encodeToString(this)

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
        private val REASON_CODE = Regex("[a-z0-9_]+")
        private val CODEC = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
            explicitNulls = false
        }

        fun parse(raw: String): LedgerTransferResult? = runCatching {
            checked(CODEC.decodeFromString<LedgerTransferResult>(raw))
        }.getOrNull()

        private fun checked(result: LedgerTransferResult): LedgerTransferResult {
            require(result.type == LedgerTransferReceipt.TYPE) { "wrong Ledger result type" }
            require(result.network == LedgerTransferReceipt.NETWORK) { "wrong Ledger result network" }
            require(!result.confirmed) { "v1 Ledger result cannot claim confirmation" }
            require(
                result.intentId.startsWith("ledger_") &&
                    result.intentId.length in 15..200 &&
                    SAFE_ID.matches(result.intentId),
            ) { "invalid Ledger result intent" }
            require(isAddress(result.to)) { "invalid Ledger result recipient" }
            require(
                POSITIVE_INTEGER.matches(result.lamports) &&
                    result.lamports.toLongOrNull()?.let { it > 0L } == true,
            ) { "invalid Ledger result amount" }
            require(result.from == null || isAddress(result.from)) { "invalid Ledger result source" }
            when (result.status) {
                LedgerTransferStatus.Submitted -> {
                    require(result.from != null) { "submitted Ledger result requires source" }
                    require(result.signature?.let(::isSignature) == true) {
                        "submitted Ledger result requires signature"
                    }
                    require(result.reason == null) { "submitted Ledger result cannot contain reason" }
                }
                LedgerTransferStatus.Cancelled -> {
                    require(result.signature == null) { "cancelled Ledger result cannot contain signature" }
                    require(result.reason?.let {
                        it.length in 1..80 && REASON_CODE.matches(it)
                    } == true) { "cancelled Ledger result requires a reason code" }
                }
            }
            return result
        }

        private fun isAddress(value: String): Boolean =
            value.length in 32..44 &&
                runCatching { Base58.decode(value).size == 32 }.getOrDefault(false)

        private fun isSignature(value: String): Boolean =
            value.length in 64..90 &&
                runCatching { Base58.decode(value).size == 64 }.getOrDefault(false)
    }
}

@Serializable
enum class LedgerTransferStatus {
    @SerialName("submitted")
    Submitted,

    @SerialName("cancelled")
    Cancelled,
}
