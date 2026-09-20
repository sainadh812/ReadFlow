package app.readflow.diagnostics

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitHubDiagnosticsTest {
    private val installation = "11111111-1111-1111-1111-111111111111"
    private fun report(id: String = "event-1", text: String = "Private original text") = IssueReport(id = id, timestampMs = 123, stage = "NORMALIZE_TEXT", severity = "ERROR",
        environment = mapOf("appVersion" to "test", "device" to "test phone"), input = IssueInput(title = "Private title", source = "https://private.example/secret",
            originalText = text, readingText = text, normalizedText = "Private normalized text", details = mapOf("model" to "kokoro", "secret" to "never send", "downloadPhase" to "Private error message in download progress")),
        failures = listOf(IssueFailure("NormalizationException", "Private error message", listOf("app.readflow.Normalizer.normalize(Normalizer.kt:1)"))),
        normalization = NormalizationFailure(listOf("source-1"), "Private rejected token", "Private expansion"))
    private fun payload(batch: UploadBatch) = batch.body.substringAfter("```jsonl\n").substringBeforeLast("\n```").lineSequence().map { Json.parseToJsonElement(it).jsonObject }.toList()

    @Test fun publicProjectionDoesNotContainPrivateInputsOrMessages() {
        val batch = DiagnosticBatches.prepare(listOf(report()), false).single()
        for (secret in listOf("Private", "private.example", "never send", "source-1")) assertFalse(batch.body.contains(secret))
        assertTrue(batch.body.contains("kokoro")); assertTrue(batch.body.contains("NormalizationException"))
        assertFalse(payload(batch).single().getValue("inputIncluded").jsonPrimitive.boolean)
    }
    @Test fun privateProjectionKeepsInputButEscapesMarkdownMentionsAndBoundsIt() {
        val text = "``` @someone <script> private" + "z".repeat(3000)
        val batch = DiagnosticBatches.prepare(listOf(report(text = text)), true).single()
        val value = payload(batch).single().getValue("input").jsonObject.getValue("originalText").jsonPrimitive.content
        assertEquals(text.take(2048), value)
        assertFalse(batch.body.contains("@someone")); assertFalse(batch.body.contains("<script>"))
        assertEquals(2, Regex("```").findAll(batch.body).count())
        assertFalse(batch.body.contains("private.example")); assertFalse(batch.body.contains("Private title"))
    }
    @Test fun repeatedEventsAreGroupedButAllIdsRemainAcknowledged() {
        val batch = DiagnosticBatches.prepare(listOf(report("a"), report("b"), report("c", "Different input")), true).single()
        val entries = payload(batch)
        assertEquals(2, entries.size)
        assertEquals(2, entries.first().getValue("occurrences").jsonPrimitive.int)
        assertEquals(listOf("a", "b", "c"), batch.reportIds)
    }
    @Test fun largeHistorySplitsIntoBoundedBatches() {
        val batches = DiagnosticBatches.prepare((1..20).map { report("event-$it", "$it " + "x".repeat(8000)) }, true)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.body.length < 50_000 })
        assertEquals(20, batches.sumOf { it.reportIds.size })
    }
    @Test fun malformedRepositoryNamesAreRejected() {
        for (repo in listOf("owner/repo/extra", "owner/..", "https://github.com/owner/repo", "owner/repo?x", "owner/.", "owner/repo\n"))
            assertThrows(IllegalArgumentException::class.java) { GitHubDestination(repo).validate() }
    }
    @Test fun invalidCredentialCharactersFailWithoutEchoingTheCredential() {
        validateGitHubToken("test-token_123")
        for (token in listOf("private-token\u0000", "private token", "private-token\n", "\u4f60\u597d", "x".repeat(513))) {
            val error = assertThrows(IllegalArgumentException::class.java) { validateGitHubToken(token) }
            assertEquals("Enter a valid GitHub token", error.message)
        }
    }
    @Test fun privateExcerptDoesNotSplitUnicodeSurrogates() {
        val text = "a".repeat(2047) + "\uD83D\uDE00"
        val entry = payload(DiagnosticBatches.prepare(listOf(report(text = text)), true).single()).single()
        assertEquals("a".repeat(2047), entry.getValue("input").jsonObject.getValue("originalText").jsonPrimitive.content)
    }

    private class Api : GitHubIssueApi {
        var privateRepo = false
        var closed = false
        var issue: JsonObject? = null
        val comments = mutableListOf<JsonObject>()
        var created = 0
        var loseCommentResponse = false
        var loseIssueResponse = false
        var changePrivacyAfterCreate = false
        var cancelNextComment = false
        override suspend fun request(method: String, path: String, token: String, body: JsonObject?): JsonElement {
            assertEquals("test-token", token)
            return when {
                method == "GET" && path == "repos/owner/repo" -> buildJsonObject { put("private", privateRepo) }
                method == "GET" && path.contains("/issues?state=") -> JsonArray(listOfNotNull(issue))
                method == "POST" && path.endsWith("/issues") -> {
                    created++; issue = buildJsonObject { put("number", 7); put("state", "open"); put("body", body!!.getValue("body")) }
                    if (changePrivacyAfterCreate) privateRepo = false
                    if (loseIssueResponse) { loseIssueResponse = false; throw IOException("response lost") }
                    issue!!
                }
                method == "GET" && path.endsWith("/issues/7") -> JsonObject(issue!! + ("state" to JsonPrimitive(if (closed) "closed" else "open")))
                method == "GET" && path.contains("/comments?") -> {
                    val page = path.substringAfter("&page=").toInt()
                    JsonArray(comments.drop((page - 1) * 10).take(10))
                }
                method == "POST" && path.endsWith("/comments") -> {
                    if (cancelNextComment) { cancelNextComment = false; throw CancellationException("cancelled") }
                    comments += body!!
                    if (loseCommentResponse) { loseCommentResponse = false; throw IOException("response lost") }
                    buildJsonObject { put("id", comments.size) }
                }
                else -> error("Unexpected $method $path")
            }
        }
    }
    private suspend fun send(api: Api, reports: List<IssueReport> = listOf(report()), input: Boolean = false, number: Int = 0): GitHubSyncResult =
        GitHubDiagnostics(api, {}).send(GitHubDestination("owner/repo", input, number), "test-token", installation, reports) { }

    @Test fun sendsOnceThenAppendsNewEventsToTheSameIssue() = runBlocking {
        val api = Api()
        assertEquals(1, send(api).uploadedReports)
        assertEquals(0, send(api).uploadedReports)
        assertEquals(1, send(api, listOf(report(), report("event-2"))).uploadedReports)
        assertEquals(1, api.created); assertEquals(2, api.comments.size)
    }
    @Test fun privateInputCannotBeSentToPublicRepository() = runBlocking {
        val api = Api()
        try { send(api, input = true); fail("Private text uploaded") } catch (_: IllegalStateException) { }
        assertEquals(0, api.created); assertTrue(api.comments.isEmpty())
    }
    @Test fun privateRepositoryAcceptsExplicitlySelectedExcerpts() = runBlocking {
        val api = Api().apply { privateRepo = true }
        send(api, input = true)
        assertTrue(api.comments.single().getValue("body").jsonPrimitive.content.contains("Private original text"))
    }
    @Test fun privacyIsRecheckedBeforeEachBatch() = runBlocking {
        val api = Api().apply { privateRepo = true; changePrivacyAfterCreate = true }
        try { send(api, input = true); fail("Repository became public") } catch (_: IllegalStateException) { }
        assertTrue(api.comments.isEmpty())
    }
    @Test fun lostCommentResponseDoesNotDuplicateTheBatchOnRetry() = runBlocking {
        val api = Api().apply { loseCommentResponse = true }
        try { send(api); fail("Expected lost response") } catch (_: IOException) { }
        assertEquals(0, send(api).uploadedReports)
        assertEquals(1, api.comments.size)
    }
    @Test fun lostIssueCreationResponseRecoversTheExistingIssue() = runBlocking {
        val api = Api().apply { loseIssueResponse = true }
        try { send(api); fail("Expected lost response") } catch (_: IOException) { }
        assertEquals(1, send(api).uploadedReports)
        assertEquals(1, api.created)
    }
    @Test fun cancellationLeavesEventsAvailableForRetry() = runBlocking {
        val api = Api().apply { cancelNextComment = true }
        try { send(api); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(api.comments.isEmpty())
        assertEquals(1, send(api).uploadedReports)
    }
    @Test fun closedOrUnrelatedIssuesAreNotModified() = runBlocking {
        val api = Api(); send(api); api.closed = true
        try { send(api, listOf(report("new")), number = 7); fail("Closed issue modified") } catch (_: IllegalStateException) { }
        api.closed = false; api.issue = buildJsonObject { put("number", 7); put("body", "Unrelated issue"); put("state", "open") }
        try { send(api, listOf(report("new")), number = 7); fail("Unrelated issue modified") } catch (_: IllegalStateException) { }
        assertEquals(1, api.comments.size)
    }
    @Test fun deduplicationReadsLaterCommentPages() = runBlocking {
        val api = Api(); send(api)
        repeat(10) { api.comments.add(0, buildJsonObject { put("body", "Unrelated comment") }) }
        assertEquals(0, send(api).uploadedReports)
        assertEquals(11, api.comments.size)
    }
    @Test fun explicitPrivateModeCanAddInputToPreviouslySanitizedEvents() = runBlocking {
        val api = Api().apply { privateRepo = true }; send(api)
        assertEquals(1, send(api, input = true).uploadedReports)
        assertEquals(0, send(api, input = true).uploadedReports)
    }
}
