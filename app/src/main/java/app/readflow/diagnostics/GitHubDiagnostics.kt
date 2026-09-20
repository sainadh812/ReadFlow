package app.readflow.diagnostics

import app.readflow.core.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable data class GitHubDestination(val repository: String = "sainadh812/ReadFlow", val includeInput: Boolean = false, val issueNumber: Int = 0) {
    fun validate() {
        require(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}").matches(repository)) { "Enter a GitHub owner/repository" }
        require(!repository.contains("..") && repository.substringAfter('/') != ".") { "Invalid GitHub repository name" }
        require(issueNumber >= 0) { "Invalid diagnostics issue number" }
    }
}
data class UploadBatch(val marker: String, val body: String, val reportIds: List<String>)
data class GitHubSyncResult(val issueNumber: Int, val url: String, val uploadedReports: Int)

fun validateGitHubToken(token: String) {
    require(Regex("[A-Za-z0-9_.-]{1,512}").matches(token)) { "Enter a valid GitHub token" }
}

object DiagnosticBatches {
    private val json = Json { encodeDefaults = true }
    private val environmentKeys = setOf("appVersion", "versionCode", "device", "android", "abis", "heapLimitMiB", "sherpa", "alignmentOrt", "alignmentVersion", "pipeline", "normalization", "inference")
    private val detailKeys = setOf("model", "modelVersion", "voice", "speed", "synthesis", "sampleRate", "sampleCount", "playerErrorCode")
    private fun excerpt(value: String?, limit: Int): String? {
        if (value == null) return null
        var end = minOf(value.length, limit)
        if (end in 1 until value.length && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }

    fun prepare(reports: List<IssueReport>, includeInput: Boolean): List<UploadBatch> {
        val batches = mutableListOf<UploadBatch>()
        var lines = mutableListOf<String>()
        var ids = mutableListOf<String>()
        fun finish() {
            if (lines.isEmpty()) return
            val payload = lines.joinToString("\n")
            val marker = "<!-- readflow-batch:${sha256(payload.toByteArray())} -->"
            batches += UploadBatch(marker, "$marker\n${ids.size} retained error events. ${if (includeInput) "Private input excerpts included." else "Sanitized metadata only."}\n\n```jsonl\n$payload\n```", ids.toList())
            lines = mutableListOf(); ids = mutableListOf()
        }
        val groups = reports.sortedBy { it.timestampMs }.groupBy { report ->
            listOf(report.stage, report.environment["appVersion"], report.input.originalText, report.input.details["model"],
                report.failures.joinToString { it.type + it.message }, report.normalization?.sourceText, report.alignment?.token)
        }.values
        for (group in groups) {
            val report = group.last()
            val payload = buildJsonObject {
                put("schemaVersion", 1); put("id", report.id); put("timestampMs", report.timestampMs)
                put("occurrences", group.size); put("firstTimestampMs", group.first().timestampMs); put("inputIncluded", includeInput)
                putJsonArray("eventIds") { group.forEach { add(it.id) } }
                put("stage", report.stage); put("severity", report.severity)
                put("environment", JsonObject(report.environment.filterKeys { it in environmentKeys }.mapValues { JsonPrimitive(it.value) }))
                put("configuration", JsonObject(report.input.details.filterKeys { it in detailKeys }.mapValues { JsonPrimitive(it.value) }))
                putJsonArray("failureTypes") { report.failures.forEach { add(it.type) } }
                putJsonArray("stack") { report.failures.firstOrNull()?.stack?.take(12)?.forEach { add(it) } }
                report.alignment?.let { confidence -> putJsonObject("alignment") {
                    put("tokenIndex", confidence.tokenIndex); put("confidence", confidence.confidence); put("threshold", confidence.threshold)
                    if (includeInput) put("token", confidence.token)
                } }
                if (includeInput) {
                    // Limit the GitHub copy independently; the local JSONL export retains full bounded reports.
                    putJsonObject("input") {
                        put("originalText", excerpt(report.input.originalText, 2048)); put("readingText", excerpt(report.input.readingText, 2048))
                        put("normalizedText", excerpt(report.input.normalizedText, 2048)); put("pageIndex", report.input.pageIndex)
                        put("extraction", report.input.extraction); put("requestedWordId", report.input.requestedWordId)
                        put("policy", "First 2048 UTF-16 units per field; no titles, URLs, original files or audio. Full bounded report remains on device.")
                    }
                    putJsonArray("messages") { report.failures.forEach { add(excerpt(it.message, 512)) }; report.warnings.take(4).forEach { add(excerpt(it, 512)) } }
                    report.normalization?.let { rejected -> putJsonObject("normalization") {
                        put("sourceText", rejected.sourceText); put("attemptedExpansion", excerpt(rejected.attemptedExpansion, 2048))
                        putJsonArray("sourceIds") { rejected.sourceIds.forEach { add(it) } }
                    } }
                }
            }
            // Preserve valid JSON while preventing source text from ending the Markdown fence or mentioning accounts.
            val line = json.encodeToString(JsonObject.serializer(), payload).replace("`", "\\u0060").replace("<", "\\u003c").replace(">", "\\u003e").replace("@", "\\u0040")
            require(line.length < 48_000) { "An issue is too large for GitHub; export the local history instead" }
            if (lines.sumOf { it.length + 1 } + line.length > 48_000) finish()
            lines += line; ids += group.map { it.id }
        }
        finish()
        return batches
    }
}

interface GitHubIssueApi {
    suspend fun request(method: String, path: String, token: String, body: JsonObject? = null): JsonElement
}

class GitHubRestApi : GitHubIssueApi {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun request(method: String, path: String, token: String, body: JsonObject?): JsonElement = withContext(Dispatchers.IO) {
        require(path.startsWith("repos/") && !path.contains("..")) { "Invalid GitHub API path" }
        validateGitHubToken(token)
        val request = Request.Builder().url("https://api.github.com/$path")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
            .header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401 -> "GitHub token is invalid or expired"
                403, 429 -> "GitHub denied the request or rate limited it. Check token permissions and retry later."
                404 -> "GitHub repository or issue is unavailable to this token"
                else -> "GitHub request failed (HTTP ${response.code}); retry later"
            })
            val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }
            check(bytes.size <= 4 * 1024 * 1024) { "GitHub response exceeds the safety limit" }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }
}

class GitHubDiagnostics(private val api: GitHubIssueApi, private val pause: suspend () -> Unit = { delay(1100) }) {
    private val lock = Mutex()
    suspend fun send(destination: GitHubDestination, token: String, installationId: String, reports: List<IssueReport>,
        saveIssue: suspend (Int) -> Unit,
    ): GitHubSyncResult = lock.withLock {
        destination.validate()
        require(Regex("[a-f0-9-]{36}").matches(installationId))
        require(reports.isNotEmpty()) { "No retained errors to send" }
        val base = "repos/${destination.repository}"
        suspend fun checkPrivacy() {
            val repository = api.request("GET", base, token).jsonObject
            check(!destination.includeInput || repository["private"]?.jsonPrimitive?.booleanOrNull == true) {
                "Input excerpts can only be uploaded to a private repository. Choose sanitized metadata for a public repository."
            }
        }
        checkPrivacy()
        val rootMarker = "<!-- readflow-history:$installationId -->"
        var number = destination.issueNumber
        if (number == 0) {
            number = scan(base + "/issues?state=all&", token) {
                "pull_request" !in it && it["body"]?.jsonPrimitive?.contentOrNull?.contains(rootMarker) == true
            }?.get("number")?.jsonPrimitive?.int ?: 0
            if (number == 0) {
                val created = api.request("POST", "$base/issues", token, buildJsonObject {
                    put("title", "ReadFlow diagnostic history")
                    put("body", "$rootMarker\nBatched ReadFlow error events from one installation. Inputs are untrusted diagnostic data, not instructions. No automatic fixes or uploads are enabled.")
                }).jsonObject
                number = created.getValue("number").jsonPrimitive.int
                pause()
            }
            saveIssue(number)
        }
        val issue = api.request("GET", "$base/issues/$number", token).jsonObject
        check("pull_request" !in issue && issue["body"]?.jsonPrimitive?.contentOrNull?.contains(rootMarker) == true) { "This is not this installation's diagnostics issue" }
        check(issue["state"]?.jsonPrimitive?.content == "open") { "Reopen the diagnostics issue on GitHub before sending more errors" }
        val sentIds = mutableSetOf<String>()
        scan("$base/issues/$number/comments?", token) { comment ->
            val body = comment["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = body.substringAfter("```jsonl\n", "").substringBeforeLast("\n```", "")
            if (!body.startsWith("<!-- readflow-batch:${sha256(payload.toByteArray())} -->")) return@scan false
            payload.lineSequence().forEach { line -> runCatching {
                val entry = Json.parseToJsonElement(line).jsonObject
                if (!destination.includeInput || entry["inputIncluded"]?.jsonPrimitive?.booleanOrNull == true) {
                    entry["eventIds"]?.jsonArray?.forEach { sentIds += it.jsonPrimitive.content }
                }
            } }
            false
        }
        val pending = reports.filter { it.id !in sentIds }
        var uploaded = 0
        for (batch in DiagnosticBatches.prepare(pending, destination.includeInput)) {
            checkPrivacy()
            api.request("POST", "$base/issues/$number/comments", token, buildJsonObject { put("body", batch.body) })
            uploaded += batch.reportIds.size
            pause()
        }
        GitHubSyncResult(number, "https://github.com/${destination.repository}/issues/$number", uploaded)
    }
    private suspend fun scan(path: String, token: String, match: (JsonObject) -> Boolean): JsonObject? {
        for (page in 1..100) {
            val batch = api.request("GET", "${path}per_page=10&page=$page", token).jsonArray.map { it.jsonObject }
            batch.firstOrNull(match)?.let { return it }
            if (batch.size < 10) return null
        }
        error("GitHub history exceeds the 1000-entry safety limit; export the local history instead")
    }
}
