package ai.tetsuo.agenc.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Exact `@ledger` keyword styling shared by the composer and sent user messages.
 *
 * The boundary contract intentionally mirrors Core's authorization token: a letter, number, or
 * underscore cannot touch either side. Styling never changes the underlying string, so selections,
 * IME composition and cursor offsets remain stable while the user types or edits a prompt.
 */
private const val LedgerKeyword = "@ledger"

private val DisplayLedgerKeywordStyle = SpanStyle(
    color = Color(0xFF6D28D9),
    fontWeight = FontWeight.Bold,
)

private val InputLedgerKeywordStyle = SpanStyle(
    color = Color(0xFFB69CFF),
    fontWeight = FontWeight.Bold,
)

internal fun ledgerKeywordText(
    text: String,
    style: SpanStyle = DisplayLedgerKeywordStyle,
): AnnotatedString = buildAnnotatedString {
    append(text)
    exactLedgerKeywordRanges(text).forEach { range ->
        addStyle(
            style = style,
            start = range.first,
            end = range.last + 1,
        )
    }
}

private fun exactLedgerKeywordRanges(text: String): Sequence<IntRange> = sequence {
    var searchFrom = 0
    while (searchFrom <= text.length - LedgerKeyword.length) {
        val start = text.indexOf(LedgerKeyword, startIndex = searchFrom, ignoreCase = true)
        if (start < 0) return@sequence

        val end = start + LedgerKeyword.length
        val hasWordBefore = start > 0 && text.codePointBefore(start).isCoreLedgerWordCodePoint()
        val hasWordAfter = end < text.length && text.codePointAt(end).isCoreLedgerWordCodePoint()
        if (!hasWordBefore && !hasWordAfter) yield(start until end)

        searchFrom = end
    }
}

/** JavaScript's `\p{L}\p{N}_` boundary contract, including supplementary Unicode code points. */
private fun Int.isCoreLedgerWordCodePoint(): Boolean {
    if (this == '_'.code || Character.isLetter(this)) return true
    return when (Character.getType(this)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        -> true
        else -> false
    }
}

/** A style-only transformation: [OffsetMapping.Identity] is safe because no glyphs are inserted. */
internal object LedgerKeywordVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = ledgerKeywordText(text.text, InputLedgerKeywordStyle),
        offsetMapping = OffsetMapping.Identity,
    )
}
