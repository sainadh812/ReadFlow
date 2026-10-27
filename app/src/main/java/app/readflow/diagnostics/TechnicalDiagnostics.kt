package app.readflow.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A separate outbound schema: never serialize IssueReport to automatic reporting or support email. */
@Serializable data class TechnicalDiagnosticEvent(
    val schemaVersion: Int = 1,
    val stage: String,
    val severity: String,
    val category: String,
    val code: String,
    val environment: Map<String, String>,
    val configuration: Map<String, String>,
    val failures: List<TechnicalFailure>,
)

@Serializable data class TechnicalFailure(val type: String, val frames: List<TechnicalFrame>)
@Serializable data class TechnicalFrame(val className: String, val methodName: String, val lineNumber: Int) {
    // Source filenames and paths are deliberately absent, including in the synthetic exception.
    fun asStackTraceElement() = StackTraceElement(className, methodName, null, lineNumber)
    fun display() = "$className.$methodName(:$lineNumber)"
}

/** Explicit field and value allowlists. Free-form text is never made safe merely by truncation. */
object TechnicalDiagnostics {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val stages = setOf(
        "UNCAUGHT_JAVA", "ANDROID_PROCESS_EXIT", "EXTRACT_PAGE", "ROTATE_OCR", "RETRY_OCR", "OCR",
        "IMPORT_FILE", "IMPORT_ARTICLE", "FETCH_WEBPAGE", "PARSE_ARTICLE", "OPEN_BOOKMARK",
        "VERIFY_MODELS", "MODEL_INSTALL", "MODEL_DELETE", "MODEL_SWITCH", "PHONEMIZER_SWITCH",
        "LOAD_MODEL", "RELEASE_MODELS", "NORMALIZE_TEXT", "SYNTHESIZE", "WRITE_AUDIO",
        "ALIGN_WORDS", "READ_AUDIO_CACHE", "QUEUE_AUDIO", "MEDIA_PLAYBACK", "PREVIEW", "CACHE_DELETE",
        "BENCHMARK", "EXPORT_ISSUE", "EXPORT_HISTORY", "GITHUB_SYNC",
        "PLAYBACK_SERVICE", "RENDER_PAGE", "DOCUMENT_DELETE", "OCR_MODEL_INSTALL", "OCR_MODEL_DELETE", "OCR_MODEL_SWITCH",
    )
    private val models = setOf("kokoro", "kokoro-misaki", "pocket", "alignment", "mlkit", "paddleocr", "teleocr")
    private val modelVersions = setOf("v0.19", "int8-2026-01-26", "53dcb6b-Q5_K_M-Q8_0", "PP-OCRv5-e6f4fa8-89d3a50",
        "729c1a6730fb549c20a1c73a3d3f96f11020225e", "unknown")
    private val types = setOf(
        "java.lang.IllegalStateException", "java.lang.IllegalArgumentException", "java.lang.NullPointerException",
        "java.lang.IndexOutOfBoundsException", "java.lang.ArrayIndexOutOfBoundsException", "java.lang.NumberFormatException",
        "java.lang.UnsupportedOperationException", "java.lang.SecurityException", "java.lang.OutOfMemoryError",
        "java.lang.UnsatisfiedLinkError", "java.lang.RuntimeException", "java.lang.Exception", "java.lang.AssertionError",
        "java.io.IOException", "java.io.FileNotFoundException", "java.io.EOFException", "java.net.SocketTimeoutException",
        "java.net.UnknownHostException", "java.net.ConnectException", "java.util.zip.ZipException",
        "android.os.DeadObjectException", "android.database.sqlite.SQLiteException", "ai.onnxruntime.OrtException",
        "androidx.media3.common.PlaybackException", "androidx.media3.exoplayer.ExoPlaybackException",
        "com.google.mlkit.common.MlKitException", "kotlinx.serialization.SerializationException",
        "app.readflow.core.NormalizationException", "app.readflow.core.SpeechPreparationException",
        "app.readflow.core.AlignmentConfidenceException", "app.readflow.ingest.ArticleImportException",
    )
    private val versions = Regex("v?[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}(?:-prototype)?")
    private val framePattern = Regex("([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+)\\.([A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)\\(([^()]*)\\)")
    private val framePrefixes = listOf("app.readflow.", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
        "ai.onnxruntime.", "com.google.mlkit.", "com.k2fsa.sherpa.onnx.", "okhttp3.", "okio.")

    fun from(report: IssueReport): TechnicalDiagnosticEvent {
        val stage = report.stage.takeIf { it in stages } ?: "OTHER"
        val environment = buildMap {
            version(report.environment["appVersion"])?.let { put("appVersion", it) }
            integer(report.environment["versionCode"], 1, Int.MAX_VALUE.toLong())?.let { put("versionCode", it) }
            integer(report.environment["heapLimitMiB"], 1, 1_048_576)?.let { put("heapLimitMiB", it) }
            Regex("[0-9]{1,2}(?:\\.[0-9]{1,2})? API ([0-9]{1,3})").matchEntire(report.environment["android"].orEmpty())
                ?.groupValues?.get(1)?.let { integer(it, 1, 999) }?.let { put("androidApi", it) }
            report.environment["abis"]?.split(',')?.map { it.trim() }
                ?.takeIf { it.isNotEmpty() && it.size <= 4 && it.all { abi -> abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") } }
                ?.let { put("abis", it.joinToString(",")) }
            version(report.environment["alignmentOrt"])?.let { put("alignmentOrt", it) }
            version(report.environment["sherpa"])?.let { put("sherpa", it) }
            if (report.environment["sherpa"] == "not bundled") put("sherpa", "not bundled")
            report.environment["alignmentVersion"]?.takeIf { it == "wav2vec2-729c1a6-ctc-v1" }?.let { put("alignmentVersion", it) }
            report.environment["pipeline"]?.takeIf { Regex("readflow-[0-9]{1,3}").matches(it) }?.let { put("pipeline", it) }
            report.environment["normalization"]?.takeIf { Regex("english-[0-9]{1,3}").matches(it) }?.let { put("normalization", it) }
        }
        val configuration = configuration(report.input.details, stage)
        val failures = report.failures.take(4).map { failure ->
            val type = failure.type.takeIf { it in types }
                ?: types.firstOrNull { it.substringAfterLast('.') == failure.type } ?: "OtherFailure"
            TechnicalFailure(type, failure.stack.asSequence().mapNotNull(::frame).take(12).toList())
        }
        val category = when {
            stage == "ANDROID_PROCESS_EXIT" -> "PROCESS_EXIT"
            failures.any { it.type == "java.lang.OutOfMemoryError" } -> "MEMORY"
            failures.any { it.type == "app.readflow.core.NormalizationException" } -> "NORMALIZATION"
            failures.any { it.type == "app.readflow.core.AlignmentConfidenceException" } -> "ALIGNMENT"
            stage == "UNCAUGHT_JAVA" -> "CRASH"
            stage in setOf("EXTRACT_PAGE", "ROTATE_OCR", "RETRY_OCR", "OCR", "OCR_MODEL_INSTALL", "OCR_MODEL_DELETE", "OCR_MODEL_SWITCH") -> "OCR"
            stage in setOf("VERIFY_MODELS", "LOAD_MODEL", "RELEASE_MODELS", "MODEL_INSTALL", "MODEL_DELETE", "MODEL_SWITCH") -> "MODEL"
            stage in setOf("NORMALIZE_TEXT", "SYNTHESIZE", "ALIGN_WORDS", "PREVIEW", "PHONEMIZER_SWITCH") -> "SPEECH"
            stage in setOf("WRITE_AUDIO", "READ_AUDIO_CACHE", "QUEUE_AUDIO", "MEDIA_PLAYBACK", "CACHE_DELETE", "PLAYBACK_SERVICE") -> "PLAYBACK"
            stage in setOf("IMPORT_FILE", "IMPORT_ARTICLE", "FETCH_WEBPAGE", "PARSE_ARTICLE") -> "IMPORT"
            else -> "OTHER"
        }
        val code = if (stage == "ANDROID_PROCESS_EXIT") "PROCESS_EXIT_${configuration["androidReason"] ?: "UNKNOWN"}"
            else "${stage}_${failures.firstOrNull()?.type?.substringAfterLast('.') ?: "WARNING"}"
        return TechnicalDiagnosticEvent(stage = stage, severity = if (report.severity == "WARNING") "WARNING" else "ERROR",
            category = category, code = code, environment = environment, configuration = configuration, failures = failures)
    }

    fun summary(reports: List<IssueReport>, limit: Int = 5): String {
        require(limit in 1..5)
        if (reports.isEmpty()) return "No saved technical failures are available."
        return json.encodeToString(ListSerializer(TechnicalDiagnosticEvent.serializer()),
            reports.sortedByDescending { it.timestampMs }.take(limit).map(::from))
    }

    private fun configuration(details: Map<String, String>, stage: String): Map<String, String> = buildMap {
        details["model"]?.takeIf { it in models }?.let { put("model", it) }
        details["modelVersion"]?.takeIf { it in modelVersions }?.let { put("modelVersion", it) }
        integer(details["voice"], 0, 1024)?.let { put("voice", it) }
        decimal(details["speed"], .25, 4.0)?.let { put("speed", it) }
        integer(details["sampleRate"], 8_000, 192_000)?.let { put("sampleRate", it) }
        integer(details["sampleCount"], 0, 1_000_000_000)?.let { put("sampleCount", it) }
        // Parse each supported setting independently; never retain the free-form recipe itself.
        val recipe = details["synthesis"].orEmpty().takeIf { it.length <= 1024 }.orEmpty().split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        for (key in listOf("speechThreads", "alignmentThreads"))
            integer(details[key] ?: recipe[key], 1, 8)?.let { put(key, it) }
        (details["backend"] ?: recipe["backend"])?.takeIf { it in setOf("cpu", "nnapi", "qnn", "vulkan", "opencl", "xnnpack") }
            ?.let { put("backend", it) }
        (details["allowSpinning"] ?: recipe["spinning"])?.takeIf { it in setOf("true", "false") }?.let { put("allowSpinning", it) }
        if (stage == "ANDROID_PROCESS_EXIT") {
            integer(details["androidReason"], 0, 100)?.let { put("androidReason", it) }
            integer(details["status"], Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.let { put("status", it) }
            for (key in listOf("pssKiB", "rssKiB")) integer(details[key], 0, 1_073_741_824)?.let { put(key, it) }
        }
    }

    private fun version(value: String?): String? = value?.takeIf { it.length <= 40 && versions.matches(it) }
    private fun integer(value: String?, min: Long, max: Long): String? = value
        ?.takeIf { it.length <= 16 && Regex("-?[0-9]+").matches(it) }?.toLongOrNull()?.takeIf { it in min..max }?.toString()
    private fun decimal(value: String?, min: Double, max: Double): String? = value
        ?.takeIf { it.length <= 8 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(it) }?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in min..max }?.toString()
    private fun frame(value: String): TechnicalFrame? {
        if (value.length > 512) return null
        val match = framePattern.matchEntire(value) ?: return null
        val (className, methodName, location) = match.destructured
        if (className.length > 180 || methodName.length > 80 || framePrefixes.none(className::startsWith)) return null
        // Ignore the original filename even if it looks valid; reject paths or injected text entirely.
        val line = when {
            location in setOf("Native Method", "Unknown Source") -> -1
            else -> Regex("[A-Za-z_$][A-Za-z0-9_$]*\\.(?:kt|java):([0-9]{1,7})").matchEntire(location)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        }
        return TechnicalFrame(className, methodName, line)
    }
}

/** Memory-only limiter; a restart resets it. Uses elapsed time, never document IDs or input contents. */
class DiagnosticRateLimiter(
    private val duplicateWindowMs: Long = 10 * 60_000L,
    private val budgetWindowMs: Long = 60 * 60_000L,
    private val maxEvents: Int = 32,
) {
    init { require(duplicateWindowMs > 0 && budgetWindowMs > 0 && maxEvents in 1..128) }
    private val recent = linkedMapOf<String, Long>()
    private val accepted = ArrayDeque<Long>()
    private var lastClock: Long? = null

    @Synchronized fun shouldReport(event: TechnicalDiagnosticEvent, nowMs: Long): Boolean {
        if (nowMs < 0) return false
        if (lastClock?.let { nowMs < it } == true) clear()
        lastClock = nowMs
        while (accepted.isNotEmpty() && nowMs - accepted.first() >= budgetWindowMs) accepted.removeFirst()
        recent.entries.removeAll { nowMs - it.value >= duplicateWindowMs }
        val key = listOf(event.stage, event.category, event.code, event.configuration["model"],
            event.failures.firstOrNull()?.frames?.firstOrNull()?.display()).joinToString("|")
        if (key in recent || accepted.size >= maxEvents) return false
        if (recent.size >= 128) recent.remove(recent.keys.first())
        recent[key] = nowMs; accepted.addLast(nowMs)
        return true
    }

    @Synchronized fun clear() { recent.clear(); accepted.clear(); lastClock = null }
}
