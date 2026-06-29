package ai.tetsuo.agenc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.ChatPalette

/**
 * A small, performant markdown-ish renderer for COMPLETED assistant text.
 *
 * Mirrors the iOS `MarkdownBlock` + `ProseText` + `CodeCard` + `MarkdownTableView` pipeline:
 * the text is split into prose / fenced code / pipe-tables, prose lines support headings, bullets,
 * and inline **bold** / `code`. This is intentionally O(n) per block — the streaming path renders
 * plain [Text] and only swaps to this once the turn completes (see [AssistantText]).
 */

// MARK: - Block model

internal sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/** Split assistant text into prose, ```fenced code```, and `| pipe | tables |`. */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = StringBuilder()

    fun flush() {
        val s = prose.toString().trim()
        if (s.isNotEmpty()) blocks += MdBlock.Prose(s)
        prose.clear()
    }

    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().startsWith("```") -> {
                flush()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append("\n")
                    code.append(lines[i]); i++
                }
                if (i < lines.size) i++ // consume closing fence
                blocks += MdBlock.Code(lang.ifEmpty { null }, code.toString())
            }
            isTableStart(lines, i) -> {
                flush()
                val (table, consumed) = parseTable(lines, i)
                blocks += table
                i += consumed
            }
            else -> {
                if (prose.isNotEmpty()) prose.append("\n")
                prose.append(line); i++
            }
        }
    }
    flush()
    return blocks.ifEmpty { listOf(MdBlock.Prose(text)) }
}

/** A `| … |` line immediately followed by a `|---|---|` separator row. */
private fun isTableStart(lines: List<String>, i: Int): Boolean {
    if (i + 1 >= lines.size) return false
    val head = lines[i].trim()
    val sep = lines[i + 1].trim()
    if (!head.contains("|") || !sep.contains("|") || !sep.contains("-")) return false
    val body = sep.replace("|", "").replace(" ", "")
    return body.isNotEmpty() && body.all { it == '-' || it == ':' }
}

private fun cells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim() }
}

private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int> {
    val header = cells(lines[start])
    val rows = mutableListOf<List<String>>()
    var i = start + 2 // skip header + separator
    while (i < lines.size) {
        val t = lines[i].trim()
        if (!t.contains("|") || t.isEmpty()) break
        rows += cells(lines[i]); i++
    }
    return MdBlock.Table(header, rows) to (i - start)
}

// MARK: - Inline spans (bold / italic / `code`)

/**
 * Parse a single line of inline markdown into an [AnnotatedString]: **bold**, *italic*, and
 * `inline code`. Kept allocation-light — one left-to-right pass, no regex backtracking.
 */
internal fun inlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = raw.length
    while (i < n) {
        val c = raw[i]
        when {
            c == '*' && i + 1 < n && raw[i + 1] == '*' -> {
                val end = raw.indexOf("**", i + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(raw.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '*' -> {
                val end = raw.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(raw.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = Brand.accent,
                            background = Color.White.copy(alpha = 0.06f),
                        ),
                    ) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

// MARK: - Renderers

/** Render completed assistant markdown: prose, fenced code cards, and pipe tables. */
@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Prose -> ProseText(block.text)
                is MdBlock.Code -> CodeBlock(block.language, block.code)
                is MdBlock.Table -> MarkdownTable(block.header, block.rows)
            }
        }
    }
}

/** Lightweight prose: # / ## / ### headings, `- ` bullets, blank-line spacing, inline spans. */
@Composable
internal fun ProseText(text: String) {
    val lines = remember(text) { text.split("\n") }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (raw in lines) {
            val t = raw.trim()
            when {
                t.startsWith("### ") -> Text(
                    inlineMarkdown(t.removePrefix("### ")),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                t.startsWith("## ") -> Text(
                    inlineMarkdown(t.removePrefix("## ")),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                t.startsWith("# ") -> Text(
                    inlineMarkdown(t.removePrefix("# ")),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                t.startsWith("- ") || t.startsWith("* ") -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("•", color = ChatPalette.secondaryText, fontSize = 15.sp)
                    Text(
                        inlineMarkdown(t.drop(2)),
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                t.isEmpty() -> Spacer(Modifier.height(3.dp))
                else -> Text(inlineMarkdown(raw), color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/** A fenced-code card — a subtle filled box, monospace, with a language tag header. */
@Composable
internal fun CodeBlock(language: String?, code: String) {
    val lineCount = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                language ?: "code",
                color = Brand.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "· $lineCount line${if (lineCount == 1) "" else "s"}",
                color = ChatPalette.secondaryText,
                fontSize = 12.sp,
            )
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                code,
                color = Color.White.copy(alpha = 0.88f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            )
        }
    }
}

/** A markdown table rendered as aligned, horizontally-scrollable columns. */
@Composable
internal fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val colCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (c in 0 until colCount) {
                    Text(
                        header.getOrElse(c) { "" },
                        color = ChatPalette.secondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
            Spacer(Modifier.width(1.dp))
            for (row in rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    for (c in 0 until colCount) {
                        Text(
                            inlineMarkdown(row.getOrElse(c) { "" }),
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun MarkdownPreview() {
    AgenCTheme {
        MarkdownText(
            text = """
                ## Capabilities
                Here is what AgenC can **do** for you, with an `inline` example:

                - Read and edit files
                - Run **commands** safely

                | Capability | What it does | Example |
                |---|---|---|
                | Edit | changes files | `edit Foo.kt` |
                | Run | runs a shell | `./gradlew test` |

                ```kotlin
                fun greet() = println("hi")
                ```
            """.trimIndent(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
