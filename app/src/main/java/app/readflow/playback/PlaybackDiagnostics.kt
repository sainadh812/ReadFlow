package app.readflow.playback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.readflow.BuildConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PlaybackStage { VERIFYING_MODELS, PREPARING_TEXT, LOADING_MODEL, SYNTHESIZING, ALIGNING, STARTING_AUDIO, AUDIO_READY, FAILED }

@Serializable
data class PlaybackCheckpoint(
    val version: String,
    val model: String,
    val stage: PlaybackStage,
    val timestampMs: Long,
    val failureTypes: List<String> = emptyList(),
)

/** Timings are elapsed wall-clock milliseconds, not predictions of device performance. */
data class PlaybackPerformanceSummary(
    val totalPreparations: Long,
    val totalUnderruns: Long,
    val recentPreparations: Int,
    val recentCacheHits: Int,
    val recentAudioDurationMs: Long,
    val recentPreparationDurationMs: Long,
    val generatedPreparationDurationMs: Long,
    val generatedAudioDurationMs: Long,
    val meanSynthesisMs: Double?,
    val meanAlignmentMs: Double?,
    val preparationP95Ms: Long?,
    val readyChunks: Int?,
    val bufferedListeningMs: Long?,
    val model: String? = null,
    val voice: String? = null,
    val generatedPreparations: Int = 0,
    val synthesisCharacterSamples: Int = 0,
    val generatedPreparationSamples: Int = 0,
    val synthesisCharactersPerMinute: Double? = null,
) {
    /** Excludes cache hits; below 1 means preparation outpaced 1x playback in this window. */
    val generatedPreparationToAudioRatio: Double?
        get() = generatedAudioDurationMs.takeIf { it > 0 }
            ?.let { generatedPreparationDurationMs.toDouble() / it }

    /** Complete generated audio per second spent preparing it, including model load/alignment. */
    val generatedAudioSecondsPerPreparationSecond: Double?
        get() = generatedPreparationDurationMs.takeIf { it > 0 }
            ?.let { generatedAudioDurationMs.toDouble() / it }

    /** Above 1 means the measured producer can keep up with the requested listening speed. */
    fun throughputHeadroom(playbackSpeed: Float): Double? =
        if (playbackSpeed.isFinite() && playbackSpeed > 0f)
            generatedAudioSecondsPerPreparationSecond?.div(playbackSpeed.toDouble()) else null

    fun canKeepUp(playbackSpeed: Float): Boolean? = throughputHeadroom(playbackSpeed)?.let { it >= 1.0 }

    companion object {
        val Empty = PlaybackPerformanceSummary(
            0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null,
        )
    }
}

/** Latest measured extraction (or a cache-only placeholder), without text or document identifiers. */
data class ExtractionPerformanceMeasurement(
    val engine: String,
    val characters: Int,
    val elapsedMs: Long,
    val cached: Boolean,
) {
    val charactersPerMinute: Double?
        get() = if (!cached && engine != "ML Kit fallback" && characters > 0 && elapsedMs > 0)
            characters.toDouble() * 60_000.0 / elapsedMs else null
}

/** Content-free current stage in memory; only failures persist across restarts. */
class PlaybackDiagnostics(private val directory: File) {
    private val mutex = Mutex()
    private val file = File(directory, "playback.json")
    private var current: PlaybackCheckpoint? = null
    private val performanceLock = Any()
    private val preparations = ArrayDeque<PreparationMeasurement>()
    private var totalPreparations = 0L
    private var totalUnderruns = 0L
    private var readyChunks: Int? = null
    private var bufferedListeningMs: Long? = null
    private val _performance = MutableStateFlow(PlaybackPerformanceSummary.Empty)
    val performance: StateFlow<PlaybackPerformanceSummary> = _performance.asStateFlow()
    private val _extractionPerformance = MutableStateFlow<ExtractionPerformanceMeasurement?>(null)
    val extractionPerformance: StateFlow<ExtractionPerformanceMeasurement?> = _extractionPerformance.asStateFlow()

    private data class PreparationMeasurement(
        val cacheHit: Boolean,
        val synthesisMs: Long,
        val alignmentMs: Long,
        val totalMs: Long,
        val audioDurationMs: Long,
        val characters: Int,
    )

    /** Starts a fresh reading sample window; call after the previous producer has stopped. */
    fun beginSession(model: String, voice: String) = synchronized(performanceLock) {
        preparations.clear()
        totalPreparations = 0
        totalUnderruns = 0
        readyChunks = null
        bufferedListeningMs = null
        _performance.value = PlaybackPerformanceSummary.Empty.copy(
            model = model.takeIf { it in setOf("kokoro", "kokoro-misaki", "pocket") } ?: "unknown",
            voice = voice.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "unknown",
        )
    }

    /** Call with the engine that actually produced the page, including any OCR fallback.
     * Cached opening/playback must not erase the useful timing from the preceding OCR run.
     */
    fun recordExtraction(engine: String, characters: Int, elapsedMs: Long, cached: Boolean = false) =
        synchronized(performanceLock) {
            if (cached && _extractionPerformance.value?.cached == false) return@synchronized
            _extractionPerformance.value = ExtractionPerformanceMeasurement(
                engine = engine.takeIf { it in setOf("ML Kit", "ML Kit fallback", "TeleOCR", "PaddleOCR", "Embedded PDF") } ?: "Other extraction",
                characters = characters.coerceAtLeast(0),
                elapsedMs = elapsedMs.coerceAtLeast(0),
                cached = cached,
            )
        }

    /** Keeps only numeric measurements for the most recent 64 completed chunks, in memory. */
    fun recordPreparation(
        cacheHit: Boolean,
        synthesisMs: Long,
        alignmentMs: Long,
        totalMs: Long,
        audioDurationMs: Long,
        characters: Int = 0,
    ) = synchronized(performanceLock) {
        if (preparations.size == 64) preparations.removeFirst()
        preparations.addLast(PreparationMeasurement(
            cacheHit, synthesisMs.coerceAtLeast(0), alignmentMs.coerceAtLeast(0),
            totalMs.coerceAtLeast(0), audioDurationMs.coerceAtLeast(0),
            characters.coerceAtLeast(0),
        ))
        totalPreparations += 1
        _performance.value = summarizePreparations()
    }

    /** Count an unexpected depletion after playback has begun, not the initial preparation. */
    fun recordUnderrun() = synchronized(performanceLock) {
        totalUnderruns += 1
        _performance.value = _performance.value.copy(totalUnderruns = totalUnderruns)
    }

    /** Buffer duration should account for the current playback speed. */
    fun recordBuffer(readyChunks: Int, bufferedListeningMs: Long) = synchronized(performanceLock) {
        this.readyChunks = readyChunks.coerceAtLeast(0)
        this.bufferedListeningMs = bufferedListeningMs.coerceAtLeast(0)
        // This runs frequently: do not re-sort or aggregate the preparation window here.
        _performance.value = _performance.value.copy(
            readyChunks = this.readyChunks, bufferedListeningMs = this.bufferedListeningMs,
        )
    }

    fun performanceSummary(): PlaybackPerformanceSummary = _performance.value

    /** Called under performanceLock only when a completed preparation changes the window. */
    private fun summarizePreparations(): PlaybackPerformanceSummary {
        val generated = preparations.filterNot { it.cacheHit }
        val measuredPreparations = generated.filter { it.totalMs > 0 && it.audioDurationMs > 0 }
        val measuredCharacters = generated.filter { it.synthesisMs > 0 && it.characters > 0 }
        val sortedPreparationTimes = preparations.map { it.totalMs }.sorted()
        return PlaybackPerformanceSummary(
            totalPreparations = totalPreparations,
            totalUnderruns = totalUnderruns,
            recentPreparations = preparations.size,
            recentCacheHits = preparations.count { it.cacheHit },
            recentAudioDurationMs = preparations.sumOf { it.audioDurationMs },
            recentPreparationDurationMs = preparations.sumOf { it.totalMs },
            generatedPreparationDurationMs = measuredPreparations.sumOf { it.totalMs },
            generatedAudioDurationMs = measuredPreparations.sumOf { it.audioDurationMs },
            meanSynthesisMs = generated.takeIf { it.isNotEmpty() }?.map { it.synthesisMs }?.average(),
            meanAlignmentMs = generated.takeIf { it.isNotEmpty() }?.map { it.alignmentMs }?.average(),
            preparationP95Ms = if (sortedPreparationTimes.isEmpty()) null
                else sortedPreparationTimes[(sortedPreparationTimes.size * 95 + 99) / 100 - 1],
            readyChunks = readyChunks,
            bufferedListeningMs = bufferedListeningMs,
            model = _performance.value.model,
            voice = _performance.value.voice,
            generatedPreparations = generated.size,
            synthesisCharacterSamples = measuredCharacters.size,
            generatedPreparationSamples = measuredPreparations.size,
            synthesisCharactersPerMinute = measuredCharacters.takeIf { it.isNotEmpty() }?.let { samples ->
                samples.sumOf { it.characters.toLong() }.toDouble() * 60_000.0 / samples.sumOf { it.synthesisMs }
            },
        )
    }

    fun performanceReport(): String {
        val summary = performanceSummary()
        return buildString {
            appendLine("Playback performance (current reading session, in memory; latest 64 completed chunks):")
            summary.model?.let { appendLine("Speech model: $it; voice: ${summary.voice}") }
            appendLine("Completed chunks: ${summary.totalPreparations}; audio underruns: ${summary.totalUnderruns}")
            if (summary.recentPreparations == 0) {
                appendLine("No completed preparation measurements")
            } else {
                appendLine("Recent chunks: ${summary.recentPreparations}; cache hits: ${summary.recentCacheHits}")
                appendLine("Recent audio: ${summary.recentAudioDurationMs} ms; preparation: ${summary.recentPreparationDurationMs} ms; preparation p95: ${summary.preparationP95Ms} ms")
                summary.meanSynthesisMs?.let {
                    appendLine("Generated chunks mean synthesis: ${formatMetric(it)} ms; mean alignment: ${formatMetric(summary.meanAlignmentMs!!)} ms")
                }
                summary.generatedPreparationToAudioRatio?.let {
                    appendLine("Generated preparation/audio ratio: ${formatMetric(it)} (wall-clock time / audio duration; excludes cache hits)")
                }
                summary.synthesisCharactersPerMinute?.let {
                    appendLine("Speech synthesis: ${formatMetric(it)} characters/minute (${summary.synthesisCharacterSamples} generated samples; excludes cache and model load)")
                }
            }
            if (summary.readyChunks != null) {
                appendLine("Latest buffer: ${summary.readyChunks} ready chunks; ${summary.bufferedListeningMs} ms at playback speed")
            }
            extractionPerformance.value?.let { extraction ->
                appendLine("Latest extraction: ${extraction.engine}; ${extraction.characters} characters; ${extraction.elapsedMs} ms; cached=${extraction.cached}")
                extraction.charactersPerMinute?.let { appendLine("Extraction: ${formatMetric(it)} characters/minute") }
            }
        }.trimEnd()
    }

    private fun formatMetric(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    suspend fun record(model: String, stage: PlaybackStage, error: Throwable? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val checkpoint = PlaybackCheckpoint(
                BuildConfig.VERSION_NAME, model.takeIf { it in setOf("kokoro", "kokoro-misaki", "pocket") } ?: "unknown",
                stage, System.currentTimeMillis(),
                generateSequence(error) { it.cause }.take(4).map { it.javaClass.name }.toList(),
            )
            current = checkpoint
            if (stage != PlaybackStage.FAILED) return@withLock
            try {
                if (!directory.isDirectory && !directory.mkdirs()) return@withLock
                val partial = File(directory, "playback.part")
                partial.outputStream().use { output ->
                    output.write(Json.encodeToString(PlaybackCheckpoint.serializer(), checkpoint).toByteArray())
                    output.fd.sync()
                }
                if (!partial.renameTo(file)) partial.delete()
            } catch (_: IOException) {
                // A full disk must not turn optional diagnostics into a playback failure.
            }
        }
    }

    suspend fun checkpoint(): PlaybackCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { current ?: if (file.isFile) Json.decodeFromString<PlaybackCheckpoint>(file.readText()).takeIf { it.stage == PlaybackStage.FAILED } else null }
            catch (_: Exception) { null }
        }
    }

    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        val checkpoint = checkpoint()
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .joinToString("\n") { exit ->
                    "${Instant.ofEpochMilli(exit.timestamp)}: ${exitReason(exit.reason)}, status=${exit.status}, pss=${exit.pss} KiB, rss=${exit.rss} KiB"
                }.ifBlank { "No process exits recorded by Android" }
        } catch (_: Exception) { "Android process-exit history unavailable" }
        buildString {
            appendLine("Read Assist playback diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Java heap limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB")
            appendLine(if (app.readflow.speech.SpeechDistribution.IS_PLAY_BUILD)
                "Runtime: Misaki; ONNX Runtime 1.22.0; CPU, configurable threads"
                else "Runtime: Sherpa 1.13.8 / experimental Misaki; shared ORT 1.22.0; CPU, configurable threads")
            appendLine(performanceReport())
            appendLine("Last playback step: ${checkpoint?.let { Json.encodeToString(PlaybackCheckpoint.serializer(), it) } ?: "No playback attempt recorded"}")
            appendLine("Recent process exits:\n$exits")
            append("No document text, titles, URLs, or audio included. Nothing uploaded.")
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_ANR -> "Not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User stopped app"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App exited"
        else -> "Android reason $reason"
    }
}
