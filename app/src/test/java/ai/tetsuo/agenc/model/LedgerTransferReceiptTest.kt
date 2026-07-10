package ai.tetsuo.agenc.model

import ai.tetsuo.agenc.ledger.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTransferReceiptTest {
    @Test
    fun `submitted client result round trips exact correlation without confirmation claim`() {
        val receipt = LedgerTransferReceipt.submitted(
            intentId = INTENT_ID,
            responseNonce = NONCE,
            to = ADDRESS,
            lamports = 1_000_000L,
            from = ADDRESS,
            signature = SIGNATURE,
        )

        val json = receipt.toJson()
        assertEquals(receipt, LedgerTransferReceipt.parse(json))
        assertTrue(json.contains("\"responseNonce\":\"$NONCE\""))
        assertTrue(json.contains("\"lamports\":\"1000000\""))
        assertFalse(json.contains("confirmed"))
    }

    @Test
    fun `cancelled client result retains request fields and fixed reason`() {
        val receipt = LedgerTransferReceipt.cancelled(
            intentId = INTENT_ID,
            responseNonce = NONCE,
            to = ADDRESS,
            lamports = 42L,
            reason = "cancelled_on_phone",
        )

        val parsed = LedgerTransferReceipt.parse(receipt.toJson())
        assertEquals(LedgerTransferStatus.Cancelled, parsed?.status)
        assertEquals(INTENT_ID, parsed?.intentId)
        assertEquals(NONCE, parsed?.responseNonce)
        assertEquals(ADDRESS, parsed?.to)
        assertEquals("42", parsed?.lamports)
        assertEquals("cancelled_on_phone", parsed?.reason)
    }

    @Test
    fun `client result parser rejects extensions weak nonce and invalid signature`() {
        val valid = LedgerTransferReceipt.submitted(
            intentId = INTENT_ID,
            responseNonce = NONCE,
            to = ADDRESS,
            lamports = 1L,
            from = ADDRESS,
            signature = SIGNATURE,
        ).toJson()

        assertNull(LedgerTransferReceipt.parse(valid.dropLast(1) + ",\"confirmed\":false}"))
        assertNull(LedgerTransferReceipt.parse(valid.replace(NONCE, "short")))
        assertNull(LedgerTransferReceipt.parse(valid.replace(SIGNATURE, ADDRESS)))
        assertNull(LedgerTransferReceipt.parse(valid.replace("\"1\"", "1")))
    }

    @Test
    fun `Core clean result requires confirmed false and intentionally omits nonce`() {
        val clean = """{"type":"ledger_solana_transfer_receipt_v1","intentId":"$INTENT_ID","status":"submitted","network":"mainnet-beta","to":"$ADDRESS","lamports":"1","from":"$ADDRESS","signature":"$SIGNATURE","confirmed":false}"""

        val parsed = LedgerTransferResult.parse(clean)
        assertNotNull(parsed)
        assertEquals(false, parsed?.confirmed)
        assertNull(LedgerTransferResult.parse(clean.replace("false", "true")))
        assertNull(
            LedgerTransferResult.parse(
                clean.dropLast(1) + ",\"responseNonce\":\"$NONCE\"}",
            ),
        )
    }

    private companion object {
        const val INTENT_ID = "ledger_12345678"
        const val NONCE = "123e4567-e89b-12d3-a456-426614174000"
        const val ADDRESS = "11111111111111111111111111111111"
        val SIGNATURE: String = Base58.encode(ByteArray(64) { (it + 1).toByte() })
    }
}
