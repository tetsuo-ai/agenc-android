package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonHubLedgerResponseTest {
    @Test
    fun `Ledger response uses typed clientResult and an empty compatible answer`() {
        val receipt = LedgerTransferReceipt.submitted(
            intentId = "ledger_12345678",
            responseNonce = "123e4567-e89b-12d3-a456-426614174000",
            to = ADDRESS,
            lamports = 1L,
            from = ADDRESS,
            signature = Base58.encode(ByteArray(64) { (it + 1).toByte() }),
        )

        val payload = elicitationResponsePayload(
            questionId = "ledger_transfer",
            answer = null,
            clientResult = receipt,
        )

        assertTrue(
            payload["answers"]
                ?.get("ledger_transfer")
                ?.get("answers")
                ?.arrayValue
                .orEmpty()
                .isEmpty(),
        )
        assertEquals(receipt.intentId, payload["clientResult"]?.get("intentId")?.stringValue)
        assertEquals(receipt.responseNonce, payload["clientResult"]?.get("responseNonce")?.stringValue)
        assertNull(payload["clientResult"]?.get("confirmed"))
    }

    private companion object {
        const val ADDRESS = "11111111111111111111111111111111"
    }
}
