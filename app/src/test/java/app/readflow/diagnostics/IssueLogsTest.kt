package app.readflow.diagnostics

import app.readflow.core.*
import app.readflow.data.DocumentEntity
import java.io.*
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IssueLogsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(root: File = temporary.newFolder(), count: Int = 20, bytes: Long = 24L * 1024 * 1024) =
        IssueLogs(root, { mapOf("appVersion" to "test", "runtime" to "test") }, count, bytes)
    private fun input() = IssueInput(originalText = "Research & development.", readingText = "Research & development.", normalizedText = "Research and development.")

    @Test fun successAndCancellationNeverCreateReports() = runBlocking {
        val root = File(temporary.root, "absent")
        val store = store(root)
        assertNull(store.record("SUCCESS", input = input()))
        assertNull(store.record("JUMP", CancellationException("superseded"), input()))
        store.refresh()
        assertFalse(root.exists()); assertTrue(store.reports.value.isEmpty())
    }
    @Test fun failureContainsExactInputAndSurvivesReopen() = runBlocking {
        val root = temporary.newFolder(); val store = store(root)
        val id = store.record("NORMALIZE_TEXT", IllegalArgumentException("unsupported symbol"), input())!!
        val report = store(root).read(id)!!
        assertEquals(input(), report.input)
        assertEquals("NORMALIZE_TEXT", report.stage)
        assertEquals("unsupported symbol", report.failures.single().message)
        assertTrue(report.failures.single().stack.isNotEmpty())
        assertEquals("test", report.environment["appVersion"])
    }
    @Test fun lowConfidenceRecordsTokenScoreThresholdAndSourceMapping() = runBlocking {
        val store = store()
        val error = AlignmentConfidenceException(2, "READ", listOf("word-3"), .03f, .12f)
        val report = store.read(store.record("ALIGN_WORDS", error, input())!!)!!
        assertEquals(.03f, report.alignment!!.confidence, .0001f)
        assertEquals(.12f, report.alignment!!.threshold, .0001f)
        assertEquals(listOf("word-3"), report.alignment!!.sourceIds)
        assertEquals(2, report.alignment!!.tokenIndex)
    }
    @Test fun failureAudioIsRetainedBeforeCacheCleanupAndExportedExactly() = runBlocking {
        val store = store(); val wav = temporary.newFile("chunk.wav")
        val original = ByteArray(256) { it.toByte() }; wav.writeBytes(original)
        val id = store.record("ALIGN_WORDS", IllegalArgumentException("test"), input(), audio = wav)!!
        wav.delete()
        val out = ByteArrayOutputStream(); store.export(id, out)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertEquals(setOf("report.json", "audio.wav"), entries.keys)
        assertArrayEquals(original, entries["audio.wav"])
        val report = Json.decodeFromString<IssueReport>(entries.getValue("report.json").toString(Charsets.UTF_8))
        assertEquals(sha256(original), report.audio.sha256)
    }
    @Test fun oversizedAudioIsOmittedNotTruncatedIntoAnInvalidWav() = runBlocking {
        val wav = temporary.newFile("large.wav")
        RandomAccessFile(wav, "rw").use { it.setLength(IssueLogs.AUDIO_LIMIT + 1) }
        val store = store(); val id = store.record("ALIGN_WORDS", IllegalStateException("test"), audio = wav)!!
        assertFalse(store.read(id)!!.audio.included)
        assertTrue(store.read(id)!!.audio.omission!!.contains("4 MiB"))
    }
    @Test fun textLimitsPreserveUtf16AndDeclareTruncation() = runBlocking {
        val text = "a".repeat(IssueLogs.TEXT_LIMIT - 1) + "\uD83D\uDE00more"
        val store = store(); val id = store.record("PARSE", IllegalArgumentException("test"), IssueInput(originalText = text))!!
        val report = store.read(id)!!
        assertEquals(IssueLogs.TEXT_LIMIT - 1, report.input.originalText!!.length)
        assertTrue("originalText" in report.truncatedFields)
    }
    @Test fun repeatedWarningsDeduplicateButSeparateFailuresDoNot() = runBlocking {
        val store = store()
        val a = store.record("OCR", warnings = listOf("No readable text"), dedupeKey = "page-a")
        assertEquals(a, store.record("OCR", warnings = listOf("No readable text"), dedupeKey = "page-a"))
        assertNotEquals(store.record("PARSE", IllegalArgumentException("bad")), store.record("PARSE", IllegalArgumentException("bad")))
        assertEquals(3, store.reports.value.size)
    }
    @Test fun historyPrunesByCountAndBytes() = runBlocking {
        val root = temporary.newFolder(); val store = store(root, count = 2, bytes = 12000)
        repeat(5) { store.record("PARSE", IllegalArgumentException("bad").apply { stackTrace = emptyArray() }, IssueInput(originalText = "a".repeat(8000))) }
        assertTrue(store.reports.value.size in 1..2)
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 12000)
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }
    @Test fun countLimitAppliesEvenWhenStorageIsAvailable() = runBlocking {
        val store = store(count = 2)
        repeat(5) { store.record("PARSE", IllegalArgumentException("bad")) }
        assertEquals(2, store.reports.value.size)
    }
    @Test fun unwritableStorageNeverMasksTheOriginalFailure() = runBlocking {
        val store = store(temporary.newFile("not-a-directory"))
        assertNull(store.record("PARSE", IllegalArgumentException("original failure"), input()))
        assertNotNull(store.storageFailure.value)
    }
    @Test fun malformedReportsDoNotPreventListingOrNewReports() = runBlocking {
        val root = temporary.newFolder(); val broken = File(root, "11111111-1111-1111-1111-111111111111").apply { mkdirs() }
        File(broken, "report.json").writeText("interrupted")
        val store = store(root); store.refresh(); assertTrue(store.reports.value.isEmpty())
        assertNotNull(store.record("PARSE", IllegalArgumentException("bad")))
        assertEquals(1, store.reports.value.size)
    }
    @Test fun deleteRemovesReportAndAudioWithoutTouchingOtherIssues() = runBlocking {
        val store = store(); val first = store.record("A", IllegalArgumentException("a"))!!
        val second = store.record("B", IllegalArgumentException("b"))!!
        store.delete(first); assertNull(store.read(first)); assertNotNull(store.read(second))
        store.clear(); assertTrue(store.reports.value.isEmpty())
    }
    @Test fun exportRejectsTraversalAndTamperedAudio() = runBlocking {
        val root = temporary.newFolder(); val store = store(root); val wav = temporary.newFile("audio.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = store.record("ALIGN", IllegalStateException("test"), audio = wav)!!
        File(root, "$id/audio.wav").writeBytes(byteArrayOf(3, 2, 1))
        try { store.export(id, ByteArrayOutputStream()); fail("Tampered attachment accepted") } catch (_: IllegalStateException) { }
        assertNull(store.read("../../private"))
    }
    @Test fun sourceUrlOmitsCredentialsQueryAndFragment() {
        assertEquals("https://example.com/article", issueSource("https://user:secret@example.com/article?token=private#section"))
        assertFalse(issueSource("content://provider/private-document-42").contains("42"))
    }
    @Test fun actualCtcFailureProvidesMeasuredScoreToLogger() = runBlocking {
        val speech = SpeechText("a", listOf(SpokenToken("A", listOf("word-a"))), listOf("word-a"))
        val failure = assertThrows(AlignmentConfidenceException::class.java) {
            CtcAlignment().align(Array(12) { FloatArray(32) }, speech, 16000, 4200)
        }
        val store = store()
        val report = store.read(store.record("ALIGN_WORDS", failure, IssueInput(normalizedText = speech.text, spokenTokens = speech.tokens))!!)!!
        assertEquals(1f / 32, report.alignment!!.confidence, .00001f)
        assertEquals("A", report.alignment!!.token)
    }
    @Test fun failedSentenceContextUsesOriginalOffsetsNotStartOfBook() {
        val page = DocumentPipeline().process("doc", 0, ExtractedPage(listOf(TextElement("First sentence. Research & development.", emptyList())), 0f, 0f, "test"))
        val words = page.words.takeLast(3)
        val document = DocumentEntity("doc", "hash", "https://example.com/", "Title", "", "text/html", 1)
        val input = documentIssueInput(document, page, words, EnglishNormalizer().normalize(words))
        assertEquals("Research & development.", input.originalText)
        assertEquals("Research and development.", input.normalizedText)
        assertEquals(words, input.words)
    }
    @Test fun documentDeletionPurgesOnlyAssociatedInputCopies() = runBlocking {
        val store = store()
        val a = store.record("PARSE", IllegalStateException("a"), input().copy(documentId = "doc-a"))!!
        val b = store.record("PARSE", IllegalStateException("b"), input().copy(documentId = "doc-b"))!!
        store.deleteForDocument("doc-a")
        assertNull(store.read(a)); assertNotNull(store.read(b))
    }
    @Test fun slowExportDoesNotBlockRecordingAnotherFailure() = runBlocking {
        val store = store(); val id = store.record("PARSE", IllegalArgumentException("first"))!!
        val began = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) { began.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val export = async(Dispatchers.IO) { store.export(id, output) }
        try {
            assertTrue(began.await(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(2000) { assertNotNull(store.record("PARSE", IllegalArgumentException("second"))) }
        } finally { release.countDown(); export.await() }
    }
}
