package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.DocumentPipeline
import app.readflow.core.ExtractedPage
import app.readflow.core.ReadingOrder
import app.readflow.core.TextElement
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TeleOcrOutputTest {
    private val region = Box(0f, 0f, 400f, 200f)
    private fun reference(text: String, x: Float, y: Float = 20f) = TextElement(text, listOf(Box(x, y, x + 20f, y + 10f)))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid or incomplete output must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun financialBenchmarkScoresRecognitionBeforeNarrationPunctuation() {
        val fixture = app.readflow.benchmark.BenchmarkFixtures
        val table = fixture.TABLE_ROWS.joinToString("") { row -> row.joinToString("") { "<fcel>$it" } + "<nl>" }
        val adapted = TeleOcrOutput.adapt(listOf(
            TeleOcrRegion("text", region, content = fixture.PARAGRAPH_LINES.joinToString("\n")),
            TeleOcrRegion("table", region, content = table),
        ))
        val metrics = app.readflow.benchmark.BenchmarkMetrics
        assertEquals(0.0, metrics.characterErrorRate(fixture.OCR_REFERENCE, adapted.recognitionText)!!, 0.0)
        assertTrue(metrics.tableReadingOrderCorrect(adapted.recognitionText))
        val narrated = adapted.elements.joinToString(" ") { it.text }
        assertTrue(metrics.characterErrorRate(fixture.OCR_REFERENCE, narrated)!! > .04)
        assertFalse(metrics.tableReadingOrderCorrect(narrated))
    }

    @Test fun rawRecognitionRetainsRealFinancialPunctuationAndErrors() {
        val adapted = TeleOcrOutput.adapt(listOf(TeleOcrRegion("table", region,
            content = "<fcel>Assets<fcel>\$9.3<nl><fcel>Costs<fcel>\$1,000<nl>")))
        assertEquals("Assets \$9.3 Costs \$1,000", adapted.recognitionText)
        assertTrue(app.readflow.benchmark.BenchmarkMetrics.characterErrorRate("Assets \$9.8 Costs \$1,000", adapted.recognitionText)!! > 0)
    }

    @Test fun officialDetectionFormatScalesCoordinatesAndKeepsModelOrder() {
        val layout = TeleOcrOutput.parseLayout("<box:500 100 900 300><label:text><up>\n<box:50 100 450 300><label:table><right>", 2000f, 1000f)
        assertEquals(Box(1000f, 100f, 1800f, 300f), layout.regions[0].box)
        assertEquals(90, layout.regions[1].angle)
        assertEquals(TeleOcrOutput.TABLE_PROMPT, TeleOcrOutput.promptFor(layout.regions[1]))
        assertTrue(layout.warnings.isEmpty())
    }

    @Test fun polygonRegionsKeepTheirBoundsAndFlagRectangularCropping() {
        val layout = TeleOcrOutput.parseLayout("<box:100 100 800 150 900 400 80 300><label:text><up>", 1000f, 2000f)
        assertEquals(Box(80f, 200f, 900f, 800f), layout.regions.single().box)
        assertEquals(1, layout.warnings.size)
    }

    @Test fun incompleteOrOutOfRangeLayoutIsRejectedInsteadOfDroppingText() {
        val invalid = listOf("", "<box:100 200 300 400><label:text>", "<box:100 200 300 1400><label:text><up>",
            "<box:100 200 100 300><label:text><up>", "<box:100 200 300><label:text><up>",
            "<box:100 200 300 400><label:unexpected><up>", "<box:100 200 300 400><label:text><sideways>",
            "<box:100 200 300 400><label:text><up>\nunfinished")
        invalid.forEach { raw -> rejected { TeleOcrOutput.parseLayout(raw, 1000f, 1000f) } }
        rejected { TeleOcrOutput.parseLayout("<box:0 0 1000 1000><label:text><up>", Float.NaN, 1000f) }
    }

    @Test fun excessiveModelOutputIsBounded() {
        rejected { TeleOcrOutput.parseLayout(List(TeleOcrOutput.MAX_REGIONS + 1) { "<box:0 0 1000 1000><label:text><up>" }.joinToString("\n"), 1000f, 1000f) }
        rejected { TeleOcrOutput.parseTable("x".repeat(TeleOcrOutput.MAX_CONTENT_CHARACTERS + 1)) }
    }

    @Test fun exactWordsIncludingAccentsUseActualReferenceGeometry() {
        val references = listOf(reference("Milankovic's", 10f), reference("theory", 80f), reference("works", 140f))
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "Milanković’s theory works")), references)
        assertEquals("Milanković’s", result.elements.first().text)
        assertEquals(references.map { it.boxes }, result.elements.map { it.boxes })
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun fullSequenceMatchKeepsRepeatedWordsDistinct() {
        val references = listOf(reference("the", 10f), reference("one", 40f), reference("the", 80f), reference("other", 120f))
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "the one the other")), references)
        assertEquals(references.map { it.boxes }, result.elements.map { it.boxes })
    }

    @Test fun ambiguousDuplicatesAndChangedNumbersNeverBorrowWordBoxes() {
        val references = listOf(reference("the", 10f), reference("$9.3", 40f), reference("the", 90f), reference("amount", 120f))
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "the $8.3 amount")), references)
        assertTrue(result.elements[0].boxes.isEmpty())
        assertTrue(result.elements[1].boxes.isEmpty())
        assertEquals(references[3].boxes, result.elements[2].boxes)
        assertTrue(result.warnings.single().contains("2 of 3"))
    }

    @Test fun textOutsideRegionAndMultiwordReferencesAreNotUsedAsWordGeometry() {
        val references = listOf(reference("outside", 500f), reference("two words", 20f))
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "outside two words")), references)
        assertTrue(result.elements.all { it.boxes.isEmpty() })
    }

    @Test fun separateRegionsCannotReuseOneWordBox() {
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "word"), TeleOcrRegion("text", region, content = "word")),
            listOf(reference("word", 20f)))
        assertTrue(result.elements[0].boxes.isNotEmpty())
        assertTrue(result.elements[1].boxes.isEmpty())
    }

    @Test fun missingContentFailsTheWholePageForCallerFallback() {
        rejected { TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "good"), TeleOcrRegion("text", region))) }
        rejected { TeleOcrOutput.adapt(listOf(TeleOcrRegion("text", region, content = "<fcel>oops<nl>"))) }
        assertFalse(TeleOcrOutput.needsRecognition(TeleOcrRegion("image", region)))
    }

    @Test fun otslRowsKeepBlanksAndMergedCellSpans() {
        val table = TeleOcrOutput.parseTable("<fcel>Year<fcel>Income<lcel><nl><fcel>2025<ecel><fcel>100<nl>")
        assertEquals(2, table.rowCount)
        assertEquals(3, table.columnCount)
        assertEquals(2, table.cells.first { it.text == "Income" }.columnSpan)
        assertEquals("", table.cells.first { it.row == 1 && it.column == 1 }.text)
        assertEquals("100", table.cells.first { it.row == 1 && it.column == 2 }.text)
    }

    @Test fun otslTwoDimensionalMergedCellsAreRectangular() {
        val table = TeleOcrOutput.parseTable("<fcel>Assets<lcel><fcel>Value<nl><ucel><xcel><fcel>100<nl>")
        val merged = table.cells.first()
        assertEquals(2, merged.rowSpan)
        assertEquals(2, merged.columnSpan)
        rejected { TeleOcrOutput.parseTable("<fcel>Assets<lcel><nl><ucel><fcel>Bad<nl>") }
    }

    @Test fun inconsistentAndInvalidMergedOtslFail() {
        listOf("<fcel>first<nl><fcel>second<fcel>extra<nl>", "<lcel><fcel>invalid<nl>",
            "<fcel>one<ecel>unexpected<nl>", "<fcel>one<fc<nl>", "<nl>", "<fcel>one<nl>Trailing explanation").forEach { raw ->
            rejected { TeleOcrOutput.parseTable(raw) }
        }
    }

    @Test fun finalOtslRowMayEndAtEosWithoutNewlineDelimiter() {
        val table = TeleOcrOutput.parseTable("<fcel>Year<fcel>Income<nl><fcel>2025<fcel>100")
        assertEquals(2, table.rowCount)
        assertEquals("100", table.cells.last().text)
        assertEquals("100", TeleOcrOutput.parseTable("<fcel>100").cells.single().text)
    }

    @Test fun htmlTablesKeepExplicitHeadersBlankCellsAndRowSpans() {
        val table = TeleOcrOutput.parseTable("<table><tr><th>Year</th><th>Income &amp; gains</th></tr><tr><td rowspan=\"2\">2025</td><td>100</td></tr><tr><td></td></tr></table>")
        assertEquals(3, table.rowCount)
        assertEquals(2, table.columnCount)
        assertTrue(table.cells.first().header)
        assertEquals("Income & gains", table.cells[1].text)
        assertEquals(2, table.cells.first { it.text == "2025" }.rowSpan)
        assertEquals("", table.cells.first { it.row == 2 && it.column == 1 }.text)
    }

    @Test fun missingHtmlCellDoesNotShiftColumnValues() {
        val table = TeleOcrOutput.parseTable("<table><tr><td>A</td><td>B</td></tr><tr><td>10</td></tr></table>")
        assertEquals("", table.cells.first { it.row == 1 && it.column == 1 }.text)
    }

    @Test fun financialComparisonsAndAmpersandsRemainLiteralCellText() {
        val otsl = TeleOcrOutput.parseTable("<fcel>P<E<fcel><5%<fcel>>20%<fcel>R&D &amp; gains<nl>")
        assertEquals(listOf("P<E", "<5%", ">20%", "R&D & gains"), otsl.cells.map { it.text })
        val html = TeleOcrOutput.parseTable("<table><tr><td>P&lt;E</td><td>&lt;5%</td><td>&gt;20%</td><td>R&amp;D &amp; gains</td></tr></table>")
        assertEquals(otsl.cells.map { it.text }, html.cells.map { it.text })
    }

    @Test fun truncatedAndOutOfBoundsHtmlTablesFail() {
        listOf("<table><tr><td>100", "<table><tr><td>100</tr></table>",
            "<table><tr><td rowspan=\"200\">100</td></tr></table>",
            "<table><tr><td colspan=\"999999999\">100</td></tr></table>",
            "<table><tr><td><table><tr><td>Nested</td></tr></table></td></tr></table>").forEach { raw ->
            rejected { TeleOcrOutput.parseTable(raw) }
        }
    }

    @Test fun tableAudioOrderIsRowMajorWithPausesAndOriginalWordGeometry() {
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("table", region, content = "<fcel>Year<fcel>Income<nl><fcel>2025<fcel>$9.3<nl>")),
            listOf(reference("Year", 20f), reference("Income", 150f), reference("2025", 20f, 60f), reference("$9.3", 150f, 60f)))
        assertEquals(listOf("Year;", "Income.", "2025;", "$9.3."), result.elements.map { it.text })
        assertEquals(listOf(0, 0, 1, 1), result.elements.map { it.block })
        assertTrue(result.elements.all { it.boxes.isNotEmpty() })
        assertEquals(1, result.tables.size)
        assertTrue(result.warnings.single().contains("row by row"))
    }

    @Test fun modelRegionOrderAndTitleSurviveAdaptation() {
        val result = TeleOcrOutput.adapt(listOf(TeleOcrRegion("title", Box(200f, 20f, 400f, 80f), content = "First"),
            TeleOcrRegion("text", Box(10f, 0f, 100f, 20f), content = "Second")))
        assertEquals(listOf("First", "Second"), result.elements.map { it.text })
        assertTrue(result.elements[0].heading)
    }

    @Test fun capturedNativeLayoutOutputParsesAllFourRegionsInOrder() {
        val raw = File("../docs/evidence/teleocr/layout-output.txt").readText()
        val layout = TeleOcrOutput.parseLayout(raw, 1000f, 1000f)
        assertEquals(listOf("title", "text", "table", "text"), layout.regions.map { it.type })
        assertEquals(Box(52f, 42f, 697f, 82f), layout.regions[0].box)
        assertEquals(Box(68f, 300f, 915f, 546f), layout.regions[2].box)
        assertTrue(layout.regions.all { it.angle == 0 })
        assertTrue(layout.warnings.isEmpty())
    }

    @Test fun capturedNativeTableValuesKeepRowOrderThroughDocumentPipeline() {
        val layout = TeleOcrOutput.parseLayout(File("../docs/evidence/teleocr/layout-output.txt").readText(), 1000f, 1000f)
        val tableRegion = layout.regions[2].copy(content = File("../docs/evidence/teleocr/table-output.txt").readText())
        val expectedCells = listOf("Year", "Capacity", "Price", "1999", "6 gigs", "$1,200",
            "2003", "120 gigs", "$950", "2006", "250 gigs", "$800")
        // Supply independent word geometry, as ML Kit does in the app. Repeated "gigs"
        // must retain separate positions, and financial punctuation must not change values.
        val references = expectedCells.flatMapIndexed { cellIndex, cell ->
            cell.split(' ').mapIndexed { wordIndex, word ->
                val x = 100f + (cellIndex % 3) * 250f + wordIndex * 45f
                val y = 320f + (cellIndex / 3) * 45f
                TextElement(word, listOf(Box(x, y, x + 40f, y + 20f)))
            }
        }
        val adapted = TeleOcrOutput.adapt(listOf(tableRegion), references)
        val table = adapted.tables.single()
        assertEquals(4, table.rowCount)
        assertEquals(3, table.columnCount)
        assertEquals(expectedCells, table.cells.map { it.text })
        val reorder = object : ReadingOrder {
            override fun order(elements: List<TextElement>, width: Float): List<TextElement> =
                error("A parsed TeleOCR table must bypass geometric reordering")
        }
        val page = DocumentPipeline(reorder).process("native-table-fixture", 0,
            ExtractedPage(adapted.elements, 1000f, 1000f, "TeleOCR", preserveOrder = true))
        assertEquals("Year; Capacity; Price.\n\n1999; 6 gigs; $1,200.\n\n2003; 120 gigs; $950.\n\n2006; 250 gigs; $800.", page.reading)
        assertEquals(references.map { it.boxes }, page.words.map { it.boxes })
    }
}
