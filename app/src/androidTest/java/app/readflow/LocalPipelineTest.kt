package app.readflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.data.*
import app.readflow.ingest.LocalExtraction
import app.readflow.ingest.ReadabilityArticles
import app.readflow.core.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).apply {
        instrumentation.context.assets.open("fixtures/$name").use { input -> outputStream().use { input.copyTo(it) } }
    }
    @Test fun selectableScannedMixedBlankAndPageNumberOnly() = runBlocking {
        val extractor = LocalExtraction()
        for (name in listOf("selectable", "scanned", "mixed", "page-number-only", "two-column")) {
            val page = extractor.extract(fixture("$name.pdf").path, 0)
            assertTrue("$name has readable words", page.elements.size > 8)
            assertTrue("$name has genuine geometry", page.elements.all { it.boxes.isNotEmpty() })
            if (name == "page-number-only") assertTrue(page.method.contains("OCR"))
        }
        assertTrue(extractor.extract(fixture("blank.pdf").path, 0).elements.isEmpty())
        assertTrue(extractor.extract(fixture("rotated.png").path, 0, 270).elements.size > 8)
        try { extractor.extract(fixture("corrupt.pdf").path, 0); fail("Corrupt PDF accepted") } catch (_: Exception) { }
    }
    @Test fun wordPositionSurvivesDatabaseReopen() = runBlocking {
        val database = ReadFlowDatabase.open(context)
        val doc = DocumentEntity("position-fixture", "hash", "test", "Fixture", "", "text/plain", 2)
        database.dao().insertDocument(doc)
        database.dao().putPosition(ReadingPosition(doc.id, 1, "stable-word-42"))
        database.close()
        val reopened = ReadFlowDatabase.open(context)
        assertEquals("stable-word-42", reopened.dao().position(doc.id)?.wordId)
        assertEquals(1, reopened.dao().position(doc.id)?.page)
        reopened.dao().deleteDocument(doc.id); reopened.close()
    }
    @Test fun originalPdfRendersWithoutFirstExtractingItsText() = runBlocking {
        val extractor = LocalExtraction()
        val bitmap = extractor.render(fixture("selectable.pdf").path, 0)
        try {
            assertTrue(bitmap.width > 600)
            var ink = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                if (bitmap.getPixel(x, y) and 0x00ffffff != 0x00ffffff) ink++
            }
            assertTrue("PDF preview must contain rendered text", ink > 20)
        } finally { bitmap.recycle() }
    }
    @Test fun savedWebpageUsesSandboxedReadabilityAndDropsScripts() = runBlocking {
        val html = instrumentation.context.assets.open("fixtures/article.html").bufferedReader().use { it.readText() }
        val article = ReadabilityArticles(context).extractHtml(html)
        assertFalse(article.sanitizedHtml.contains("<script"))
        assertFalse(article.sanitizedHtml.contains("<iframe"))
        assertTrue(article.page.elements.any { it.text.contains("quiet space") })
    }
    @Test fun savedWebpagePunctuationPreparesSpeechWithSourceMappings() = runBlocking {
        val html = instrumentation.context.assets.open("fixtures/web-punctuation.html").bufferedReader().use { it.readText() }
        val article = ReadabilityArticles(context).extractHtml(html)
        val page = DocumentPipeline().process("web-punctuation", 0, article.page)
        val chunks = SpeechPlanner().prepare(page.words).toList()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.speech.text.contains("Research and development") })
        assertEquals(page.words.map { it.id }, chunks.flatMap { it.speech.sourceIds })
        assertTrue(page.reading.contains("Research & development"))
    }
}
