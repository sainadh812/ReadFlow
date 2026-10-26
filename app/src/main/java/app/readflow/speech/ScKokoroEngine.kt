package app.readflow.speech

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.readflow.core.PcmAudio
import app.readflow.core.TtsEngine
import app.readflow.core.Voice
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class ScKokoroAvailability(val installed: Boolean, val version: String?, val message: String)

/** The system TTS API does not expose the execution device used by another app. */
data class ScKokoroExecution(
    val packageName: String = ScKokoroEngine.PACKAGE_NAME,
    val version: String?,
    val voice: String,
    val backend: String = "external-unverified",
    val description: String = "SC Kokoro external engine · hardware backend unverified",
)

/**
 * Optional inter-app prototype. No SC Kokoro code, drivers or weights are bundled.
 * The external app owns its OpenCL runtime; ReadFlow requests a WAV, then uses its
 * normal alignment and playback pipeline. It never calls speak() or changes the
 * system's default engine. Failure is explicit: this class never substitutes CPU.
 */
class ScKokoroEngine(context: Context) : TtsEngine {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null
    private var selectedVoice: String? = null
    @Volatile var lastExecution: ScKokoroExecution? = null
        private set

    override val timingCapability = "PCM only; separate known-transcript CTC alignment required"
    override val cancellationCapability = "Android TTS stop and bounded callback drain; external process owns native cleanup"

    override fun voices(pack: String): List<Voice> = if (pack == MODEL_ID) listOf(
        Voice(DEFAULT_VOICE, "SC Kokoro · Heart"),
        Voice("af_bella", "SC Kokoro · Bella"),
        Voice("af_nicole", "SC Kokoro · Nicole"),
    ) else emptyList()

    override suspend fun load(pack: String, voice: String) = mutex.withLock {
        require(pack == MODEL_ID && voices(pack).any { it.id == voice }) { "Choose a supported SC Kokoro voice" }
        if (engine != null && selectedVoice == voice) return@withLock
        closeEngine()
        val available = availability(context)
        check(available.installed) { available.message }
        val ready = CompletableDeferred<Int>()
        // Publish the reference inside the dispatched block: cancellation on the
        // return to the caller must not strand an initialized Android service.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            engine = TextToSpeech(context, { status -> ready.complete(status) }, PACKAGE_NAME)
        }
        val candidate = checkNotNull(engine)
        try {
            check(withTimeout(30_000) { ready.await() } == TextToSpeech.SUCCESS) { "SC Kokoro could not start. Open it and run its device test." }
            // Android may fall back to a system engine if binding fails. Reject a
            // voice catalog that does not expose the exact offline Kokoro voice.
            val installedVoice = withContext(Dispatchers.IO) {
                candidate.voices?.firstOrNull { it.name == voice && it.locale.language == "en" && !it.isNetworkConnectionRequired }
            }
            check(installedVoice != null) { "SC Kokoro voice is unavailable. Open SC Kokoro, finish its download and run its device test." }
            check(candidate.setVoice(installedVoice) == TextToSpeech.SUCCESS && candidate.setSpeechRate(1f) == TextToSpeech.SUCCESS && candidate.setPitch(1f) == TextToSpeech.SUCCESS) {
                "SC Kokoro could not select the requested voice"
            }
            selectedVoice = voice
            lastExecution = ScKokoroExecution(version = available.version, voice = voice)
        } catch (error: Throwable) {
            withContext(NonCancellable) { closeEngine() }
            throw error
        }
    }

    override suspend fun synthesize(text: String, isCurrent: () -> Boolean): PcmAudio = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Superseded external speech request")
        require(text.isNotBlank() && text.length < TextToSpeech.getMaxSpeechInputLength()) { "Sentence exceeds the external engine's input limit" }
        val candidate = checkNotNull(engine) { "SC Kokoro is not loaded" }
        val directory = File(context.cacheDir, "sc-kokoro").also { check(it.isDirectory || it.mkdirs()) }
        val wave = File.createTempFile("speech-", ".wav", directory)
        val id = UUID.randomUUID().toString()
        val completed = CompletableDeferred<Unit>()
        candidate.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) completed.complete(Unit) }
            @Deprecated("Required Android callback")
            override fun onError(utteranceId: String?) { fail(utteranceId, "SC Kokoro failed to synthesize this sentence") }
            override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId, "SC Kokoro synthesis failed (code $errorCode)") }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { fail(utteranceId, "SC Kokoro stopped synthesis") }
            private fun fail(utteranceId: String?, message: String) {
                if (utteranceId == id) completed.completeExceptionally(IOException(message))
            }
        })
        try {
            val submitted = withContext(Dispatchers.Main.immediate) {
                candidate.synthesizeToFile(text, Bundle(), wave, id)
            }
            check(submitted == TextToSpeech.SUCCESS) { "SC Kokoro rejected the speech request" }
            withTimeout(120_000) {
                while (withTimeoutOrNull(200) { completed.await(); true } != true) {
                    if (!isCurrent()) throw CancellationException("Superseded external speech request")
                }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Superseded external speech request")
            withContext(Dispatchers.IO) { readTtsWave(wave) }
        } catch (error: Throwable) {
            // Keep this instance serialized until stop is acknowledged (or a
            // bounded deadline expires), then detach before another request.
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) { runCatching { candidate.stop() } }
                try { withTimeoutOrNull(5_000) { completed.await() } } catch (_: IOException) { }
                closeEngine()
            }
            throw error
        } finally {
            candidate.setOnUtteranceProgressListener(null)
            wave.delete()
        }
    }

    override suspend fun unload() = mutex.withLock { closeEngine() }

    private suspend fun closeEngine() {
        val old = engine
        engine = null
        selectedVoice = null
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            runCatching { old?.stop() }
            runCatching { old?.shutdown() }
        }
    }

    companion object {
        const val MODEL_ID = "sc-kokoro"
        const val DEFAULT_VOICE = "af_heart"
        const val PACKAGE_NAME = "com.labsii.voices"
        const val INSTALL_URL = "https://play.google.com/store/apps/details?id=com.labsii.voices"
        const val SOURCE_URL = "https://github.com/speech-central/sc-voices-android"

        fun availability(context: Context): ScKokoroAvailability = try {
            val info = context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            val enabled = info.applicationInfo?.enabled == true
            ScKokoroAvailability(enabled, info.versionName, if (enabled)
                "SC Kokoro installed · open it to finish model setup and run its device test. Hardware backend is not exposed to Read Assist."
            else "SC Kokoro is disabled in Android settings.")
        } catch (_: PackageManager.NameNotFoundException) {
            ScKokoroAvailability(false, null, "Install SC Kokoro separately, download its voices and run its device test first.")
        } catch (_: SecurityException) {
            ScKokoroAvailability(false, null, "Android could not inspect SC Kokoro installation.")
        }
    }
}
