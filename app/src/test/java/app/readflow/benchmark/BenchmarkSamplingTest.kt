package app.readflow.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkSamplingTest {
    @Test fun keepsTransientPeakEvenWhenCompletedMeasurementIsSmall() {
        val samples = BenchmarkMemorySamples()
        samples.add(250.0)
        samples.beginRow()
        samples.add(300.0); samples.add(1800.0); samples.add(275.0)
        val row = samples.finishRow()
        assertEquals(1800.0, row.peakMiB!!, 0.0)
        assertEquals(3, row.count)
        assertEquals(1800.0, samples.overall().peakMiB!!, 0.0)
        assertEquals(4, samples.overall().count)
    }

    @Test fun cancelledRowsStillContributeToWholeRunPeak() {
        val samples = BenchmarkMemorySamples()
        samples.beginRow(); samples.add(2000.0)
        // No row is committed when inference is cancelled.
        assertEquals(2000.0, samples.overall().peakMiB!!, 0.0)
    }

    @Test fun rowMeasurementsDoNotLeakAcrossSkippedOrLaterRows() {
        val samples = BenchmarkMemorySamples()
        samples.beginRow(); samples.add(500.0); samples.finishRow()
        assertEquals(0, samples.finishRow().count)
        samples.beginRow(); samples.add(200.0)
        assertEquals(200.0, samples.finishRow().peakMiB!!, 0.0)
        assertEquals(500.0, samples.overall().peakMiB!!, 0.0)
    }

    @Test fun ignoresUnavailableOrNonfiniteSamples() {
        val samples = BenchmarkMemorySamples()
        samples.beginRow()
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach(samples::add)
        assertNull(samples.overall().peakMiB)
        assertEquals(0, samples.finishRow().count)
    }

    @Test fun cpuComparisonsNeverAssignExternalEngineThreadCounts() {
        val options = BenchmarkOptions(speechModels = listOf("kokoro", "sc-kokoro"), compareCpuThreads = true, repetitions = 3)
        assertEquals(listOf(2, 3, 4), speechPasses(options, "kokoro").map { it.threads })
        assertEquals(listOf<Int?>(null), speechPasses(options, "sc-kokoro").map { it.threads })
        assertEquals(23, benchmarkMeasurementCount(options)) // 3 CPU blocks + external + 3 OCR
    }

    @Test fun sustainedPassRunsOnlyAfterShortComparisonsAtSelectedThreads() {
        val options = BenchmarkOptions(compareCpuThreads = true, speechThreads = 1, sustainedSpeechSamples = 60)
        val passes = speechPasses(options, "kokoro")
        assertEquals(4, passes.size)
        assertEquals(BenchmarkSpeechPass(1, 60, sustained = true), passes.last())
        assertEquals(1, passes.count { it.sustained })
    }

    @Test fun maximumOptionsStayInsideBoundedReport() {
        val options = BenchmarkOptions(speechModels = listOf("kokoro", "pocket", "sc-kokoro"),
            ocrEngines = listOf("mlkit", "teleocr", "paddleocr"), repetitions = 5,
            compareCpuThreads = true, sustainedSpeechSamples = 60).validated()
        assertEquals(250, benchmarkMeasurementCount(options))
        assertTrue(benchmarkMeasurementCount(options) <= BenchmarkStore.MAX_ROWS)
    }
}
