package app.readflow.benchmark

/** Bounded numeric aggregation: no per-second samples or document content are retained. */
internal class BenchmarkMemorySamples {
    data class Summary(val peakMiB: Double?, val count: Int)
    private var peak: Double? = null
    private var count = 0
    private var rowActive = false
    private var rowPeak: Double? = null
    private var rowCount = 0

    @Synchronized fun beginRow() { rowActive = true; rowPeak = null; rowCount = 0 }
    @Synchronized fun add(pssMiB: Double?) {
        if (pssMiB == null || !pssMiB.isFinite() || pssMiB < 0) return
        peak = maxOf(peak ?: pssMiB, pssMiB); count++
        if (rowActive) { rowPeak = maxOf(rowPeak ?: pssMiB, pssMiB); rowCount++ }
    }
    @Synchronized fun finishRow(): Summary {
        rowActive = false
        return Summary(rowPeak, rowCount).also { rowPeak = null; rowCount = 0 }
    }
    @Synchronized fun overall(): Summary = Summary(peak, count)
}

internal data class BenchmarkSpeechPass(val threads: Int?, val warmSamples: Int, val sustained: Boolean = false) {
    val measurements get() = warmSamples + 2 // load, first sample, then warm samples
}

/** Only one pass/model is resident. Sustained runs follow all short comparisons. */
internal fun speechPasses(options: BenchmarkOptions, model: String): List<BenchmarkSpeechPass> {
    val external = model == "sc-kokoro"
    val threads = if (external) listOf(null) else if (options.compareCpuThreads) listOf(2, 3, 4) else listOf(options.speechThreads)
    return threads.map { BenchmarkSpeechPass(it, options.repetitions) } +
        if (options.sustainedSpeechSamples > 0) listOf(BenchmarkSpeechPass(
            if (external) null else options.speechThreads, options.sustainedSpeechSamples, sustained = true)) else emptyList()
}

internal fun benchmarkMeasurementCount(options: BenchmarkOptions): Int =
    options.speechModels.sumOf { model -> speechPasses(options, model).sumOf { it.measurements } } + options.ocrEngines.size * options.repetitions
