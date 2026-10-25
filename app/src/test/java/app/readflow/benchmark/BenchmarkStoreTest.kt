package app.readflow.benchmark

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchmarkStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyLatestResultIsRetainedAndRoundTripsMeasuredValues() {
        val directory = temporary.newFolder()
        val store = BenchmarkStore(directory)
        store.save(BenchmarkState(status = BenchmarkStatus.COMPLETED, startedAtMs = 1))
        val latest = BenchmarkState(status = BenchmarkStatus.COMPLETED, startedAtMs = 2,
            rows = listOf(BenchmarkRow("speech", "kokoro", "Warm sample", totalMs = 5000,
                audioSeconds = 10.0, preparationSpeed = 2.0, alignmentAccepted = false,
                deviceAfter = DeviceSnapshot(processPssMiB = 512.5))),
            highestObservedPssMiB = 512.5, memorySampleCount = 3)
        store.save(latest)
        assertEquals(latest, store.load())
        assertEquals(listOf("latest.json"), directory.listFiles()!!.map { it.name })
        assertTrue(store.report(latest).contains("\"alignmentAccepted\": false"))
    }

    @Test fun processDeathRetainsCompletedRowsButCannotLookLikeLiveTest() {
        val store = BenchmarkStore(temporary.newFolder())
        val rows = listOf(BenchmarkRow("ocr", "mlkit", "Financial page", totalMs = 1000))
        store.save(BenchmarkState(status = BenchmarkStatus.RUNNING, completed = 1, rows = rows))
        val recovered = store.load()!!
        assertEquals(BenchmarkStatus.CANCELLED, recovered.status)
        assertFalse(recovered.running)
        assertEquals(rows, recovered.rows)
        assertEquals(1, recovered.completed)
    }

    @Test fun misakiReportsPreserveFrontendTimingAndExactNormalizedInput() {
        val store = BenchmarkStore(temporary.newFolder())
        val result = BenchmarkState(status = BenchmarkStatus.COMPLETED,
            options = BenchmarkOptions(speechModels = listOf("kokoro-misaki"), speechFixture = "pronunciation"),
            speechFixtureVersion = BenchmarkFixtures.SPEECH_VERSION,
            rows = listOf(BenchmarkRow("speech", "kokoro-misaki", "Names", phonemizerMs = 45,
                speechInferenceMs = 700, inputText = "Milanković's example.",
                normalizedSpeechText = "Milankovich's example.")))
        store.save(result)
        assertEquals(result, store.load())
        assertTrue(store.report(result).contains("\"phonemizerMs\": 45"))
    }

    @Test fun oldReportsDoNotClaimARecordedSpeechFixtureVersion() {
        val directory = temporary.newFolder()
        File(directory, "latest.json").writeText("""{"status":"COMPLETED","rows":[]}""")
        assertNull(BenchmarkStore(directory).load()!!.speechFixtureVersion)
    }

    @Test fun maximumComparisonWithSpeechDetailsFitsSavedReportBudget() {
        val store = BenchmarkStore(temporary.newFolder())
        val options = BenchmarkOptions(speechModels = listOf("kokoro", "kokoro-misaki", "pocket", "sc-kokoro"),
            ocrEngines = listOf("mlkit", "teleocr", "paddleocr"), repetitions = 5,
            compareCpuThreads = true, sustainedSpeechSamples = 60).validated()
        val report = BenchmarkState(status = BenchmarkStatus.COMPLETED, options = options,
            rows = List(benchmarkMeasurementCount(options)) {
                BenchmarkRow("speech", "kokoro-misaki", "4 speech threads · Sustained sample 60/60",
                    phonemizerMs = 100, speechInferenceMs = 1000, synthesisMs = 1150, alignmentMs = 500,
                    inputText = BenchmarkFixtures.PRONUNCIATION.last(), normalizedSpeechText = "a".repeat(220),
                    modelVersion = "v".repeat(200), note = "n".repeat(1600),
                    deviceAfter = DeviceSnapshot(processPssMiB = 1024.0, batteryTemperatureC = 40.0))
            })
        assertTrue(store.report(report).toByteArray(Charsets.UTF_8).size < BenchmarkStore.MAX_BYTES)
        store.save(report)
        assertEquals(report.rows.size, store.load()!!.rows.size)
    }

    @Test fun cancellationAndPartialFailuresRemainVisible() {
        val store = BenchmarkStore(temporary.newFolder())
        val partial = BenchmarkState(status = BenchmarkStatus.CANCELLED,
            rows = listOf(BenchmarkRow("ocr", "teleocr", "Financial page", BenchmarkRowStatus.FAILED, note = "Fallback excluded")))
        store.save(partial)
        assertEquals(partial, store.load())
    }

    @Test fun missingCorruptAndOversizedReportsAreIgnored() {
        val directory = temporary.newFolder()
        val store = BenchmarkStore(directory)
        assertNull(store.load())
        val file = File(directory, "latest.json")
        file.writeText("bad json")
        assertNull(store.load())
        file.writeText(" ".repeat(BenchmarkStore.MAX_BYTES + 1))
        assertNull(store.load())
    }

    @Test fun rejectsUnboundedHistoryBeforeOverwritingGoodResult() {
        val store = BenchmarkStore(temporary.newFolder())
        val good = BenchmarkState(status = BenchmarkStatus.COMPLETED)
        store.save(good)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(good.copy(rows = List(BenchmarkStore.MAX_ROWS + 1) { BenchmarkRow("ocr", "mlkit", "Page") }))
        }
        assertEquals(good, store.load())
    }
}
