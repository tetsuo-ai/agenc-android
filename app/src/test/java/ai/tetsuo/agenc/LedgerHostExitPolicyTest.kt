package ai.tetsuo.agenc

import ai.tetsuo.agenc.data.PendingLedgerResponseRecord
import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerHostExitPolicyTest {
    @Test
    fun `host exit archives receipts clears only unsigned work and blocks critical recovery`() {
        val submitted = record(receipt = submittedReceipt(), hardwareReviewStarted = true)
        assertEquals(
            LedgerHostExitAction.ArchiveReceipt,
            planLedgerHostExit(submitted, hasPendingBroadcast = false, hardwareFlowCritical = false),
        )
        assertEquals(
            LedgerHostExitAction.BlockCritical,
            planLedgerHostExit(submitted, hasPendingBroadcast = true, hardwareFlowCritical = false),
        )
        assertEquals(
            LedgerHostExitAction.BlockCritical,
            planLedgerHostExit(submitted, hasPendingBroadcast = false, hardwareFlowCritical = true),
        )
        assertEquals(
            LedgerHostExitAction.BlockCritical,
            planLedgerHostExit(
                record(receipt = null, hardwareReviewStarted = true),
                hasPendingBroadcast = false,
                hardwareFlowCritical = false,
            ),
        )
        assertEquals(
            LedgerHostExitAction.ClearUnsigned,
            planLedgerHostExit(
                record(receipt = null, hardwareReviewStarted = false),
                hasPendingBroadcast = false,
                hardwareFlowCritical = false,
            ),
        )
        assertEquals(
            LedgerHostExitAction.BlockInvalidReceipt,
            planLedgerHostExit(
                record(receipt = "{}", hardwareReviewStarted = true),
                hasPendingBroadcast = false,
                hardwareFlowCritical = false,
            ),
        )
        assertEquals(
            LedgerHostExitAction.Proceed,
            planLedgerHostExit(null, hasPendingBroadcast = false, hardwareFlowCritical = false),
        )
    }

    private fun record(receipt: String?, hardwareReviewStarted: Boolean) =
        PendingLedgerResponseRecord(
            sessionId = "session-1",
            requestId = "request-1",
            questionId = "ledger_transfer",
            hostBinding = "paired-host",
            intentId = INTENT_ID,
            responseNonce = NONCE,
            expiresAtEpochMillis = 10_000L,
            sourceAddress = ADDRESS,
            recipientAddress = ADDRESS,
            lamports = 1L,
            receipt = receipt,
            hardwareReviewStarted = hardwareReviewStarted,
        )

    private fun submittedReceipt(): String = LedgerTransferReceipt.submitted(
        intentId = INTENT_ID,
        responseNonce = NONCE,
        to = ADDRESS,
        lamports = 1L,
        from = ADDRESS,
        signature = Base58.encode(ByteArray(64) { (it + 1).toByte() }),
    ).toJson()

    private companion object {
        const val INTENT_ID = "ledger_12345678"
        const val NONCE = "123e4567-e89b-12d3-a456-426614174000"
        const val ADDRESS = "11111111111111111111111111111111"
    }
}
