package ai.tetsuo.agenc.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaLedgerPathsTest {
    @Test
    fun `initial common scan matches the three supported families without duplicates`() {
        val paths = SolanaLedgerApp.commonAccountPaths(5)

        assertEquals(19, paths.size)
        assertEquals(paths.size, paths.map { it.key }.toSet().size)
        assertEquals("44/501/0/0", paths.first().key)
        assertTrue(paths.any { it.key == "44/501/9" })
        assertTrue(paths.any { it.key == "44/501/0/4" })
    }

    @Test
    fun `exact index exposes live legacy and alternate paths beyond common scan`() {
        val paths = SolanaLedgerApp.accountPathsAtIndex(42)

        assertEquals(
            listOf("44/501/42/0", "44/501/42", "44/501/0/42"),
            paths.map { it.key },
        )
        paths.forEach { assertNotNull(SolanaLedgerApp.pathFromKey(it.key)) }
    }

    @Test
    fun `persisted path parser stays inside supported Solana families`() {
        assertNull(SolanaLedgerApp.pathFromKey("44/501/1/2"))
        assertNull(SolanaLedgerApp.pathFromKey("44/60/0/0"))
        assertNull(SolanaLedgerApp.pathFromKey("44/501/1000001/0"))
    }
}
