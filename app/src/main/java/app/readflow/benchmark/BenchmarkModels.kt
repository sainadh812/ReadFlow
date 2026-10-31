package app.readflow.benchmark

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable
import app.readflow.core.RecognizedTable
import app.readflow.speech.SpeechDistribution

@Serializable
data class BenchmarkOptions(
    val speechModels: List<String> = listOf(if (SpeechDistribution.IS_PLAY_BUILD) "kokoro-misaki" else "kokoro"),
    val ocrEngines: List<String> = listOf("mlkit"),
    val voice: String = "0",
    val ocrQuality: String = "balanced",
    /** Speech warm samples after one cold sample; OCR total page runs per selected engine. */
    val repetitions: Int = 3,
    val speechThreads: Int = 2,
    val alignmentThreads: Int = 2,
    val allowSpinning: Boolean = false,
    /** Sequential 2/3/4-thread warm blocks; one resident model, with thermal state reported. */
    val compareCpuThreads: Boolean = false,
    /** Longer preparation sequence including alignment; not a speaker/underrun measurement. */
    val sustainedSpeechSamples: Int = 0,
    /** Compare names and contextual pronunciations; generated audio still requires listening. */
    val speechFixture: String = "financial",
) {
    fun validated(): BenchmarkOptions {
        require(repetitions in setOf(1, 3, 5)) { "Choose 1, 3, or 5 samples" }
        require(speechModels.all { it in setOf("kokoro", "kokoro-misaki", "pocket", "sc-kokoro") }) { "Unknown speech model" }
        require(ocrEngines.all { it in setOf("mlkit", "teleocr", "paddleocr") }) { "Unknown OCR engine" }
        require(speechThreads in 1..4 && alignmentThreads in 1..4) { "Choose 1–4 CPU threads" }
        require(sustainedSpeechSamples in setOf(0, 30, 60)) { "Choose a supported sustained test length" }
        require(ocrQuality in setOf("fast", "balanced", "high")) { "Unknown OCR quality" }
        require(speechFixture in setOf("financial", "pronunciation")) { "Unknown speech samples" }
        require(speechModels.isNotEmpty() || ocrEngines.isNotEmpty()) { "Select a speech or OCR test" }
        return copy(speechModels = speechModels.distinct(), ocrEngines = ocrEngines.distinct())
    }

    /** Historical reports may mention other engines; only the build's engines can execute. */
    internal fun validatedForExecution(): BenchmarkOptions = validated().also { options ->
        require(options.speechModels.all { it in SpeechDistribution.benchmarkModels }) { "Speech model unavailable in this build" }
    }
}

@Serializable enum class BenchmarkStatus { IDLE, RUNNING, COMPLETED, CANCELLED, FAILED }
@Serializable enum class BenchmarkRowStatus { SUCCESS, FAILED, SKIPPED }

@Serializable
data class DeviceSnapshot(
    val device: String = "",
    val androidApi: Int = 0,
    val thermalStatus: Int? = null,
    val batteryTemperatureC: Double? = null,
    /** Whole process proportional set size, not Java heap or whole-device RAM. */
    val processPssMiB: Double? = null,
    val charging: Boolean? = null,
    val powerSave: Boolean? = null,
    val availableMemoryMiB: Double? = null,
)

@Serializable
data class BenchmarkRow(
    val kind: String,
    val model: String,
    val sample: String,
    val status: BenchmarkRowStatus = BenchmarkRowStatus.SUCCESS,
    val cold: Boolean = false,
    val totalMs: Long = 0,
    val verifyMs: Long? = null,
    val loadMs: Long? = null,
    val synthesisMs: Long? = null,
    val alignmentMs: Long? = null,
    val characters: Int? = null,
    val audioSeconds: Double? = null,
    val charactersPerMinute: Double? = null,
    /** Seconds of generated audio divided by total preparation seconds; larger is faster. */
    val preparationSpeed: Double? = null,
    /** Edit distance / reference characters; can exceed 1 when OCR inserts extra text. */
    val characterErrorRate: Double? = null,
    val tableReadingOrderCorrect: Boolean? = null,
    val alignedWords: Int? = null,
    val alignmentAccepted: Boolean? = null,
    val voice: String? = null,
    val modelVersion: String? = null,
    val note: String? = null,
    /** Sampled after this measurement, before speech-model teardown. Not a peak reading. */
    val deviceAfter: DeviceSnapshot? = null,
    val observedPeakPssMiB: Double? = null,
    val memorySamples: Int = 0,
    val backend: String? = null,
    val speechThreads: Int? = null,
    val alignmentThreads: Int? = null,
    val allowSpinning: Boolean? = null,
    val resampleMs: Long? = null,
    val alignmentInferenceMs: Long? = null,
    val alignmentDecodeMs: Long? = null,
    val alignmentLoadMs: Long? = null,
    val alignmentNormalizationMs: Long? = null,
    val alignmentOutputCopyMs: Long? = null,
    val ocrLayoutMs: Long? = null,
    val ocrRecognitionMs: Long? = null,
    /** Synthetic fixture output only; original user documents are never included. */
    val recognizedText: String? = null,
    val referenceText: String? = null,
    val tableStructureCorrect: Boolean? = null,
    val tableCellAccuracy: Double? = null,
    val phonemizerMs: Long? = null,
    val speechInferenceMs: Long? = null,
    /** Original built-in fixture and the exact normalized text passed to the speech engine. */
    val inputText: String? = null,
    val normalizedSpeechText: String? = null,
)

@Serializable
data class BenchmarkState(
    val schemaVersion: Int = 2,
    val fixtureVersion: String = BenchmarkFixtures.VERSION,
    val appVersion: String = "",
    val status: BenchmarkStatus = BenchmarkStatus.IDLE,
    val phase: String = "Ready to test this phone",
    val options: BenchmarkOptions = BenchmarkOptions(),
    val startedAtMs: Long = 0,
    val elapsedMs: Long = 0,
    val completed: Int = 0,
    val total: Int = 0,
    val rows: List<BenchmarkRow> = emptyList(),
    val before: DeviceSnapshot? = null,
    val after: DeviceSnapshot? = null,
    /** Highest observed PSS, sampled during work every second; brief peaks may still be missed. */
    val highestObservedPssMiB: Double? = null,
    val memorySampleCount: Int = 0,
    val error: String? = null,
    val cancellationReason: String? = null,
    val currentDevice: DeviceSnapshot? = null,
    val memorySamplingIntervalMs: Long = 1000,
    val speechFixtureVersion: String? = null,
) {
    val running get() = status == BenchmarkStatus.RUNNING
}

/** Pure calculations shared by tests and reports. No wall-clock averages are invented. */
object BenchmarkMetrics {
    private const val MAX_REFERENCE_CHARACTERS = 4096
    private const val MAX_RECOGNIZED_CHARACTERS = 8192

    fun characters(text: String): Int = text.codePointCount(0, text.length)

    fun charactersPerMinute(characters: Int, elapsedMs: Long): Double? =
        if (characters >= 0 && elapsedMs > 0) characters * 60_000.0 / elapsedMs else null

    fun preparationSpeed(audioSeconds: Double, elapsedMs: Long): Double? =
        if (audioSeconds.isFinite() && audioSeconds > 0 && elapsedMs > 0) audioSeconds * 1000 / elapsedMs else null

    /** Preserve spelling, case, accents and punctuation; ignore line wrapping and equivalent Unicode. */
    fun normalizeOcr(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace(Regex("[\\s\\p{Z}]+"), " ").trim()

    fun characterErrorRate(reference: String, recognized: String): Double? {
        val expected = normalizeOcr(reference).codePoints().toArray()
        val actual = normalizeOcr(recognized).codePoints().toArray()
        if (expected.isEmpty() || expected.size > MAX_REFERENCE_CHARACTERS || actual.size > MAX_RECOGNIZED_CHARACTERS) return null
        var previous = IntArray(actual.size + 1) { it }
        var current = IntArray(actual.size + 1)
        for (i in expected.indices) {
            current[0] = i + 1
            for (j in actual.indices) current[j + 1] = minOf(
                current[j] + 1, previous[j + 1] + 1, previous[j] + if (expected[i] == actual[j]) 0 else 1)
            val swap = previous; previous = current; current = swap
        }
        return previous[actual.size].toDouble() / expected.size
    }

    /** Checks exact contiguous cell tokens in row order, not table geometry or general table accuracy. */
    fun tableReadingOrderCorrect(text: String): Boolean {
        val actual = normalizeOcr(text).lowercase(Locale.ROOT).split(' ')
        val expected = BenchmarkFixtures.TABLE_ROWS.flatMap { it }.joinToString(" ")
            .lowercase(Locale.ROOT).split(' ')
        return actual.windowed(expected.size).any { it == expected }
    }

    fun tableStructureCorrect(tables: List<RecognizedTable>): Boolean = tables.size == 1 && tables.single().let {
        it.rows == BenchmarkFixtures.TABLE_ROWS.size && it.columns == BenchmarkFixtures.TABLE_ROWS.first().size &&
            it.cells.size == it.rows * it.columns && it.cells.all { cell -> cell.rowSpan == 1 && cell.columnSpan == 1 } &&
            it.cells.map { cell -> cell.row to cell.column }.toSet().size == it.cells.size &&
            it.cells.all { cell -> cell.row in 0 until it.rows && cell.column in 0 until it.columns }
    }

    /** Values are checked in their actual cell positions, preserving financial punctuation. */
    fun tableCellAccuracy(tables: List<RecognizedTable>): Double {
        if (tables.size != 1) return 0.0
        val cells = tables.single().cells.groupBy { it.row to it.column }
        val expected = BenchmarkFixtures.TABLE_ROWS
        val matches = expected.mapIndexed { row, values -> values.mapIndexed { column, value ->
            cells[row to column]?.singleOrNull()?.let { it.rowSpan == 1 && it.columnSpan == 1 && normalizeOcr(it.text) == value } == true
        }.count { it } }.sum()
        return matches.toDouble() / maxOf(expected.sumOf { it.size }, tables.single().cells.size)
    }
}
