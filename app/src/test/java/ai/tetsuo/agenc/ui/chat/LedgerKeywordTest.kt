package ai.tetsuo.agenc.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerKeywordTest {
    @Test
    fun `highlights every exact ledger keyword without consuming punctuation`() {
        val text = "@ledger send, then (@LEDGER)."
        val annotated = ledgerKeywordText(text)

        assertEquals(text, annotated.text)
        assertEquals(
            listOf(0 until 7, 20 until 27),
            annotated.spanStyles.map { it.start until it.end },
        )
        annotated.spanStyles.forEach { span ->
            assertEquals(Color(0xFF6D28D9), span.item.color)
            assertEquals(FontWeight.Bold, span.item.fontWeight)
        }
    }

    @Test
    fun `does not style embedded or extended ledger text`() {
        val annotated = ledgerKeywordText(
            "email@ledger @ledger_wallet old@ledgered ledger@ledger2",
        )

        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `composer transformation preserves cursor offsets and uses visible violet`() {
        val source = AnnotatedString("Move 1 lamport with @ledger now")
        val transformed = LedgerKeywordVisualTransformation.filter(source)

        assertEquals(source.text, transformed.text.text)
        assertEquals(listOf(20 until 27), transformed.text.spanStyles.map { it.start until it.end })
        assertEquals(Color(0xFFB69CFF), transformed.text.spanStyles.single().item.color)
        for (offset in 0..source.length) {
            assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun `recognizes keyword next to unicode punctuation but not unicode letters`() {
        val annotated = ledgerKeywordText("¿@ledger? é@ledger @ledger界 𐐀@ledger @ledger²")

        assertEquals(listOf(1 until 8), annotated.spanStyles.map { it.start until it.end })
    }
}
