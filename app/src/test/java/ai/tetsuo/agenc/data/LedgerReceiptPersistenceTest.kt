package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerReceiptPersistenceTest {
    @Test
    fun `terminal same-intent receipt is monotonic and only exact replay succeeds`() {
        val submitted = submittedRecord()
        val cancellation = LedgerTransferReceipt.cancelled(
            intentId = submitted.intentId,
            responseNonce = submitted.responseNonce,
            to = submitted.recipientAddress,
            lamports = submitted.lamports,
            from = submitted.sourceAddress,
            reason = "cancelled_on_phone",
        ).toJson()

        assertEquals(submitted, mergePendingLedgerResponse(submitted, submitted))
        assertEquals(
            submitted,
            mergePendingLedgerResponse(submitted, submitted.copy(hardwareReviewStarted = false)),
        )
        assertNull(mergePendingLedgerResponse(submitted, submitted.copy(receipt = null)))
        assertNull(mergePendingLedgerResponse(submitted, submitted.copy(receipt = cancellation)))
        assertNull(
            mergePendingLedgerResponse(
                submitted,
                submitted.copy(lamports = submitted.lamports + 1),
            ),
        )
    }

    @Test
    fun `archive is bounded append-only idempotent and rejects key conflicts`() {
        val submitted = submittedRecord()
        val first = planLedgerReceiptArchive(LedgerReceiptOutboxState(), submitted, maxRecords = 1)
        assertTrue(first.accepted)
        assertEquals(listOf(submitted), first.state.records)

        val replay = planLedgerReceiptArchive(first.state, submitted, maxRecords = 1)
        assertTrue(replay.accepted)
        assertEquals(first.state, replay.state)

        val conflictingReceipt = LedgerTransferReceipt.cancelled(
            intentId = submitted.intentId,
            responseNonce = submitted.responseNonce,
            to = submitted.recipientAddress,
            lamports = submitted.lamports,
            from = submitted.sourceAddress,
            reason = "cancelled_on_phone",
        ).toJson()
        val conflict = planLedgerReceiptArchive(
            first.state,
            submitted.copy(receipt = conflictingReceipt),
            maxRecords = 1,
        )
        assertFalse(conflict.accepted)
        assertEquals(first.state, conflict.state)

        val second = submittedRecord(
            intentId = "ledger_second_123",
            responseNonce = "223e4567-e89b-12d3-a456-426614174000",
        )
        val full = planLedgerReceiptArchive(
            first.state,
            second,
            maxRecords = 1,
        )
        assertFalse(full.accepted)
        assertEquals(first.state, full.state)

        // Production deliberately has no cap: an unacknowledged financial receipt is never
        // dropped merely because older reconciliation records remain.
        val uncapped = planLedgerReceiptArchive(first.state, second)
        assertTrue(uncapped.accepted)
        assertEquals(2, uncapped.state.records.size)
    }

    @Test
    fun `archive requires receipt fields to match durable request exactly`() {
        val submitted = submittedRecord()
        assertTrue(hasCorrelatedLedgerReceipt(submitted))
        assertFalse(
            hasCorrelatedLedgerReceipt(
                submitted.copy(recipientAddress = OTHER_ADDRESS),
            ),
        )
        assertFalse(hasCorrelatedLedgerReceipt(submitted.copy(receipt = "{}")))
    }

    private fun submittedRecord(
        intentId: String = INTENT_ID,
        responseNonce: String = RESPONSE_NONCE,
    ): PendingLedgerResponseRecord {
        val receipt = LedgerTransferReceipt.submitted(
            intentId = intentId,
            responseNonce = responseNonce,
            to = ADDRESS,
            lamports = 1_000_000L,
            from = ADDRESS,
            signature = SIGNATURE,
        ).toJson()
        return PendingLedgerResponseRecord(
            sessionId = "session-1",
            requestId = "request-$intentId",
            questionId = "ledger_transfer",
            hostBinding = "paired-host",
            intentId = intentId,
            responseNonce = responseNonce,
            expiresAtEpochMillis = 10_000L,
            sourceAddress = ADDRESS,
            recipientAddress = ADDRESS,
            lamports = 1_000_000L,
            receipt = receipt,
            hardwareReviewStarted = true,
        )
    }

    private companion object {
        const val INTENT_ID = "ledger_12345678"
        const val RESPONSE_NONCE = "123e4567-e89b-12d3-a456-426614174000"
        const val ADDRESS = "11111111111111111111111111111111"
        const val OTHER_ADDRESS = "Vote111111111111111111111111111111111111111"
        val SIGNATURE: String = Base58.encode(ByteArray(64) { (it + 1).toByte() })
    }
}
