package app.readflow.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DocumentReadingOrderTest {
    private val upright = listOf(
        TextElement("First", listOf(Box(40f, 80f, 90f, 105f)), 0, 0),
        TextElement("line.", listOf(Box(110f, 80f, 160f, 105f)), 0, 0),
        TextElement("Second", listOf(Box(40f, 130f, 105f, 155f)), 0, 1),
        TextElement("line.", listOf(Box(125f, 130f, 175f, 155f)), 0, 1),
    )

    private fun extracted(elements: List<TextElement>, transform: Transform, width: Float, height: Float) =
        ExtractedPage(elements.map { it.copy(boxes = it.boxes.map(transform::map)) }, width, height, "OCR", transform = transform)

    private fun checkWordsAndSourceGeometry(extracted: ExtractedPage, expected: List<String>): PageContent {
        val page = DocumentPipeline().process("rotated-fixture", 0, extracted)
        assertEquals(expected, page.words.map { it.text })
        page.words.forEach { word ->
            val original = extracted.elements.single { it.text == word.text && it.line == word.line }
            assertSame("The output keeps the original source box list", original.boxes, word.boxes)
            assertEquals(word.text, page.reading.substring(word.start, word.end))
        }
        assertEquals(extracted.transform, page.imageToPage)
        assertEquals(extracted.width, page.width, 0f)
        assertEquals(extracted.height, page.height, 0f)
        return page
    }

    @Test fun quarterTurn90OrdersBothLinesAndWordsInUprightCoordinates() {
        val page = extracted(upright.reversed(), Transform(0f, -1f, 1f, 0f, 0f, 600f), 900f, 600f)
        checkWordsAndSourceGeometry(page, listOf("First", "line.", "Second", "line."))
    }

    @Test fun quarterTurn270OrdersBothLinesAndWordsInUprightCoordinates() {
        val page = extracted(upright.reversed(), Transform(0f, 1f, -1f, 0f, 900f, 0f), 900f, 600f)
        checkWordsAndSourceGeometry(page, listOf("First", "line.", "Second", "line."))
    }

    @Test fun rotatedScaledSparseColumnsUseTheWholeProcessedPageWidth() {
        // Text occupies only x=120..360 of the 600px-wide upright page. Its own
        // bounding width would put the gutter inside the left-hand column.
        val columns = (0..2).flatMap { row -> listOf(
            TextElement("R$row", listOf(Box(320f, 100f + row * 50f, 360f, 125f + row * 50f)), row * 2 + 1, row * 2 + 1),
            TextElement("L$row", listOf(Box(120f, 100f + row * 50f, 260f, 125f + row * 50f)), row * 2, row * 2),
        ) }
        val expected = listOf("L0", "L1", "L2", "R0", "R1", "R2")
        checkWordsAndSourceGeometry(extracted(columns, Transform(0f, -2f, 3f, 0f, 0f, 1200f), 2700f, 1200f), expected)
        checkWordsAndSourceGeometry(extracted(columns, Transform(0f, 2f, -3f, 0f, 2700f, 0f), 2700f, 1200f), expected)
    }

    @Test fun sourceScaleAndTranslationNormalizeGeometryAgainstPageBounds() {
        var receivedWidth = 0f
        var receivedBox: Box? = null
        val order = object : ReadingOrder {
            override fun order(elements: List<TextElement>, width: Float): List<TextElement> {
                receivedWidth = width
                receivedBox = elements.single().boxes.single()
                return elements
            }
        }
        val page = extracted(listOf(upright.first()), Transform(2f, 0f, 0f, 3f, 100f, 200f), 1400f, 2600f)
        val result = DocumentPipeline(order).process("scaled", 0, page)
        assertEquals(700f, receivedWidth, .001f)
        assertEquals(90f, receivedBox!!.left, .001f)
        assertEquals(146.66667f, receivedBox!!.top, .001f)
        assertSame(page.elements.single().boxes, result.words.single().boxes)
    }

    @Test fun identityKeepsExistingOrderingInputsAndNativeCoordinates() {
        val columns = (0..5).map { index -> TextElement("$index", listOf(Box(
            if (index % 2 == 0) 20f else 330f, (index / 2) * 30f,
            if (index % 2 == 0) 200f else 550f, (index / 2) * 30f + 20f)), index, index) }
        checkWordsAndSourceGeometry(ExtractedPage(columns, 600f, 800f, "Embedded PDF text"), listOf("0", "2", "4", "1", "3", "5"))
        var received: List<TextElement>? = null
        val order = object : ReadingOrder {
            override fun order(elements: List<TextElement>, width: Float): List<TextElement> {
                received = elements
                assertEquals(600f, width, 0f)
                return elements
            }
        }
        DocumentPipeline(order).process("identity", 0, ExtractedPage(upright, 600f, 900f, "native"))
        assertSame(upright, received)
    }

    @Test fun equalTextAndGeometryStillRestoreDistinctOriginalOccurrences() {
        val first = TextElement("same", listOf(Box(40f, 60f, 80f, 90f)))
        val second = first.copy(boxes = first.boxes.toMutableList())
        assertEquals(first, second)
        assertNotSame(first.boxes, second.boxes)
        val page = ExtractedPage(listOf(first, second), 600f, 900f, "OCR", transform = Transform(2f, 0f, 0f, 2f))
        val result = DocumentPipeline().process("duplicates", 0, page)
        assertEquals(2, result.words.size)
        assertSame(first.boxes, result.words[0].boxes)
        assertSame(second.boxes, result.words[1].boxes)
        assertNotEquals(result.words[0].id, result.words[1].id)
    }

    @Test fun modelTableOrderKeepsEachFinancialValueBesideItsRowLabel() {
        val texts = listOf("Assets", "$2,000", "Liabilities", "$1,000", "Equity", "$1,000.")
        val cells = texts.mapIndexed { index, text ->
            val left = if (index % 2 == 0) 40f else 360f
            val top = 100f + (index / 2) * 50f
            TextElement(text, listOf(Box(left, top, left + 140f, top + 25f)), index, index / 2)
        }
        val baseline = ExtractedPage(cells, 600f, 800f, "Latin OCR")
        val geometryOrder = DocumentPipeline().process("table", 0, baseline)
        assertEquals(listOf("Assets", "Liabilities", "Equity", "$2,000", "$1,000", "$1,000."),
            geometryOrder.words.map { it.text })
        val modelOrder = baseline.copy(method = "TeleOCR", preserveOrder = true)
        checkWordsAndSourceGeometry(modelOrder, texts)
    }

    @Test fun rotatedModelOrderBypassesColumnHeuristicsAndKeepsSourceGeometry() {
        // A model may place a sidebar after the body even though it is physically
        // above the body. Rotation must not cause the geometric sorter to override it.
        val modelSequence = listOf(
            TextElement("Main", listOf(Box(50f, 300f, 110f, 325f)), 0, 0),
            TextElement("paragraph.", listOf(Box(120f, 300f, 240f, 325f)), 0, 0),
            TextElement("Sidebar.", listOf(Box(350f, 80f, 480f, 105f)), 1, 1),
        )
        val page = extracted(modelSequence, Transform(0f, -2f, 3f, 0f, 0f, 1200f), 2700f, 1200f)
            .copy(method = "TeleOCR", preserveOrder = true)
        val result = checkWordsAndSourceGeometry(page, listOf("Main", "paragraph.", "Sidebar."))
        assertEquals("Main paragraph.\n\nSidebar.", result.reading)
        assertEquals(3, result.words.map { it.id }.toSet().size)
    }

    @Test fun savedPageRetainsTableSpansHeadersAndGeometryWhenReopened() {
        val table = RecognizedTable(Box(40f, 100f, 550f, 240f), 3, 2, listOf(
            RecognizedCell("Assets", 0, 0, columnSpan = 2, header = true),
            RecognizedCell("Cash", 1, 0, rowSpan = 2),
            RecognizedCell("$2,000", 1, 1),
            RecognizedCell("", 2, 1),
        ))
        val extracted = ExtractedPage(upright, 600f, 900f, "TeleOCR", preserveOrder = true, tables = listOf(table))
        val original = DocumentPipeline().process("saved-table", 0, extracted)
        val restored = Json.decodeFromString<PageContent>(Json.encodeToString(PageContent.serializer(), original))
        assertEquals(original, restored)
        assertEquals(listOf(table), restored.tables)
    }

    @Test fun upgradeReadsPreviouslySavedPagesWithoutTableFields() {
        val previous = """{"id":"page-id","documentId":"book-id","index":55,"original":"Original text","reading":"Original text","words":[],"paragraphs":[],"width":600.0,"height":800.0,"extraction":"Latin OCR"}"""
        val restored = Json.decodeFromString<PageContent>(previous)
        assertEquals("page-id", restored.id)
        assertEquals("Original text", restored.reading)
        assertEquals(55, restored.index)
        assertTrue(restored.tables.isEmpty())
        assertTrue(restored.layout.isEmpty())
        assertEquals(Transform(), restored.imageToPage)
    }
}
