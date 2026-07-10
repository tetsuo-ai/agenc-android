package ai.tetsuo.agenc.ui.chat

import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerTransferRowTest {
    @Test
    fun `submitted row says submitted and formats decimal SOL without implying confirmation`() {
        val signature = Base58.encode(ByteArray(64) { (it + 1).toByte() })
        val result = requireNotNull(
            LedgerTransferResult.parse(
                """{"type":"ledger_solana_transfer_receipt_v1","intentId":"ledger_12345678","status":"submitted","network":"mainnet-beta","to":"$ADDRESS","lamports":"1000000","from":"$ADDRESS","signature":"$signature","confirmed":false}""",
            ),
        )

        assertEquals("Submitted", ledgerTransferStatusLabel(result))
        assertEquals("0.001 SOL", ledgerTransferAmountLabel(result.lamports))
        assertEquals(false, result.confirmed)
    }

    private companion object {
        const val ADDRESS = "11111111111111111111111111111111"
    }
}
