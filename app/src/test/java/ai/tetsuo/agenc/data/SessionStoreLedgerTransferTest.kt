package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferResult
import ai.tetsuo.agenc.model.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreLedgerTransferTest {
    @Test
    fun `only successful Ledger tool completion creates one intent row`() {
        val result = result(lamports = "1")
        val items = mutableListOf<TranscriptItem>()

        assertFalse(appendCoreValidatedLedgerTransfer(items, "bash", false, result))
        assertFalse(appendCoreValidatedLedgerTransfer(items, "request_ledger_transfer", true, result))
        assertTrue(appendCoreValidatedLedgerTransfer(items, "request_ledger_transfer", false, result))
        assertFalse(appendCoreValidatedLedgerTransfer(items, "request_ledger_transfer", false, result))

        assertEquals(1, items.size)
        assertEquals(INTENT_ID, (items.single() as TranscriptItem.LedgerTransfer).result.intentId)
    }

    @Test
    fun `same intent conflict cannot rewrite rendered financial history`() {
        val first = result(lamports = "1")
        val conflict = result(lamports = "2")
        val items = mutableListOf<TranscriptItem>()

        assertTrue(appendCoreValidatedLedgerTransfer(items, "request_ledger_transfer", false, first))
        assertFalse(appendCoreValidatedLedgerTransfer(items, "request_ledger_transfer", false, conflict))

        assertEquals("1", (items.single() as TranscriptItem.LedgerTransfer).result.lamports)
    }

    private fun result(lamports: String): LedgerTransferResult {
        val raw = """{"type":"ledger_solana_transfer_receipt_v1","intentId":"$INTENT_ID","status":"submitted","network":"mainnet-beta","to":"$ADDRESS","lamports":"$lamports","from":"$ADDRESS","signature":"$SIGNATURE","confirmed":false}"""
        return requireNotNull(LedgerTransferResult.parse(raw))
    }

    private companion object {
        const val INTENT_ID = "ledger_12345678"
        const val ADDRESS = "11111111111111111111111111111111"
        val SIGNATURE = Base58.encode(ByteArray(64) { (it + 1).toByte() })
    }
}
