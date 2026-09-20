package app.readflow.diagnostics

import app.readflow.core.*
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class IssueInput(
    val documentId: String? = null, val title: String? = null, val source: String? = null,
    val mime: String? = null, val pageIndex: Int? = null, val requestedWordId: String? = null,
    val originalText: String? = null, val readingText: String? = null, val normalizedText: String? = null,
    val words: List<SourceWord> = emptyList(), val spokenTokens: List<SpokenToken> = emptyList(),
    val extraction: String? = null, val details: Map<String, String> = emptyMap(),
)
@Serializable data class IssueFailure(val type: String, val message: String?, val stack: List<String>)
@Serializable data class ConfidenceFailure(val tokenIndex: Int, val token: String, val sourceIds: List<String>, val confidence: Float, val threshold: Float)
@Serializable data class NormalizationFailure(val sourceIds: List<String>, val sourceText: String, val attemptedExpansion: String)
@Serializable data class IssueAudio(val included: Boolean, val bytes: Long = 0, val sha256: String? = null, val omission: String? = null)
@Serializable data class IssueReport(
    val schemaVersion: Int = 1, val id: String, val timestampMs: Long, val stage: String, val severity: String,
    val environment: Map<String, String>, val input: IssueInput, val failures: List<IssueFailure>,
    val warnings: List<String> = emptyList(), val alignment: ConfidenceFailure? = null,
    val normalization: NormalizationFailure? = null,
    val audio: IssueAudio = IssueAudio(false, omission = "No generated audio available"),
    val truncatedFields: List<String> = emptyList(), val dedupeKey: String? = null,
    val privacy: String = "Contains private input excerpts, source identifiers and possibly generated speech. Review before sharing. No automatic upload. Original PDF/image files and full webpages are not attached.",
)
data class IssueSummary(val id: String, val timestampMs: Long, val stage: String, val message: String, val audio: Boolean)

/** Error-only, private, bounded storage. Never invoked as a successful-operation trace. */
class IssueLogs(private val root: File, private val environment: () -> Map<String, String>,
    private val maxReports: Int = 20, private val maxBytes: Long = 24L * 1024 * 1024,
) {
    companion object { const val TEXT_LIMIT = 8192; const val AUDIO_LIMIT = 4L * 1024 * 1024 }
    private val lock = Any()
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<List<IssueSummary>>(emptyList())
    val reports = mutable.asStateFlow()
    private val mutableStorageFailure = MutableStateFlow<String?>(null)
    val storageFailure = mutableStorageFailure.asStateFlow()
    @Volatile var activeInput: IssueInput = IssueInput()

    suspend fun refresh() = withContext(Dispatchers.IO) { synchronized(lock) { prune(); refreshLocked() } }
    suspend fun record(stage: String, error: Throwable? = null, input: IssueInput = IssueInput(),
        warnings: List<String> = emptyList(), audio: File? = null, dedupeKey: String? = null,
    ): String? {
        if (error is CancellationException || error == null && warnings.isEmpty()) return null
        return withContext(NonCancellable + Dispatchers.IO) { save(stage, error, input, warnings, audio, dedupeKey) }
    }
    fun recordCrash(error: Throwable) { if (error !is CancellationException) save("UNCAUGHT_JAVA", error, activeInput, emptyList(), null, null) }

    private fun save(stage: String, error: Throwable?, input: IssueInput, warnings: List<String>, audio: File?, dedupeKey: String?): String? = synchronized(lock) {
        var partial: File? = null
        try {
            if (dedupeKey != null) readAll().firstOrNull { it.dedupeKey == dedupeKey }?.let { return@synchronized it.id }
            val id = UUID.randomUUID().toString()
            check(root.isDirectory || root.mkdirs())
            val directory = File(root, "$id.partial").also { check(it.mkdir()) }; partial = directory
            val truncated = mutableListOf<String>()
            fun text(name: String, value: String?, limit: Int = TEXT_LIMIT): String? {
                if (value == null || value.length <= limit) return value
                truncated += name
                return value.take(if (value[limit - 1].isHighSurrogate()) limit - 1 else limit)
            }
            val safeInput = input.copy(
                documentId = text("documentId", input.documentId, 128), title = text("title", input.title, 512),
                source = text("source", input.source, 2048), mime = text("mime", input.mime, 128),
                originalText = text("originalText", input.originalText), readingText = text("readingText", input.readingText),
                normalizedText = text("normalizedText", input.normalizedText),
                words = input.words.take(128).map { it.copy(text = text("wordText", it.text, 256)!!, boxes = it.boxes.take(8)) },
                spokenTokens = input.spokenTokens.take(512).map { it.copy(text = text("spokenToken", it.text, 128)!!, sourceIds = it.sourceIds.take(8)) },
                details = input.details.entries.take(32).associate { it.key.take(80) to text("details.${it.key.take(80)}", it.value, 1024)!! },
            )
            if (input.words.size > 128) truncated += "words"
            if (input.spokenTokens.size > 512) truncated += "spokenTokens"
            val causes = generateSequence(error) { it.cause }.take(6).toList()
            val confidence = causes.filterIsInstance<AlignmentConfidenceException>().firstOrNull()?.let {
                ConfidenceFailure(it.tokenIndex, it.token.take(128), it.sourceWordIds.take(8), it.confidence, it.threshold)
            }
            val normalization = causes.filterIsInstance<NormalizationException>().firstOrNull()?.let {
                NormalizationFailure(it.sourceIds.take(8), text("normalization.sourceText", it.sourceText, 256)!!,
                    text("normalization.attemptedExpansion", it.expansion)!!)
            }
            val attachment = when {
                audio == null || !audio.isFile -> IssueAudio(false, omission = "No generated audio available")
                audio.length() > AUDIO_LIMIT -> IssueAudio(false, omission = "Audio exceeds 4 MiB; no partial WAV attached")
                else -> try {
                    val copy = File(directory, "audio.wav")
                    audio.inputStream().use { source -> copy.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val count = source.read(buffer); if (count < 0) break
                            total += count; check(total <= AUDIO_LIMIT); target.write(buffer, 0, count)
                        }
                    } }
                    IssueAudio(true, copy.length(), app.readflow.models.fileHash(copy))
                } catch (_: Exception) { File(directory, "audio.wav").delete(); IssueAudio(false, omission = "Audio could not be retained") }
            }
            val report = IssueReport(id = id, timestampMs = System.currentTimeMillis(), stage = stage.take(80),
                severity = if (error == null) "WARNING" else "ERROR", environment = environment(), input = safeInput,
                failures = causes.map { IssueFailure(it.javaClass.name, text("errorMessage", it.message, 2048), it.stackTrace.take(40).map { frame -> frame.toString().take(512) }) },
                warnings = warnings.take(16).map { text("warning", it, 1024)!! }, alignment = confidence, normalization = normalization, audio = attachment,
                truncatedFields = truncated.distinct(), dedupeKey = dedupeKey?.take(128))
            val bytes = json.encodeToString(IssueReport.serializer(), report).toByteArray()
            check(bytes.size <= 512 * 1024) { "Issue report exceeds storage bound" }
            File(directory, "report.json").outputStream().use { it.write(bytes); it.fd.sync() }
            check(directory.renameTo(File(root, id))); partial = null
            prune(); refreshLocked()
            mutableStorageFailure.value = null
            id
        } catch (_: Exception) {
            partial?.deleteRecursively()
            mutableStorageFailure.value = "An issue log could not be saved. Check available storage."
            null
        }
    }
    suspend fun read(id: String): IssueReport? = withContext(Dispatchers.IO) { synchronized(lock) { readReport(id) } }
    suspend fun snapshot(): List<IssueReport> = withContext(Dispatchers.IO) { synchronized(lock) { readAll().sortedBy { it.timestampMs } } }
    suspend fun exportHistory(output: OutputStream) = withContext(Dispatchers.IO) {
        val history = snapshot()
        check(history.isNotEmpty()) { "No issue history to export" }
        // One chronological JSONL journal; audio remains available through individual exports.
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("issues.jsonl"))
            val compact = Json { encodeDefaults = true }
            history.forEach { report ->
                val entry = report.copy(audio = IssueAudio(false, omission = "Combined history excludes audio; use individual issue export"))
                zip.write((compact.encodeToString(IssueReport.serializer(), entry) + "\n").toByteArray())
            }
            zip.closeEntry()
        }
    }
    suspend fun export(id: String, output: OutputStream) = withContext(Dispatchers.IO) {
        // Release the store lock before writing to a possibly slow user-selected provider.
        val snapshot = synchronized(lock) {
            val report = checkNotNull(readReport(id)) { "This issue log is no longer available" }
            val audio = if (report.audio.included) {
                val file = File(directory(id), "audio.wav")
                check(report.audio.bytes <= AUDIO_LIMIT && file.length() == report.audio.bytes) { "Saved issue audio is missing or corrupt" }
                val bytes = file.inputStream().use { it.readNBytes((AUDIO_LIMIT + 1).toInt()) }
                check(bytes.size.toLong() == report.audio.bytes && sha256(bytes) == report.audio.sha256) { "Saved issue audio is missing or corrupt" }
                bytes
            } else null
            json.encodeToString(IssueReport.serializer(), report).toByteArray() to audio
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("report.json")); zip.write(snapshot.first); zip.closeEntry()
            snapshot.second?.let { audio ->
                zip.putNextEntry(ZipEntry("audio.wav")); zip.write(audio); zip.closeEntry()
            }
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        check(!directory(id).exists() || directory(id).deleteRecursively()) { "Could not delete issue log" }; refreshLocked()
    } }
    suspend fun clear() = withContext(Dispatchers.IO) { synchronized(lock) {
        check(!root.exists() || root.deleteRecursively()) { "Could not delete issue logs" }; refreshLocked()
    } }
    suspend fun deleteForDocument(documentId: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        readAll().filter { it.input.documentId == documentId }.forEach { check(directory(it.id).deleteRecursively()) { "Could not delete associated issue log" } }
        refreshLocked()
    } }
    private fun directory(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid issue ID" }
        return File(root, id)
    }
    private fun readReport(id: String): IssueReport? = try {
        val file = File(directory(id), "report.json")
        if (!file.isFile || file.length() > 512 * 1024) null else json.decodeFromString<IssueReport>(file.readText()).takeIf { it.id == id }
    } catch (_: Exception) { null }
    private fun readAll() = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".partial") }.mapNotNull { readReport(it.name) }.sortedByDescending { it.timestampMs }
    private fun refreshLocked() { mutable.value = readAll().map { IssueSummary(it.id, it.timestampMs, it.stage, it.failures.firstOrNull()?.message ?: it.warnings.firstOrNull() ?: "Issue", it.audio.included) } }
    private fun prune() {
        root.listFiles().orEmpty().filter { it.name.endsWith(".partial") || it.isDirectory && readReport(it.name) == null }.forEach { it.deleteRecursively() }
        val reports = readAll()
        var bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        for ((index, report) in reports.withIndex().reversed()) {
            if (index >= maxReports || bytes > maxBytes) {
                val directory = directory(report.id); val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (directory.deleteRecursively()) bytes -= size
            }
        }
    }
}
