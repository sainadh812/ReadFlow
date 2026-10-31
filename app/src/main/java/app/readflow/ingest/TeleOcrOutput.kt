package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.TextElement
import java.text.Normalizer
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Layout coordinates are in the same input-image space as [TeleOcrOutput.adapt]'s reference words. */
data class TeleOcrRegion(val type: String, val box: Box, val angle: Int = 0, val content: String = "")
data class TeleOcrLayout(val regions: List<TeleOcrRegion>, val warnings: List<String> = emptyList())
data class TeleOcrCell(val text: String, val row: Int, val column: Int, val rowSpan: Int = 1,
    val columnSpan: Int = 1, val header: Boolean = false)
/** Empty cells and merged-cell spans are retained even though they contain no spoken words. */
data class TeleOcrTable(val regionIndex: Int, val rowCount: Int, val columnCount: Int, val cells: List<TeleOcrCell>)
data class TeleOcrAdapted(val elements: List<TextElement>, val warnings: List<String>, val tables: List<TeleOcrTable>, val recognitionText: String)

/**
 * TeleOCR uses two passes: layout detection followed by recognition of each cropped region.
 * Format and prompts: https://github.com/caipeng328/TeleOCR/blob/main/TeleOCR/vlm_utils/TeleOCR_client.py
 * This adapter rejects partial structured output so callers can keep the original ML Kit result.
 */
object TeleOcrOutput {
    const val LAYOUT_PROMPT = "\nAnalyze the image layout."
    const val TEXT_PROMPT = "\nPlease output the text content from the image."
    const val TABLE_PROMPT = "\nThis is the image of a table. Please output the table in OTSL format."
    const val MAX_REGIONS = 128
    const val MAX_CONTENT_CHARACTERS = 131_072
    private const val MAX_WORDS = 16_384
    private const val MAX_ROWS = 256
    private const val MAX_COLUMNS = 128
    private const val MAX_CELLS = 8_192
    private val layoutLine = Regex("^<box:([0-9\\s]+)><label:([a-z_]+)><([^>]+)>$", RegexOption.IGNORE_CASE)
    private val token = Regex("\\S+")
    private val otslToken = Regex("<(nl|fcel|ecel|lcel|ucel|xcel)>")
    private val blockTypes = setOf("text", "title", "table", "image", "code", "algorithm", "header", "footer",
        "page_number", "page_footnote", "aside_text", "equation", "equation_block", "ref_text", "list", "phonetic",
        "table_caption", "image_caption", "code_caption", "table_footnote", "image_footnote", "unknown", "seal", "char")
    private val containers = setOf("image", "list", "equation_block")

    fun needsRecognition(region: TeleOcrRegion): Boolean = region.type !in containers

    fun promptFor(region: TeleOcrRegion): String = when (region.type) {
        "table" -> TABLE_PROMPT
        "char" -> "\nThis is a scientific figure. Please extract the table implied by this figure."
        "equation" -> "\nPlease write out the expression of the formula in the image using LaTeX format."
        "code", "algorithm" -> "\nThe image contains a code snippet, please output the parsing result."
        "seal" -> "\nSeal Recognition:"
        else -> TEXT_PROMPT
    }

    fun parseLayout(raw: String, width: Float, height: Float): TeleOcrLayout {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "Invalid OCR image size." }
        require(raw.length <= MAX_CONTENT_CHARACTERS) { "TeleOCR layout exceeded the page limit." }
        val lines = unfence(raw).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty() && lines.size <= MAX_REGIONS) { "TeleOCR returned an empty or oversized layout." }
        var polygon = false
        val regions = lines.map { line ->
            val match = requireNotNull(layoutLine.matchEntire(line)) { "TeleOCR returned an incomplete layout." }
            val coordinates = match.groupValues[1].trim().split(Regex("\\s+")).map {
                requireNotNull(it.toIntOrNull()?.takeIf { value -> value in 0..1000 }) { "TeleOCR returned invalid layout coordinates." }
            }
            require(coordinates.size in 4..64 && coordinates.size % 2 == 0) { "TeleOCR returned an invalid region." }
            polygon = polygon || coordinates.size > 4
            val xs = coordinates.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = coordinates.filterIndexed { i, _ -> i % 2 != 0 }
            val box = Box(xs.min() * width / 1000, ys.min() * height / 1000,
                xs.max() * width / 1000, ys.max() * height / 1000)
            require(box.width > 0 && box.height > 0) { "TeleOCR returned an empty region." }
            val type = match.groupValues[2].lowercase(Locale.ROOT)
            require(type in blockTypes) { "TeleOCR returned an unknown region type." }
            val directions = Regex("[a-z]+").findAll(match.groupValues[3].lowercase(Locale.ROOT))
                .map { it.value }.filter { it in setOf("up", "right", "down", "left") }.toList()
            require(directions.size == 1) { "TeleOCR returned an invalid region orientation." }
            val angle = mapOf("up" to 0, "right" to 90, "down" to 180, "left" to 270).getValue(directions.single())
            TeleOcrRegion(type, box, angle)
        }
        return TeleOcrLayout(regions, if (polygon) listOf("Irregular layout regions use rectangular crops; check text near their edges.") else emptyList())
    }

    /** Keep the returned order in DocumentPipeline; geometric column sorting would scramble tables. */
    fun adapt(regions: List<TeleOcrRegion>, referenceWords: List<TextElement> = emptyList()): TeleOcrAdapted {
        require(regions.size <= MAX_REGIONS && regions.sumOf { it.content.length.toLong() } <= MAX_CONTENT_CHARACTERS) {
            "TeleOCR text exceeded the page limit."
        }
        val elements = mutableListOf<TextElement>()
        val warnings = mutableListOf<String>()
        val tables = mutableListOf<TeleOcrTable>()
        val recognized = mutableListOf<String>()
        val usedReferences = mutableSetOf<Int>()
        var nextBlock = 0
        var unmatched = 0
        regions.forEachIndexed { regionIndex, region ->
            require(region.type in blockTypes && validBox(region.box)) { "TeleOCR returned an invalid region." }
            if (!needsRecognition(region)) return@forEachIndexed
            val content = unfence(region.content)
            require(content.isNotBlank()) { "TeleOCR did not finish reading a region." }
            val words = mutableListOf<OutputWord>()
            if (region.type == "table" || region.type == "char") {
                val table = parseTable(content, regionIndex)
                tables += table
                for (row in 0 until table.rowCount) {
                    val cells = table.cells.filter { it.row == row && it.text.isNotBlank() }.sortedBy { it.column }
                    for ((cellIndex, cell) in cells.withIndex()) {
                        val cellWords = token.findAll(cell.text).map { it.value }.toList()
                        cellWords.forEachIndexed { wordIndex, word ->
                            val ending = if (wordIndex == cellWords.lastIndex) {
                                if (cellIndex < cells.lastIndex) ";" else "."
                            } else ""
                            val displayed = if (ending.isNotEmpty() && word.last() !in ".,;:!?") word + ending else word
                            words += OutputWord(displayed, word, nextBlock, 0, cell.header)
                        }
                    }
                    if (cells.isNotEmpty()) nextBlock++
                }
            } else {
                require(!otslToken.containsMatchIn(content) && !content.contains("<box:") &&
                    !Regex("</?table\\b", RegexOption.IGNORE_CASE).containsMatchIn(content)) { "TeleOCR returned unexpected text formatting." }
                content.lineSequence().forEachIndexed { lineIndex, line ->
                    token.findAll(line).forEach { words += OutputWord(it.value, it.value, nextBlock, lineIndex, region.type == "title") }
                }
                nextBlock++
                if (region.type == "equation") warnings += "Formulas are preserved as recognized; their spoken pronunciation may need correction."
            }
            require(elements.size + words.size <= MAX_WORDS) { "TeleOCR text exceeded the word limit." }
            val references = referenceWords.withIndex().filter { (index, element) ->
                index !in usedReferences && token.findAll(element.text).take(2).count() == 1 &&
                    element.boxes.isNotEmpty() && element.boxes.all { box ->
                        validBox(box) && region.box.intersection(box) >= box.width * box.height * .8f
                    }
            }
            val matches = matchWords(words.map { it.source }, references.map { it.value.text })
            recognized += words.joinToString(" ") { it.source }
            words.forEachIndexed { index, word ->
                val reference = matches[index]?.let(references::get)
                val boxes = reference?.value?.boxes.orEmpty()
                if (reference != null) usedReferences += reference.index else unmatched++
                elements += TextElement(word.text, boxes, word.block, word.line, word.heading)
            }
        }
        require(elements.isNotEmpty()) { "TeleOCR returned no readable text." }
        if (unmatched > 0) warnings += "Exact word highlights are unavailable for $unmatched of ${elements.size} TeleOCR words; those words remain available in text view."
        if (tables.isNotEmpty()) warnings += "Tables are read row by row, with pauses between cells. Blank cells and merged cells are retained; column headings are not repeated for each value."
        return TeleOcrAdapted(elements, warnings.distinct(), tables, recognized.joinToString("\n"))
    }

    fun parseTable(raw: String, regionIndex: Int = 0): TeleOcrTable {
        require(raw.length <= MAX_CONTENT_CHARACTERS) { "TeleOCR table exceeded the page limit." }
        val content = unfence(raw)
        return if (Regex("^<table(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(content)) parseHtmlTable(content, regionIndex)
        else parseOtslTable(content, regionIndex)
    }

    private fun parseOtslTable(content: String, regionIndex: Int): TeleOcrTable {
        val matches = otslToken.findAll(content).toList()
        require(matches.isNotEmpty() && content.substring(0, matches.first().range.first).isBlank()) { "TeleOCR returned an invalid table." }
        val rows = mutableListOf<MutableList<Pair<String, String>>>()
        var row = mutableListOf<Pair<String, String>>()
        matches.forEachIndexed { index, match ->
            val kind = match.groupValues[1]
            val text = content.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: content.length).trim()
            require(kind == "fcel" || text.isEmpty()) { "TeleOCR returned invalid table cell content." }
            require(!Regex("</?[a-z][^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                !Regex("<(?:[felux]c(?:e(?:l)?)?|n(?:l)?)$").containsMatchIn(text)) { "TeleOCR returned an incomplete table token." }
            if (kind == "nl") {
                require(row.isNotEmpty()) { "TeleOCR returned an empty table row." }
                rows += row
                row = mutableListOf()
            } else row += kind to text
            require(rows.size <= MAX_ROWS && row.size <= MAX_COLUMNS) { "TeleOCR table exceeded the grid limit." }
        }
        // Upstream permits the last row without <nl>. The native caller must require EOS;
        // syntax alone cannot distinguish a complete one-cell table from cut-off cell text.
        if (row.isNotEmpty()) rows += row
        require(rows.isNotEmpty() && rows.size <= MAX_ROWS) { "TeleOCR returned an empty or oversized table." }
        val columns = rows.first().size
        require(rows.all { it.size == columns } && rows.size * columns <= MAX_CELLS) { "TeleOCR returned an inconsistent table grid." }
        val cells = mutableListOf<TeleOcrCell>()
        val owners = Array(rows.size) { IntArray(columns) { -1 } }
        for (r in rows.indices) for (c in 0 until columns) {
            val (kind, value) = rows[r][c]
            val owner = when (kind) {
                "fcel", "ecel" -> cells.size.also { cells += TeleOcrCell(cleanCell(Parser.unescapeEntities(value, false)), r, c) }
                "lcel" -> if (c > 0) owners[r][c - 1] else -1
                "ucel" -> if (r > 0) owners[r - 1][c] else -1
                "xcel" -> if (r > 0 && c > 0 && owners[r][c - 1] == owners[r - 1][c]) owners[r][c - 1] else -1
                else -> -1
            }
            require(owner >= 0) { "TeleOCR returned an invalid merged table cell." }
            owners[r][c] = owner
        }
        val lastRows = IntArray(cells.size)
        val lastColumns = IntArray(cells.size)
        val counts = IntArray(cells.size)
        for (r in rows.indices) for (c in 0 until columns) {
            val owner = owners[r][c]
            lastRows[owner] = maxOf(lastRows[owner], r)
            lastColumns[owner] = maxOf(lastColumns[owner], c)
            counts[owner]++
        }
        val spanned = cells.mapIndexed { index, cell ->
            val rowSpan = lastRows[index] - cell.row + 1
            val columnSpan = lastColumns[index] - cell.column + 1
            require(counts[index] == rowSpan * columnSpan) { "TeleOCR returned a nonrectangular merged cell." }
            cell.copy(rowSpan = rowSpan, columnSpan = columnSpan)
        }
        return TeleOcrTable(regionIndex, rows.size, columns, spanned)
    }

    private fun parseHtmlTable(content: String, regionIndex: Int): TeleOcrTable {
        require(Regex("</table>\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(content)) { "TeleOCR did not finish the table." }
        // Jsoup repairs truncated HTML; check closing structure before allowing that repair.
        for (name in listOf("table", "tr", "td", "th")) {
            val opens = Regex("<$name(?:\\s[^>]*|)>", RegexOption.IGNORE_CASE).findAll(content).count()
            val closes = Regex("</$name\\s*>", RegexOption.IGNORE_CASE).findAll(content).count()
            require(opens == closes) { "TeleOCR did not finish the table structure." }
        }
        val document = Jsoup.parseBodyFragment(content)
        val tables = document.select("table")
        require(tables.size == 1) { "TeleOCR returned nested or multiple tables in one region." }
        val rows = tables.single().select("tr")
        require(rows.isNotEmpty() && rows.size <= MAX_ROWS) { "TeleOCR returned an empty or oversized table." }
        val occupied = Array(rows.size) { BooleanArray(MAX_COLUMNS) }
        val cells = mutableListOf<TeleOcrCell>()
        var columns = 0
        rows.forEachIndexed { rowIndex, row ->
            var column = 0
            for (element in row.children().filter { it.tagName() in setOf("td", "th") }) {
                while (column < MAX_COLUMNS && occupied[rowIndex][column]) column++
                fun span(name: String): Int = if (!element.hasAttr(name)) 1 else
                    requireNotNull(element.attr(name).toIntOrNull()?.takeIf { it in 1..MAX_COLUMNS }) { "TeleOCR returned an invalid table span." }
                val rowSpan = span("rowspan")
                val columnSpan = span("colspan")
                require(column + columnSpan <= MAX_COLUMNS && rowIndex + rowSpan <= rows.size) { "TeleOCR returned a table span outside the grid." }
                for (r in rowIndex until rowIndex + rowSpan) for (c in column until column + columnSpan) {
                    require(!occupied[r][c]) { "TeleOCR returned overlapping table cells." }
                    occupied[r][c] = true
                }
                cells += TeleOcrCell(cleanCell(element.text()), rowIndex, column, rowSpan, columnSpan, element.tagName() == "th")
                column += columnSpan
                columns = maxOf(columns, column)
                require(cells.size <= MAX_CELLS) { "TeleOCR table exceeded the cell limit." }
            }
        }
        require(columns > 0 && rows.size * columns <= MAX_CELLS) { "TeleOCR returned an empty or oversized grid." }
        // Explicitly keep omitted positions; they must not shift later values into a different column.
        rows.indices.forEach { r -> (0 until columns).filter { !occupied[r][it] }.forEach { c -> cells += TeleOcrCell("", r, c) } }
        return TeleOcrTable(regionIndex, rows.size, columns, cells.sortedWith(compareBy<TeleOcrCell> { it.row }.thenBy { it.column }))
    }

    private data class OutputWord(val text: String, val source: String, val block: Int, val line: Int, val heading: Boolean)

    private fun matchWords(output: List<String>, references: List<String>): Map<Int, Int> {
        val keys = output.map(::wordKey)
        val referenceKeys = references.map(::wordKey)
        if (keys == referenceKeys) return keys.indices.associateWith { it }
        val outputCounts = keys.groupingBy { it }.eachCount()
        val referenceIndices = referenceKeys.withIndex().groupBy({ it.value }, { it.index })
        return keys.withIndex().mapNotNull { (index, key) ->
            referenceIndices[key]?.singleOrNull()?.takeIf { key.isNotEmpty() && outputCounts[key] == 1 }?.let { index to it }
        }.toMap()
    }

    private fun wordKey(text: String): String = Normalizer.normalize(text.trim().replace('’', '\'').replace('‘', '\''), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    private fun validBox(box: Box): Boolean = listOf(box.left, box.top, box.right, box.bottom).all(Float::isFinite) && box.width > 0 && box.height > 0
    // OTSL cells contain literal text, not HTML. Parsing them as markup loses comparisons such as P<E.
    private fun cleanCell(text: String): String = text.replace(Regex("\\s+"), " ").trim()
    private fun unfence(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        require(text.endsWith("```") && text.indexOf('\n') in 3 until text.length - 3) { "TeleOCR returned incomplete formatted output." }
        return text.substring(text.indexOf('\n') + 1, text.length - 3).trim()
    }
}
