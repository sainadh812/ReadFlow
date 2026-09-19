package app.readflow

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.core.*
import app.readflow.speech.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.models=true; downloads real packs. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechTest {
    @Test fun importedPdfBothEnginesAlignmentAndActualPlayerSeek() = runBlocking {
        assumeTrue("Explicit model/device test not enabled", InstrumentationRegistry.getArguments().getString("models") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ReadFlowApp
        app.models.install("alignment")
        val pdf = File(app.cacheDir, "speech-fixture.pdf").apply {
            instrumentation.context.assets.open("fixtures/selectable.pdf").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val document = app.documents.import(Uri.fromFile(pdf))
        val page = app.documents.loadPage(document, 0)
        val words = chunkWords(page.words).first()
        val speech = EnglishNormalizer().normalize(words)
        val aligner = OnnxForcedAligner { File(app.models.directory("alignment"), "model.onnx") }
        val engine = SherpaEngine(app.models::directory) { id -> app.models.packs.first { it.id == id }.voices }
        try {
            for ((model, voice) in listOf("kokoro" to "0", "pocket" to "alba")) {
                app.models.install(model); app.models.verifyInstalled(model)
                engine.load(model, voice)
                val pcm = engine.synthesize(speech.text) { true }
                val key = "instrumentation-$model"
                val exact = app.cache.writePcm(key, pcm)
                val timings = aligner.align(exact, speech)
                assertEquals(words.size, timings.size)
                val target = timings[timings.size / 2]
                val startMs = (target.startSample * 1000 + pcm.sampleRate - 1) / pcm.sampleRate
                withContext(Dispatchers.Main) {
                    val player = ExoPlayer.Builder(app).build()
                    try {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(app.cache.file(key))))
                        player.seekTo(startMs); player.prepare()
                        withTimeout(30_000) { while (player.playbackState != Player.STATE_READY) delay(30) }
                        assertTrue(kotlin.math.abs(player.currentPosition - startMs) <= 2)
                        assertEquals(target.wordId, activeWord(timings, player.currentPosition, pcm.sampleRate)?.wordId)
                        player.setPlaybackSpeed(2f); player.play()
                        delay(100)
                        assertTrue(player.currentPosition >= startMs)
                        player.pause()
                        val paused = player.currentPosition
                        delay(100); assertEquals(paused, player.currentPosition)
                    } finally { player.release() }
                }
                engine.unload()
            }
        } finally { engine.unload(); aligner.close() }
    }
}
