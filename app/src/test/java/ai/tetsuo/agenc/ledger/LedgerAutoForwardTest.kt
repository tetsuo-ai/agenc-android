package ai.tetsuo.agenc.ledger

import ai.tetsuo.agenc.data.LedgerHardwareReviewClaimResult
import ai.tetsuo.agenc.data.PendingLedgerResponseRecord
import ai.tetsuo.agenc.data.planLedgerHardwareReviewClaim
import ai.tetsuo.agenc.data.preserveLedgerHardwareReviewFence
import ai.tetsuo.agenc.ui.screens.AgentTransferRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerAutoForwardTest {
    @Test
    fun `manual and local scripted requests never auto-forward`() {
        assertEquals(
            LedgerAutoForwardDecision.MANUAL,
            planLedgerAutoForward(null, wallet(), null, NOW),
        )
        assertEquals(
            LedgerAutoForwardDecision.MANUAL,
            planLedgerAutoForward(
                request = request(intentId = null),
                selected = wallet(),
                recovery = recovery(),
                nowEpochMillis = NOW,
            ),
        )
    }

    @Test
    fun `exact typed request auto-forwards only with persisted default and matching recovery`() {
        assertEquals(
            LedgerAutoForwardDecision.WAITING_FOR_DEFAULT,
            planLedgerAutoForward(request(), null, recovery(), NOW),
        )
        assertEquals(
            LedgerAutoForwardDecision.READY,
            planLedgerAutoForward(request(), wallet(), recovery(), NOW),
        )
        assertEquals(
            LedgerAutoForwardDecision.RECOVERY_MISMATCH,
            planLedgerAutoForward(
                request(),
                wallet(),
                recovery().copy(recipientAddress = OTHER_ADDRESS),
                NOW,
            ),
        )
        assertEquals(
            LedgerAutoForwardDecision.EXPIRED,
            planLedgerAutoForward(request(), wallet(), recovery(), EXPIRES_AT),
        )
    }

    @Test
    fun `durable hardware fence blocks auto-forward after reconnect or process restore`() {
        val started = recovery().copy(hardwareReviewStarted = true)

        assertEquals(
            LedgerAutoForwardDecision.ALREADY_FORWARDED,
            planLedgerAutoForward(request(), wallet(), started, NOW),
        )
        val repeated = planLedgerHardwareReviewClaim(
            current = started,
            intentId = INTENT_ID,
            responseNonce = RESPONSE_NONCE,
            expiresAtEpochMillis = EXPIRES_AT,
            sourceAddress = SOURCE_ADDRESS,
            recipientAddress = RECIPIENT_ADDRESS,
            lamports = LAMPORTS,
        )
        assertEquals(LedgerHardwareReviewClaimResult.ALREADY_STARTED, repeated.result)
        assertNull(repeated.updatedRecord)
    }

    @Test
    fun `exact claim flips the fence before APDU and stale writes cannot roll it back`() {
        val planned = planLedgerHardwareReviewClaim(
            current = recovery(),
            intentId = INTENT_ID,
            responseNonce = RESPONSE_NONCE,
            expiresAtEpochMillis = EXPIRES_AT,
            sourceAddress = SOURCE_ADDRESS,
            recipientAddress = RECIPIENT_ADDRESS,
            lamports = LAMPORTS,
        )

        assertEquals(LedgerHardwareReviewClaimResult.CLAIMED, planned.result)
        assertTrue(planned.updatedRecord?.hardwareReviewStarted == true)

        val staleUiWrite = recovery().copy(sourceAddress = SOURCE_ADDRESS)
        val preserved = preserveLedgerHardwareReviewFence(planned.updatedRecord, staleUiWrite)
        assertTrue(preserved.hardwareReviewStarted)

        val differentIntent = preserveLedgerHardwareReviewFence(
            planned.updatedRecord,
            staleUiWrite.copy(intentId = "ledger_next_intent"),
        )
        assertFalse(differentIntent.hardwareReviewStarted)
    }

    @Test
    fun `claim fails closed when any locked transfer field changes or result already exists`() {
        val mismatch = planLedgerHardwareReviewClaim(
            current = recovery(),
            intentId = INTENT_ID,
            responseNonce = RESPONSE_NONCE,
            expiresAtEpochMillis = EXPIRES_AT,
            sourceAddress = SOURCE_ADDRESS,
            recipientAddress = OTHER_ADDRESS,
            lamports = LAMPORTS,
        )
        val settled = planLedgerHardwareReviewClaim(
            current = recovery().copy(receipt = "submitted"),
            intentId = INTENT_ID,
            responseNonce = RESPONSE_NONCE,
            expiresAtEpochMillis = EXPIRES_AT,
            sourceAddress = SOURCE_ADDRESS,
            recipientAddress = RECIPIENT_ADDRESS,
            lamports = LAMPORTS,
        )

        assertEquals(LedgerHardwareReviewClaimResult.MISMATCH, mismatch.result)
        assertEquals(LedgerHardwareReviewClaimResult.MISMATCH, settled.result)
        assertNull(mismatch.updatedRecord)
        assertNull(settled.updatedRecord)
    }

    private fun request(intentId: String? = INTENT_ID) = AgentTransferRequest(
        toBase58 = RECIPIENT_ADDRESS,
        lamports = LAMPORTS,
        note = "agent funding",
        intentId = intentId,
        responseNonce = RESPONSE_NONCE,
        expiresAtEpochMillis = EXPIRES_AT,
    )

    private fun wallet() = SelectedWalletContext(
        accountKey = "44/501/0/0",
        derivationPath = listOf(44, 501, 0, 0),
        derivationPathLabel = "44'/501'/0'/0'",
        address = SOURCE_ADDRESS,
        deviceName = "Flex",
        deviceAddress = "AA:BB:CC:DD:EE:FF",
    )

    private fun recovery() = PendingLedgerResponseRecord(
        sessionId = "session-1",
        requestId = "request-1",
        questionId = "ledger_transfer",
        hostBinding = "paired-host",
        intentId = INTENT_ID,
        responseNonce = RESPONSE_NONCE,
        expiresAtEpochMillis = EXPIRES_AT,
        sourceAddress = SOURCE_ADDRESS,
        recipientAddress = RECIPIENT_ADDRESS,
        lamports = LAMPORTS,
        receipt = null,
    )

    private companion object {
        const val NOW = 1_000L
        const val EXPIRES_AT = 601_000L
        const val LAMPORTS = 50_000_000L
        const val INTENT_ID = "ledger_test_intent"
        const val RESPONSE_NONCE = "123e4567-e89b-12d3-a456-426614174000"
        const val SOURCE_ADDRESS = "11111111111111111111111111111111"
        const val RECIPIENT_ADDRESS = "Vote111111111111111111111111111111111111111"
        const val OTHER_ADDRESS = "Stake11111111111111111111111111111111111111"
    }
}
