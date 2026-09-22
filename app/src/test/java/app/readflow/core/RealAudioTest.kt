package app.readflow.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealAudioTest {
    @Test fun bothModelsAlignActualAudioWithProductionKotlinAlgorithm() {
        verify(File("../docs/evidence"))
    }
    @Test fun kokoroAlignsOcrExpansionsToTheirOriginalSourceWords() {
        val root = File("../docs/evidence/ocr-normalization")
        verify(root, File(root, "input.txt").readText().trim(), listOf("kokoro"))
    }
    @Test fun bothModelsAlignAccentedNamesToTheUnchangedSourceWords() {
        val root = File("../docs/evidence/accented-speech")
        verify(root, File(root, "input.txt").readText().trim())
    }
    @Test fun recordedPocketOcrFixtureStillRejectsItsLowConfidenceFirstWord() {
        val root = File("../docs/evidence/ocr-normalization")
        val failure = assertThrows(AlignmentConfidenceException::class.java) {
            verify(root, File(root, "input.txt").readText().trim(), listOf("pocket"))
        }
        assertEquals(0, failure.tokenIndex)
        assertEquals("YOU", failure.token)
        assertTrue(failure.confidence < failure.threshold)
    }
    private fun verify(root: File, sourceText: String? = null, models: List<String> = listOf("kokoro", "pocket")) {
        assumeTrue("Run scripts/prototype.py for the selected engines first", models.all { File(root, "$it/run.json").exists() })
        for (model in models) {
            val dir = File(root, model)
            val meta = Json.parseToJsonElement(File(dir, "run.json").readText()).jsonObject
            val page = DocumentPipeline().process("prototype", 0, ExtractedPage(listOf(TextElement(sourceText ?: meta.getValue("text").jsonPrimitive.content, emptyList())), 600f, 800f, "fixture"))
            val speech = EnglishNormalizer().normalize(page.words)
            assertEquals("The aligned transcript must be the exact synthesized text", meta.getValue("text").jsonPrimitive.content, speech.text)
            val buffer = ByteBuffer.wrap(File(dir, "logits.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val logits = Array(meta.getValue("frames").jsonPrimitive.int) { FloatArray(32) { buffer.float } }
            if (sourceText != null) {
                File(dir, "source.json").writeText(Json.encodeToString(PageContent.serializer(), page))
                File(dir, "speech.json").writeText(Json.encodeToString(SpeechText.serializer(), speech))
            }
            val timings = try { CtcAlignment().align(logits, speech, meta.getValue("sampleRate").jsonPrimitive.int, meta.getValue("sampleCount").jsonPrimitive.int) }
            catch (error: AlignmentConfidenceException) {
                if (sourceText != null) File(dir, "alignment-result.json").writeText(buildJsonObject {
                    put("status", "REJECTED_LOW_CONFIDENCE"); put("tokenIndex", error.tokenIndex); put("token", error.token)
                    put("confidence", error.confidence); put("threshold", error.threshold); put("wordSyncAccepted", false)
                }.toString())
                throw error
            }
            assertEquals(page.words.size, timings.size)
            assertTrue(timings.zipWithNext().all { (a, b) -> a.startSample < b.startSample && a.endSample <= b.startSample })
            File(dir, "timings.json").writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WordTiming.serializer()), timings))
            File(dir, "source.json").writeText(Json.encodeToString(PageContent.serializer(), page))
            if (sourceText != null) {
                val tokenSpeech = speech.copy(tokens = speech.tokens.mapIndexed { index, token -> token.copy(sourceIds = listOf("token-$index")) })
                val tokenTimings = CtcAlignment().align(logits, tokenSpeech, meta.getValue("sampleRate").jsonPrimitive.int, meta.getValue("sampleCount").jsonPrimitive.int)
                for (word in page.words.filter { it.text in listOf("|", "money.4") }) {
                    val tokenIds = speech.tokens.indices.filter { word.id in speech.tokens[it].sourceIds }.map { "token-$it" }
                    assertEquals(2, tokenIds.size)
                    val spans = tokenTimings.filter { it.wordId in tokenIds }
                    val timing = timings.single { it.wordId == word.id }
                    assertEquals(spans.first().startSample, timing.startSample)
                    assertEquals(spans.last().endSample, timing.endSample)
                }
                File(dir, "alignment-result.json").writeText(buildJsonObject {
                    put("status", "HOST_MAPPING_CHECK_PASSED"); put("sourceWords", timings.size)
                    put("minimumConfidence", timings.minOf { it.confidence }); put("auditoryBoundariesAudited", false)
                    put("deviceVerified", false)
                }.toString())
            }
            for (word in timings) {
                val milliseconds = (word.startSample * 1000 + meta.getValue("sampleRate").jsonPrimitive.int - 1) / meta.getValue("sampleRate").jsonPrimitive.int
                assertEquals(word.wordId, activeWord(timings, milliseconds, meta.getValue("sampleRate").jsonPrimitive.int)?.wordId)
            }
        }
    }
}
