package app.readflow.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAlignmentTest {
    private fun speech(text: String = "Read this sentence.") = EnglishNormalizer().normalize(
        DocumentPipeline().process("playback", 0, ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "fixture")).words)

    private fun chunk(speech: SpeechText, timings: List<WordTiming> = emptyList()) = AlignedAudio(
        "key", "audio.wav", "sha", 24000, 48000, timings, speech.sourceIds, speech, "kokoro", "0", CtcAlignment.VERSION)

    @Test fun recordedRejectedAudioRemainsPlayableWithoutInventedHighlighting() = runTest {
        val root = File("../docs/evidence/ocr-normalization")
        val meta = Json.parseToJsonElement(File(root, "pocket/run.json").readText()).jsonObject
        val speech = speech(File(root, "input.txt").readText().trim())
        val bytes = ByteBuffer.wrap(File(root, "pocket/logits.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val logits = Array(meta.getValue("frames").jsonPrimitive.int) { FloatArray(32) { bytes.float } }
        val rate = meta.getValue("sampleRate").jsonPrimitive.int
        val count = meta.getValue("sampleCount").jsonPrimitive.int
        val aligner = object : SpeechAligner {
            override suspend fun align(audio: PcmAudio, text: SpeechText) = CtcAlignment().align(logits, text, rate, count)
        }
        val result = alignForPlayback(aligner, PcmAudio(FloatArray(count), rate), speech)
        assertEquals("YOU", result.rejected!!.token)
        assertTrue(result.rejected.confidence < result.rejected.threshold)
        assertTrue(result.timings.isEmpty())
        val playable = chunk(speech, result.timings)
        assertEquals(0L, playbackStartMs(playable, speech.tokens.first().sourceIds.first()))
        assertTrue(activeWordIds(playable.timings, 500, playable.sampleRate).isEmpty())
        assertEquals(speech.tokens.first().sourceIds.first(), playbackResumeWord(playable, null))
    }

    @Test fun acceptedBoundariesAndSampleRoundingArePreserved() = runTest {
        val speech = speech()
        val timings = speech.sourceIds.mapIndexed { index, id -> WordTiming(id, index * 5000L + 1, index * 5000L + 4000, .9f) }
        val aligner = object : SpeechAligner {
            override suspend fun align(audio: PcmAudio, text: SpeechText) = timings
        }
        val result = alignForPlayback(aligner, PcmAudio(FloatArray(16000), 24000), speech)
        assertNull(result.rejected)
        assertSame(timings, result.timings)
        assertEquals(209L, playbackStartMs(chunk(speech, timings), speech.sourceIds[1]))
        assertEquals(speech.sourceIds[1], playbackResumeWord(chunk(speech, timings), speech.sourceIds[1]))
        assertNull(playbackResumeWord(chunk(speech, timings), null))
    }

    @Test fun cancellationAndInferenceFailuresStillPropagate() = runTest {
        for (failure in listOf(CancellationException("superseded"), IllegalArgumentException("bad model"), IllegalStateException("no session"))) {
            val aligner = object : SpeechAligner {
                override suspend fun align(audio: PcmAudio, text: SpeechText): List<WordTiming> = throw failure
            }
            try {
                alignForPlayback(aligner, PcmAudio(FloatArray(100), 24000), speech())
                fail("Swallowed ${failure.javaClass.simpleName}")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
    }

    @Test fun unsynchronizedAudioCannotPretendToSeekToAnInteriorWord() {
        val speech = speech()
        assertThrows(IllegalStateException::class.java) { playbackStartMs(chunk(speech), speech.sourceIds[1]) }
        assertThrows(IllegalStateException::class.java) { playbackStartMs(chunk(speech), "stale-id") }
    }

    @Test fun aLaterReliableChunkRestoresRealHighlighting() = runTest {
        val speech = speech()
        val timing = WordTiming(speech.sourceIds.first(), 0, 12000, .9f)
        var calls = 0
        val aligner = object : SpeechAligner {
            override suspend fun align(audio: PcmAudio, text: SpeechText): List<WordTiming> {
                if (calls++ == 0) throw AlignmentConfidenceException(0, text.tokens.first().text, text.tokens.first().sourceIds, .01f, .12f)
                return listOf(timing)
            }
        }
        val audio = PcmAudio(FloatArray(24000), 24000)
        assertTrue(alignForPlayback(aligner, audio, speech).timings.isEmpty())
        val next = alignForPlayback(aligner, audio, speech)
        assertNull(next.rejected)
        assertEquals(setOf(timing.wordId), activeWordIds(next.timings, 100, 24000))
    }

    @Test fun cachedUnhighlightedAudioSurvivesSerialization() {
        val original = chunk(speech())
        val restored = Json.decodeFromString<AlignedAudio>(Json.encodeToString(AlignedAudio.serializer(), original))
        assertEquals(original, restored)
        assertEquals(0L, playbackStartMs(restored, null))
    }
}
