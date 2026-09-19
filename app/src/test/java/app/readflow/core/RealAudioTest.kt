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
        val root = File("../docs/evidence")
        assumeTrue("Run scripts/prototype.py for both engines first", listOf("kokoro", "pocket").all { File(root, "$it/run.json").exists() })
        for (model in listOf("kokoro", "pocket")) {
            val dir = File(root, model)
            val meta = Json.parseToJsonElement(File(dir, "run.json").readText()).jsonObject
            val page = DocumentPipeline().process("prototype", 0, ExtractedPage(listOf(TextElement(meta.getValue("text").jsonPrimitive.content, emptyList())), 600f, 800f, "fixture"))
            val speech = EnglishNormalizer().normalize(page.words)
            val buffer = ByteBuffer.wrap(File(dir, "logits.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val logits = Array(meta.getValue("frames").jsonPrimitive.int) { FloatArray(32) { buffer.float } }
            val timings = CtcAlignment().align(logits, speech, meta.getValue("sampleRate").jsonPrimitive.int, meta.getValue("sampleCount").jsonPrimitive.int)
            assertEquals(page.words.size, timings.size)
            assertTrue(timings.zipWithNext().all { (a, b) -> a.startSample < b.startSample && a.endSample <= b.startSample })
            File(dir, "timings.json").writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WordTiming.serializer()), timings))
            File(dir, "source.json").writeText(Json.encodeToString(PageContent.serializer(), page))
            for (word in timings) {
                val milliseconds = (word.startSample * 1000 + meta.getValue("sampleRate").jsonPrimitive.int - 1) / meta.getValue("sampleRate").jsonPrimitive.int
                assertEquals(word.wordId, activeWord(timings, milliseconds, meta.getValue("sampleRate").jsonPrimitive.int)?.wordId)
            }
        }
    }
}
