package app.readflow.diagnostics

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TechnicalDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "PRIVATE_book_Account_123"
    private fun report(stage: String = "NORMALIZE_TEXT") = IssueReport(
        id = secret, timestampMs = 1234, stage = stage, severity = "ERROR",
        environment = mapOf("appVersion" to "0.2.4", "versionCode" to "20", "device" to secret,
            "android" to "17 API 37", "abis" to "arm64-v8a", "heapLimitMiB" to "256", "inference" to secret),
        input = IssueInput(documentId = secret, title = secret, source = "https://secret.example/$secret",
            originalText = secret, readingText = secret, normalizedText = secret, requestedWordId = secret,
            details = mapOf("model" to "kokoro-misaki", "modelVersion" to "v0.19", "voice" to "0", "documentSha256" to secret,
                "synthesis" to "steps=5;backend=cpu;speechThreads=4;alignmentThreads=2;spinning=false;phonemizer=$secret",
                "downloadPhase" to secret, "generation" to secret, "audioKey" to secret)),
        failures = listOf(
            IssueFailure("app.readflow.core.SpeechPreparationException", secret,
                listOf("app.readflow.core.SpeechPlanner.prepare(SpeechPlanning.kt:42)", "app.readflow.Reader.read(/storage/emulated/0/$secret:23)")),
            IssueFailure("app.readflow.core.NormalizationException", secret,
                listOf("app.readflow.core.EnglishNormalizer.normalize(Normalization.kt:34)", "evil.$secret.read(Book.kt:1)")),
            IssueFailure(secret, secret, listOf(secret)),
        ), warnings = listOf(secret), normalization = NormalizationFailure(listOf(secret), secret, secret),
        audio = IssueAudio(true, 1024, secret), dedupeKey = secret, truncatedFields = listOf(secret), privacy = secret,
    )

    @Test fun outboundSchemaNeverSerializesPrivateInputsMessagesNamesOrUnknownFields() {
        val event = TechnicalDiagnostics.from(report())
        val encoded = Json.encodeToString(TechnicalDiagnosticEvent.serializer(), event)
        assertFalse(encoded.contains(secret)); assertFalse(encoded.contains("secret.example"))
        for (excluded in listOf("SpeechPlanning.kt", "Normalization.kt", "documentId", "audioKey", "originalText", "timestampMs", "phonemizer", "downloadPhase"))
            assertFalse("Leaked $excluded", encoded.contains(excluded))
        assertEquals("NORMALIZATION", event.category)
        assertEquals("kokoro-misaki", event.configuration["model"])
        assertEquals("v0.19", event.configuration["modelVersion"])
        assertEquals("4", event.configuration["speechThreads"])
        assertEquals("2", event.configuration["alignmentThreads"])
        assertEquals("cpu", event.configuration["backend"])
        assertEquals("false", event.configuration["allowSpinning"])
        assertEquals("37", event.environment["androidApi"])
        assertEquals(1, event.failures.first().frames.size)
        assertEquals("OtherFailure", event.failures.last().type)
        val frame = event.failures.first().frames.single().asStackTraceElement()
        assertEquals("app.readflow.core.SpeechPlanner", frame.className)
        assertEquals("prepare", frame.methodName); assertEquals(42, frame.lineNumber); assertNull(frame.fileName)
    }

    @Test fun allowlistedKeysWithInvalidValuesDoNotBypassPrivacy() {
        val original = report()
        val unsafe = original.copy(stage = secret, severity = secret,
            environment = original.environment.mapValues { secret },
            input = original.input.copy(details = original.input.details.mapValues { secret } + mapOf(
                "speed" to "NaN", "voice" to "-1", "sampleCount" to "9999999999999999999999",
                "speechThreads" to "1024", "synthesis" to "backend=$secret;alignmentThreads=2-$secret;spinning=$secret")))
        val event = TechnicalDiagnostics.from(unsafe)
        assertEquals("OTHER", event.stage); assertEquals("ERROR", event.severity)
        assertTrue(event.environment.isEmpty()); assertTrue(event.configuration.isEmpty())
        assertFalse(TechnicalDiagnostics.summary(listOf(unsafe)).contains(secret))
    }

    @Test fun processExitKeepsNumericCauseAndMemoryWithoutProcessNamesOrIdentifiers() {
        val original = report("ANDROID_PROCESS_EXIT")
        val exit = original.copy(input = original.input.copy(details = mapOf(
            "androidReason" to "5", "status" to "11", "pssKiB" to "1350000", "rssKiB" to "1800000",
            "processName" to secret, "exitTimestampMs" to "1792952677303", "inputAvailability" to secret,
        )))
        val event = TechnicalDiagnostics.from(exit)
        assertEquals("PROCESS_EXIT_5", event.code)
        assertEquals(mapOf("androidReason" to "5", "status" to "11", "pssKiB" to "1350000", "rssKiB" to "1800000"), event.configuration)
        val publicBatch = DiagnosticBatches.prepare(listOf(exit.copy(id = "safe-event-id")), false).single().body
        assertTrue(publicBatch.contains("1350000")); assertTrue(publicBatch.contains("1800000"))
        assertFalse(publicBatch.contains(secret)); assertFalse(publicBatch.contains("processName"))
        assertFalse(publicBatch.contains("exitTimestampMs"))
    }

    @Test fun invalidProcessValuesAreOmittedAndExitFieldsOnlyApplyToExitEvents() {
        val values = mapOf("androidReason" to "999", "status" to secret, "pssKiB" to "-1", "rssKiB" to "Infinity")
        assertTrue(TechnicalDiagnostics.from(report("ANDROID_PROCESS_EXIT").copy(input = IssueInput(details = values))).configuration.isEmpty())
        assertTrue(TechnicalDiagnostics.from(report().copy(input = IssueInput(details = mapOf("androidReason" to "5")))).configuration.isEmpty())
    }

    @Test fun supportSummaryOnlyIncludesLatestFiveSanitizedEvents() {
        val reports = (1..8).map { report().copy(timestampMs = it.toLong(), input = IssueInput(details = mapOf("voice" to "$it"))) }
        val encoded = TechnicalDiagnostics.summary(reports)
        val events = Json.decodeFromString<List<TechnicalDiagnosticEvent>>(encoded)
        assertEquals(listOf("8", "7", "6", "5", "4"), events.map { it.configuration["voice"] })
        assertFalse(encoded.contains(secret))
        assertEquals("No saved technical failures are available.", TechnicalDiagnostics.summary(emptyList()))
    }

    @Test fun repeatedSentenceFailuresDeduplicateRegardlessOfInputAndErrorMessage() {
        val limiter = DiagnosticRateLimiter(duplicateWindowMs = 100, budgetWindowMs = 1000, maxEvents = 5)
        val first = TechnicalDiagnostics.from(report())
        val nextSentence = TechnicalDiagnostics.from(report().copy(id = "different-id", input = IssueInput(originalText = "Another sentence.",
            details = report().input.details), failures = report().failures.map { it.copy(message = "Different rejected word") }))
        assertTrue(limiter.shouldReport(first, 0))
        assertFalse(limiter.shouldReport(nextSentence, 99))
        assertTrue(limiter.shouldReport(nextSentence, 100))
    }

    @Test fun rateBudgetBoundsDistinctFailuresAndRecoversAfterItsWindow() {
        val limiter = DiagnosticRateLimiter(duplicateWindowMs = 100, budgetWindowMs = 1000, maxEvents = 2)
        val a = TechnicalDiagnostics.from(report())
        val b = TechnicalDiagnostics.from(report("LOAD_MODEL"))
        val c = TechnicalDiagnostics.from(report("ALIGN_WORDS"))
        assertTrue(limiter.shouldReport(a, 0)); assertTrue(limiter.shouldReport(b, 1))
        assertFalse(limiter.shouldReport(c, 2)); assertFalse(limiter.shouldReport(a, 101))
        assertTrue(limiter.shouldReport(c, 1000))
        limiter.clear(); assertTrue(limiter.shouldReport(a, 1001))
        assertTrue(limiter.shouldReport(b, 0)) // Reset after clock origin changes.
        assertFalse(limiter.shouldReport(c, -1))
    }

    @Test fun callbackRunsOnlyAfterDurableSaveAndReporterFailureCannotLoseLocalReport() = runBlocking {
        val root = temporary.newFolder()
        var called = 0
        var durableWhenCalled = false
        val store = IssueLogs(root, { emptyMap() }, onReport = { report ->
            called++
            durableWhenCalled = File(root, "${report.id}/report.json").isFile && !File(root, "${report.id}.partial").exists()
            throw AssertionError("Reporter failed")
        })
        val id = store.record("SYNTHESIZE", IllegalStateException(secret))!!
        assertTrue(durableWhenCalled)
        assertEquals(1, called); assertNotNull(store.read(id)); assertNull(store.storageFailure.value)
    }

    @Test fun failedSaveAndDuplicateDoNotNotifyAgain() = runBlocking {
        var called = 0
        val failed = IssueLogs(temporary.newFile(), { emptyMap() }, onReport = { called++ })
        assertNull(failed.record("SYNTHESIZE", IllegalStateException(secret)))
        assertEquals(0, called)
        val store = IssueLogs(temporary.newFolder(), { emptyMap() }, onReport = { called++ })
        val first = store.record("OCR", warnings = listOf(secret), dedupeKey = "one")
        assertEquals(first, store.record("OCR", warnings = listOf(secret), dedupeKey = "one"))
        assertEquals(1, called)
    }

    @Test fun callbackCannotRecursivelyReportItsOwnFailure() = runBlocking {
        var called = 0
        lateinit var store: IssueLogs
        store = IssueLogs(temporary.newFolder(), { emptyMap() }, onReport = {
            called++; store.recordCrash(IllegalStateException("Callback failure"))
        })
        assertNotNull(store.record("SYNTHESIZE", IllegalStateException(secret)))
        assertEquals(1, called); assertEquals(2, store.reports.value.size)
    }
}
