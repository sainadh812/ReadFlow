package app.readflow

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.data.Preferences
import app.readflow.dictionary.DictionaryPackPhase
import app.readflow.models.fileHash
import app.readflow.speech.SpeechDistribution
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in setup on an isolated emulator. It creates real documents and downloads real packs. */
@RunWith(AndroidJUnit4::class)
class StoreDemoSeedTest {
    @Test fun seedOriginalDemoDocuments() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit demo setup not enabled", args.getString("demo") == "true")
        assertTrue("Demo setup is restricted to an emulator", Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("sdk") || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = instrumentation.targetContext.applicationContext as ReadFlowApp
        val output = File(app.getExternalFilesDir(null), "store-demo").apply { mkdirs() }
        val report = JSONObject().put("contentLicense", "CC0-1.0")
            .put("description", "Original demonstration documents authored for Read Assist; no user documents.")
            .put("playSpeechDistribution", SpeechDistribution.IS_PLAY_BUILD)
            .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("androidFingerprint", Build.FINGERPRINT).put("abi", Build.SUPPORTED_ABIS.joinToString())
            .put("setupStartedAt", System.currentTimeMillis())
        try {
            app.preferences.set(Preferences(showPerformance = false, kokoroPhonemizer = "misaki"))
            app.preferences.setAppTheme("warm_library")
            val records = JSONArray()
            // Insert the featured book last so the normal newest-first library shows it first.
            val books = listOf(
                Book("Field notes", listOf("Notice the small things" to listOf(
                    "A notebook invites us to notice what is already around us. A leaf catches the afternoon light. A familiar street sounds different after the rain. A small observation can be the beginning of a larger idea.",
                    "Write down one detail, then give it a little space. You do not need to explain everything at once. Return tomorrow and see what has changed. Curiosity grows through attention, one ordinary moment at a time."))),
                Book("Everyday curiosity", listOf("Questions worth keeping" to listOf(
                    "Curiosity begins with a simple question. Why does a shadow change its shape? How does a seed know when to grow? What makes one story stay with us long after we close the book?",
                    "We learn by looking closely, listening carefully, and trying again. A useful answer often leads to another question. Keep a place for these questions, and leave room beside them for discoveries."))),
                Book("A quiet space for thought", listOf(
                    "Make room for a good read" to listOf(
                        "Reading opens a quiet space for thought. A page gives us time to pause, notice a detail, and follow an idea at our own pace. Each sentence adds something new to the picture.",
                        "Listen to the words, or let your eyes move gently across the page. A familiar sentence can sound different when we hear it spoken aloud. Its rhythm gives us another way to understand it.",
                        "There is no hurry to reach the end. Good reading leaves space for curiosity. Turn the page when you are ready, and take the next idea with you."),
                    "Follow your curiosity" to listOf(
                        "Every question is an invitation to explore. A word we have not met before can open a door to another subject. Looking up its meaning helps us return to the sentence with a clearer picture.",
                        "We can read a little, listen a little, and pause whenever an idea deserves more attention. The important thing is to keep the thread of the story in our own hands.",
                        "Small discoveries become part of what we know. They connect one page to the next, and one day to another. Curiosity makes an ordinary reading session feel like a beginning."),
                    "Carry an idea with you" to listOf(
                        "When a reading session ends, one clear idea may stay with us. It can change how we notice the world, how we ask a question, or how we explain something to a friend.",
                        "Return to a favourite page and you may find a detail you missed before. Stories and ideas grow richer when we give them another moment of attention.",
                        "Close the book when you are ready. The next page will still be here. For now, take a useful thought into the rest of your day.")))
            )
            withContext(Dispatchers.IO) {
                books.forEach { book ->
                    val file = File(output, "${book.title}.pdf")
                    writeBook(file, book)
                    val imported = app.documents.import(Uri.fromFile(file))
                    val document = imported.copy(title = book.title)
                    app.database.openHelper.writableDatabase.execSQL(
                        "UPDATE documents SET title=? WHERE id=?", arrayOf(book.title, document.id))
                    // Use the production extraction pipeline; do not fabricate recognized words.
                    repeat(document.pageCount) { app.documents.loadPage(document, it) }
                    records.put(JSONObject().put("title", book.title).put("pages", document.pageCount)
                        .put("sha256", fileHash(file)).put("documentId", document.id))
                }
            }
            report.put("documents", records)
            if (args.getString("models") == "true") {
                withTimeout(20 * 60 * 1000L) {
                    app.models.install("kokoro")
                    app.models.verifyInstalled("kokoro")
                    app.models.install("alignment")
                    app.models.verifyInstalled("alignment")
                }
                report.put("kokoroInstalledAndVerified", true).put("alignmentInstalledAndVerified", true)
            }
            if (args.getString("dictionary") == "true") {
                withTimeout(15 * 60 * 1000L) {
                    app.dictionary.install()
                    val state = app.dictionary.state.first { it.installed || it.phase == DictionaryPackPhase.ERROR }
                    check(state.installed) { state.error ?: "Dictionary installation failed" }
                    check(app.dictionary.lookup("curiosity").entries.isNotEmpty()) { "Dictionary lookup failed" }
                }
                report.put("dictionaryInstalledAndLookupVerified", true)
            }
            report.put("status", "complete")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            report.put("setupFinishedAt", System.currentTimeMillis())
            File(output, "seed.json").writeText(report.toString(2))
        }
    }

    private data class Book(val title: String, val pages: List<Pair<String, List<String>>>)

    private fun writeBook(file: File, book: Book) {
        val document = PdfDocument()
        try {
            book.pages.forEachIndexed { index, (heading, paragraphs) ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(480, 720, index + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.rgb(251, 248, 240))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.rgb(36, 61, 47)
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10f
                canvas.drawText("READ ASSIST  /  ORIGINAL READING COLLECTION", 38f, 42f, paint)
                paint.textSize = 28f
                paint.typeface = Typeface.create("serif", Typeface.BOLD)
                var y = drawWrapped(canvas, heading, paint, 38f, 100f, 404f, 34f) + 24f
                paint.color = Color.rgb(42, 43, 38)
                paint.typeface = Typeface.create("serif", Typeface.NORMAL)
                paint.textSize = 16f
                paragraphs.forEach { paragraph ->
                    y = drawWrapped(canvas, paragraph, paint, 38f, y, 404f, 24f) + 18f
                }
                check(y < 668f) { "Demo page text overflows: ${book.title} page ${index + 1}" }
                paint.color = Color.rgb(95, 104, 94)
                paint.textSize = 10f
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                canvas.drawText("${book.title}   |   ${index + 1}", 38f, 684f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }

    private fun drawWrapped(canvas: android.graphics.Canvas, text: String, paint: Paint,
        x: Float, startY: Float, width: Float, lineHeight: Float): Float {
        var line = ""
        var y = startY
        text.split(' ').forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > width && line.isNotEmpty()) {
                canvas.drawText(line, x, y, paint)
                y += lineHeight
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) { canvas.drawText(line, x, y, paint); y += lineHeight }
        return y
    }
}
