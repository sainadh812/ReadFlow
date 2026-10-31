package app.readflow.diagnostics

import app.readflow.core.DocumentPipeline
import app.readflow.core.ExtractedPage
import app.readflow.core.SpeechPlanner
import app.readflow.core.SpeechPreparationException
import app.readflow.core.TextElement
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticSubmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun event(stage: String = "SYNTHESIZE") = TechnicalDiagnosticEvent(
        stage = stage, severity = "ERROR", category = "SPEECH", code = "${stage}_IllegalStateException",
        environment = emptyMap(), configuration = mapOf("model" to "kokoro-misaki"),
        failures = listOf(TechnicalFailure("java.lang.IllegalStateException",
            listOf(TechnicalFrame("app.readflow.speech.misaki.MisakiFrontend", "phonemize", 39)))),
    )

    @Test fun synchronousFailureAllowsTheNextOccurrenceToRetryWithoutExposingTheException() {
        var attempts = 0
        val submission = DiagnosticSubmission(sink = {
            if (++attempts == 1) error("PRIVATE backend details must stay inside the submission boundary")
        })
        val event = event()

        assertEquals(DiagnosticSubmissionOutcome.Failed, submission.submit(event, 0))
        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(event, 1))
        assertEquals(DiagnosticSubmissionOutcome.Suppressed, submission.submit(event, 2))
        assertEquals(2, attempts)
    }

    @Test fun sdkLinkageFailureDoesNotEscapeOrPreventTheNextSubmission() {
        var attempts = 0
        val submission = DiagnosticSubmission(sink = {
            if (++attempts == 1) throw LinkageError("PRIVATE SDK linkage details")
        })
        val event = event()

        assertEquals(DiagnosticSubmissionOutcome.Failed, submission.submit(event, 0))
        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(event, 1))
        assertEquals(2, attempts)
    }

    @Test fun failedSubmissionDoesNotReleaseAnUnrelatedSuccessfulSignature() {
        val successful = event("NORMALIZE_TEXT")
        val retrying = event()
        var retryAttempts = 0
        val received = mutableListOf<TechnicalDiagnosticEvent>()
        val submission = DiagnosticSubmission(sink = { event ->
            if (event == retrying && ++retryAttempts == 1) error("Temporary backend failure")
            received += event
        })

        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(successful, 0))
        assertEquals(DiagnosticSubmissionOutcome.Failed, submission.submit(retrying, 1))
        assertEquals(DiagnosticSubmissionOutcome.Suppressed, submission.submit(successful, 2))
        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(retrying, 3))
        assertEquals(listOf(successful, retrying), received)
    }

    @Test fun repeatedBackendFailuresStillExhaustTheBudgetAndRecoverAfterItsWindow() {
        var attempts = 0
        val submission = DiagnosticSubmission(
            sink = { if (++attempts <= 2) error("Unavailable backend") },
            limiter = DiagnosticRateLimiter(duplicateWindowMs = 1000, budgetWindowMs = 100, maxEvents = 2),
        )
        val event = event()

        assertEquals(DiagnosticSubmissionOutcome.Failed, submission.submit(event, 0))
        assertEquals(DiagnosticSubmissionOutcome.Failed, submission.submit(event, 1))
        assertEquals(DiagnosticSubmissionOutcome.Suppressed, submission.submit(event, 2))
        assertEquals(2, attempts)
        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(event, 100))
        assertEquals(3, attempts)
    }

    @Test fun suppressedEventsNeitherReachTheSinkNorConsumeAnAdditionalBudgetSlot() {
        val received = mutableListOf<TechnicalDiagnosticEvent>()
        val submission = DiagnosticSubmission(
            sink = { received += it },
            limiter = DiagnosticRateLimiter(duplicateWindowMs = 100, budgetWindowMs = 1000, maxEvents = 2),
        )
        val first = event()
        val second = event("NORMALIZE_TEXT")
        val outcomes = listOf(
            submission.submit(first, 0),
            submission.submit(first, 1),
            submission.submit(second, 2),
            submission.submit(event("LOAD_MODEL"), 3),
        )

        assertEquals(listOf(DiagnosticSubmissionOutcome.Accepted, DiagnosticSubmissionOutcome.Suppressed,
            DiagnosticSubmissionOutcome.Accepted, DiagnosticSubmissionOutcome.Suppressed), outcomes)
        assertEquals(2, outcomes.count { it == DiagnosticSubmissionOutcome.Accepted })
        assertEquals(listOf(first, second), received)
    }

    @Test fun successfulSubmissionsRemainSuppressedUntilTheirDuplicateWindowEnds() {
        var attempts = 0
        val submission = DiagnosticSubmission(sink = { attempts++ },
            limiter = DiagnosticRateLimiter(duplicateWindowMs = 100, budgetWindowMs = 1000, maxEvents = 3))
        val event = event()

        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(event, 0))
        assertEquals(DiagnosticSubmissionOutcome.Suppressed, submission.submit(event, 99))
        assertEquals(DiagnosticSubmissionOutcome.Accepted, submission.submit(event, 100))
        assertEquals(2, attempts)
    }

    @Test fun realSentenceRejectionReachesTheSinkOnlyAfterDurableStorageAndSanitizing() = runBlocking {
        val reading = "Private reading x = y."
        val page = DocumentPipeline().process("private-document", 0,
            ExtractedPage(listOf(TextElement(reading, emptyList())), 0f, 0f, "test"))
        val failure = assertThrows(SpeechPreparationException::class.java) {
            SpeechPlanner().prepare(page.words).toList()
        }
        val root = temporary.newFolder()
        val received = mutableListOf<TechnicalDiagnosticEvent>()
        val submission = DiagnosticSubmission(sink = { received += it })
        var durableWhenSubmitted = false
        var outcome: DiagnosticSubmissionOutcome? = null
        var callbacks = 0
        val store = IssueLogs(root, { mapOf("appVersion" to "0.2.5") }, onReport = { report ->
            callbacks++
            durableWhenSubmitted = File(root, "${report.id}/report.json").isFile &&
                !File(root, "${report.id}.partial").exists()
            outcome = submission.submit(TechnicalDiagnostics.from(report), 0)
        })

        val id = store.record("NORMALIZE_TEXT", failure, IssueInput(documentId = "private-document",
            originalText = reading, readingText = reading, words = failure.sentence,
            details = mapOf("model" to "kokoro-misaki")))!!

        assertTrue(durableWhenSubmitted)
        assertEquals(1, callbacks)
        assertEquals(DiagnosticSubmissionOutcome.Accepted, outcome)
        val local = store.read(id)!!
        assertEquals(reading, local.input.originalText)
        assertEquals("=", local.normalization!!.sourceText)
        val sent = received.single()
        assertEquals("NORMALIZATION", sent.category)
        assertEquals("NORMALIZE_TEXT_SpeechPreparationException", sent.code)
        assertEquals("kokoro-misaki", sent.configuration["model"])
        assertTrue(sent.failures.any { it.type == "app.readflow.core.NormalizationException" })
        val encoded = Json.encodeToString(TechnicalDiagnosticEvent.serializer(), sent)
        assertFalse(encoded.contains(reading))
        assertFalse(encoded.contains("private-document"))
        assertFalse(encoded.contains(failure.message!!))
        assertFalse(encoded.contains("originalText"))
    }
}
