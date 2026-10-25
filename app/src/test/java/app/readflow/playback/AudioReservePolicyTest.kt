package app.readflow.playback

import org.junit.Assert.*
import org.junit.Test

class AudioReservePolicyTest {
    private val policy = AudioReservePolicy()

    @Test fun fourFutureSentencesAndEnoughListeningTimeStopPreparation() {
        assertFalse(policy.shouldPrepareMore(AudioReserve(4, 5, 30_000)))
        assertTrue(policy.shouldPrepareMore(AudioReserve(4, 5, 29_999)))
        assertTrue(policy.shouldPrepareMore(AudioReserve(3, 5, 60_000)))
    }

    @Test fun fragmentsOfCurrentOrFutureSentenceDoNotCountAsCompleteSentences() {
        val reserve = audioReserve(listOf(
            segment("current", completes = false),
            segment("current", completes = false),
            segment("current"),
            segment("next", completes = false),
            segment("next", completes = false),
        ), currentPositionMs = 1_000, speed = 1f)
        assertEquals(0, reserve.futureSentenceCount)
        assertEquals(5, reserve.queuedChunkCount)
        assertEquals(49_000L, reserve.remainingListeningMs)
        assertTrue(policy.shouldPrepareMore(reserve))
    }

    @Test fun completingTheLastChunkCountsEachFutureSentenceOnce() {
        val reserve = audioReserve(listOf(
            segment("current"),
            segment("a", completes = false), segment("a"),
            segment("b"), segment("c"), segment("d"),
        ), 0, 1f)
        assertEquals(4, reserve.futureSentenceCount)
        assertFalse(policy.shouldPrepareMore(reserve))
    }

    @Test fun countDoesNotGrowFromDuplicateCompleteMarkers() {
        val reserve = audioReserve(listOf(segment("current"), segment("next"), segment("next")), 0, 1f)
        assertEquals(1, reserve.futureSentenceCount)
    }

    @Test fun doubleSpeedRequiresTwiceThePreparedAudio() {
        val segments = (0..4).map { segment(it.toString(), durationMs = 8_000) }
        val normal = audioReserve(segments, 0, 1f)
        val fast = audioReserve(segments, 0, 2f)
        assertEquals(40_000L, normal.remainingListeningMs)
        assertEquals(20_000L, fast.remainingListeningMs)
        assertFalse(policy.shouldPrepareMore(normal))
        assertTrue(policy.shouldPrepareMore(fast))
        assertEquals(80_000L, audioReserve(segments, 0, .5f).remainingListeningMs)
    }

    @Test fun currentPositionIsRemovedBeforeConvertingToListeningTime() {
        assertEquals(7_000L,
            audioReserve(listOf(segment("current"), segment("next")), 6_000, 2f).remainingListeningMs)
        assertEquals(10_000L,
            audioReserve(listOf(segment("current"), segment("next")), -500, 2f).remainingListeningMs)
        assertEquals(5_000L,
            audioReserve(listOf(segment("current"), segment("next")), Long.MAX_VALUE, 2f).remainingListeningMs)
    }

    @Test fun timeAndChunkLimitsBoundHugeOrVeryShortSentences() {
        val longSentence = AudioReserve(0, 8, 120_000)
        val tinyFragments = AudioReserve(0, 64, 10_000)
        for (reserve in listOf(longSentence, tinyFragments)) {
            assertFalse(policy.shouldPrepareMore(reserve))
            assertTrue(policy.canStart(reserve, 1_000, 1_000))
        }
        assertTrue(policy.shouldPrepareMore(AudioReserve(0, 63, 119_999)))
    }

    @Test fun temporaryPressureCapsTheQueueWithoutReturningToAnEightSecondTarget() {
        assertTrue(policy.shouldPrepareMore(AudioReserve(0, 1, 8_000), constrained = true))
        assertTrue(policy.shouldPrepareMore(AudioReserve(4, 5, 29_999), constrained = true))
        assertFalse(policy.shouldPrepareMore(AudioReserve(4, 5, 30_000), constrained = true))
        assertFalse(policy.shouldPrepareMore(AudioReserve(0, 10, 60_000), constrained = true))
        assertFalse(policy.shouldPrepareMore(AudioReserve(0, 24, 10_000), constrained = true))
        assertTrue(policy.canStart(AudioReserve(0, 24, 10_000), 1_000, 1_000, constrained = true))
    }

    @Test fun startupWaitsForReserveButStartsImmediatelyWhenItIsReady() {
        assertFalse(policy.canStart(AudioReserve(3, 4, 30_000), 1_000, 1_000))
        assertFalse(policy.canStart(AudioReserve(4, 5, 29_999), 1_000, 1_000))
        assertTrue(policy.canStart(AudioReserve(4, 5, 30_000), 1_000, 1_000))
    }

    @Test fun slowPreparationHasABoundedWaitFromFirstPlayableAudio() {
        val reserve = AudioReserve(0, 1, 1_000)
        assertFalse(policy.canStart(reserve, 1_000, 8_999))
        assertTrue(policy.canStart(reserve, 1_000, 9_000))
        assertFalse(policy.canStart(reserve, null, 9_000))
        assertFalse(policy.canStart(reserve, 1_000, 999))
        assertFalse(policy.canStart(reserve, -1, 9_000))
    }

    @Test fun shortDocumentStartsAtEndOfInputWithoutFourFutureSentences() {
        val reserve = AudioReserve(0, 1, 500)
        assertFalse(policy.canStart(reserve, 1_000, 1_000))
        assertTrue(policy.canStart(reserve, 1_000, 1_000, inputComplete = true))
    }

    @Test fun zeroDurationOrMissingAudioNeverStartsEvenAfterDeadlineOrEndOfInput() {
        for (reserve in listOf(AudioReserve(0, 0, 0), AudioReserve(4, 64, 0), AudioReserve(4, 0, 30_000))) {
            assertFalse(policy.canStart(reserve, 0, 9_000, inputComplete = true))
        }
        assertEquals(AudioReserve(0, 0, 0), audioReserve(emptyList(), 1_000, 1f))
        assertEquals(0L, audioReserve(listOf(segment("current", durationMs = 0)), 0, 1f).remainingListeningMs)
    }

    @Test fun invalidSpeedFallsBackToNormalAndDurationArithmeticDoesNotOverflow() {
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(12_000L, listeningTimeMs(12_000, speed))
        }
        assertEquals(0L, listeningTimeMs(-1, 1f))
        assertEquals(Long.MAX_VALUE, listeningTimeMs(Long.MAX_VALUE, .5f))
        assertEquals(Long.MAX_VALUE, audioReserve(listOf(
            segment("current", durationMs = Long.MAX_VALUE), segment("next", durationMs = Long.MAX_VALUE),
        ), 0, 1f).remainingListeningMs)
    }

    @Test fun negativeSnapshotValuesAndInvalidSegmentMetadataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AudioReserve(-1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioReserve(0, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioReserve(0, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { PreparedAudioSegment("", 1, true) }
        assertThrows(IllegalArgumentException::class.java) { PreparedAudioSegment("sentence", -1, true) }
    }

    @Test fun memoryPressureExpiresAndFurtherSignalsExtendOnlyTheTemporaryWindow() {
        val pressure = MemoryPressureWindow()
        assertFalse(pressure.isActive(0))
        pressure.notePressure(1_000)
        assertTrue(pressure.isActive(1_000))
        assertTrue(pressure.isActive(60_999))
        assertFalse(pressure.isActive(61_000))
        assertFalse(pressure.isActive(1_000_000))
        pressure.notePressure(2_000_000)
        pressure.notePressure(2_050_000)
        assertTrue(pressure.isActive(2_100_000))
        assertFalse(pressure.isActive(2_110_000))
    }

    @Test fun backgroundingTheUiDoesNotReduceTheSpeechReserve() {
        assertFalse(isMemoryPressureLevel(20)) // TRIM_MEMORY_UI_HIDDEN
        assertFalse(isMemoryPressureLevel(5)) // TRIM_MEMORY_RUNNING_MODERATE
        assertTrue(isMemoryPressureLevel(10)) // TRIM_MEMORY_RUNNING_LOW
        assertTrue(isMemoryPressureLevel(15)) // TRIM_MEMORY_RUNNING_CRITICAL
        assertTrue(isMemoryPressureLevel(40)) // TRIM_MEMORY_BACKGROUND
        assertTrue(isMemoryPressureLevel(60)) // TRIM_MEMORY_MODERATE
        assertTrue(isMemoryPressureLevel(80)) // TRIM_MEMORY_COMPLETE
    }

    @Test fun unknownTrimLevelsFollowTheirRangeWithoutConfusingUiWithPressure() {
        assertFalse(isMemoryPressureLevel(-1))
        assertFalse(isMemoryPressureLevel(0))
        assertFalse(isMemoryPressureLevel(9))
        assertTrue(isMemoryPressureLevel(12))
        assertTrue(isMemoryPressureLevel(19))
        assertFalse(isMemoryPressureLevel(25))
        assertFalse(isMemoryPressureLevel(39))
        assertTrue(isMemoryPressureLevel(41))
        assertTrue(isMemoryPressureLevel(100))
    }

    @Test fun invalidPressureTimesCannotCreatePermanentPressure() {
        val pressure = MemoryPressureWindow()
        pressure.notePressure(Long.MAX_VALUE - 5)
        assertTrue(pressure.isActive(Long.MAX_VALUE))
        assertFalse(pressure.isActive(0))
        assertThrows(IllegalArgumentException::class.java) { pressure.notePressure(-1) }
        assertThrows(IllegalArgumentException::class.java) { MemoryPressureWindow(0) }
    }

    private fun segment(id: String, durationMs: Long = 10_000, completes: Boolean = true) =
        PreparedAudioSegment(id, durationMs, completes)
}
