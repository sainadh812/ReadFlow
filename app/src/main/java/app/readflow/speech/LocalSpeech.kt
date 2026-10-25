package app.readflow.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.readflow.core.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.*

class SherpaEngine(private val packDirectory: (String) -> File, private val voiceCatalog: (String) -> List<Voice>) : TtsEngine {
    override val timingCapability = "PCM only; separate known-transcript CTC alignment required"
    override val cancellationCapability = "Cooperative native callback; uncancellable inference sections must drain before unload"
    override fun voices(pack: String): List<Voice> = voiceCatalog(pack)
    private val mutex = Mutex()
    private var native: OfflineTts? = null
    private var active = ""
    private var voice = ""
    private var root: File? = null
    private var runtime = SpeechRuntimeConfig()

    /** Changing execution settings drains in-flight inference before replacing its session. */
    suspend fun configure(config: SpeechRuntimeConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (runtime != config) {
                releaseNativeLocked()
                runtime = config
            }
        }
    }

    override suspend fun load(pack: String, voice: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(voices(pack).any { it.id == voice }) { "This voice is not in the verified model catalog" }
            if (active == pack && this@SherpaEngine.voice == voice && native != null) return@withLock
            releaseNativeLocked()
            val dir = packDirectory(pack)
            require(File(dir, ".verified").exists()) { "Download and verify this model first" }
            val provider = runtime.sherpaProvider(File(dir.parentFile, ".readflow-runtime"))
            fun path(name: String) = File(dir, name).absolutePath
            val model = when (pack) {
                "kokoro" -> OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                    model = path("model.onnx"), voices = path("voices.bin"), tokens = path("tokens.txt"),
                    dataDir = path("espeak-ng-data"), lang = "en-us"), numThreads = runtime.threads, debug = false, provider = provider)
                "pocket" -> OfflineTtsModelConfig(pocket = OfflineTtsPocketModelConfig(
                    lmFlow = path("lm_flow.int8.onnx"), lmMain = path("lm_main.int8.onnx"), encoder = path("encoder.onnx"),
                    decoder = path("decoder.int8.onnx"), textConditioner = path("text_conditioner.onnx"),
                    vocabJson = path("vocab.json"), tokenScoresJson = path("token_scores.json")), numThreads = runtime.threads, debug = false, provider = provider)
                else -> error("Unknown model")
            }
            native = speechNativeCall { OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1)) }
            active = pack; this@SherpaEngine.voice = voice; root = dir
        }
    }
    override suspend fun synthesize(text: String, isCurrent: () -> Boolean): PcmAudio = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(isCurrent()) { "Superseded speech request" }
            val engine = checkNotNull(native) { "No model loaded" }
            val config = if (active == "pocket") {
                val wave = speechNativeCall { WaveReader.readWave(File(root, "voices/$voice.wav").absolutePath) }
                GenerationConfig(referenceAudio = wave.samples, referenceSampleRate = wave.sampleRate, numSteps = 5,
                    extra = mapOf("temperature" to "0.7", "chunk_size" to "15"))
            } else GenerationConfig(sid = voice.toInt(), speed = 1f)
            val generated = speechNativeCall {
                engine.generateWithConfigAndCallback(text, config, SherpaAudioCallback(isCurrent))
            }
            check(isCurrent()) { "Superseded speech request" }
            check(generated.samples.isNotEmpty()) { "The speech model returned no audio" }
            PcmAudio(generated.samples, generated.sampleRate)
        }
    }
    // The same lock guards inference and destruction, including uncancellable JNI work.
    override suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock { releaseNativeLocked() }
    }

    private fun releaseNativeLocked() {
        val previous = native
        // Clear ownership first: a failed JNI destructor must never be retried on
        // the same potentially freed handle by the next configuration or load.
        native = null; active = ""; voice = ""; root = null
        if (previous != null) speechNativeCall { previous.free() }
    }
}

class OnnxForcedAligner(private val modelFile: () -> File) : SpeechAligner {
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private val environment = OrtEnvironment.getEnvironment()
    private var runtime = SpeechRuntimeConfig()
    @Volatile var lastTimings = AlignmentStageTimings()
        private set

    suspend fun configure(config: SpeechRuntimeConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (runtime != config) { releaseSessionLocked(); runtime = config }
        }
    }

    override suspend fun align(audio: PcmAudio, text: SpeechText): List<WordTiming> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val started = System.nanoTime()
            var stage = started
            var load = 0L; var resampling = 0L; var normalization = 0L
            var inference = 0L; var outputCopy = 0L; var ctc = 0L
            fun elapsed() = (System.nanoTime() - stage).coerceAtLeast(0)
            try {
                require(audio.sampleRate > 0 && audio.samples.isNotEmpty()) { "Alignment requires non-empty PCM at a positive sample rate" }
                require(audio.samples.size.toDouble() / audio.sampleRate <= 40) { "Generated chunk exceeds the 40-second alignment limit" }
                if (session == null) {
                    require(modelFile().exists()) { "Download the word-alignment pack first" }
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(runtime.threads); options.setInterOpNumThreads(1)
                        options.addConfigEntry("session.intra_op.allow_spinning", if (runtime.allowSpinning) "1" else "0")
                        options.addConfigEntry("session.inter_op.allow_spinning", if (runtime.allowSpinning) "1" else "0")
                        session = environment.createSession(modelFile().absolutePath, options)
                    }
                }
                load = elapsed(); stage = System.nanoTime()
                val samples = resample(audio.samples, audio.sampleRate, 16000)
                require(samples.isNotEmpty()) { "Audio is too short for word alignment" }
                resampling = elapsed(); stage = System.nanoTime()
                val mean = samples.average()
                val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
                val deviation = sqrt(variance + 1e-7)
                for (i in samples.indices) samples[i] = ((samples[i] - mean) / deviation).toFloat()
                normalization = elapsed(); stage = System.nanoTime()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
                    checkNotNull(session).run(mapOf("input_values" to input)).use { output ->
                        inference = elapsed(); stage = System.nanoTime()
                        @Suppress("UNCHECKED_CAST")
                        val logits = (output[0].value as Array<Array<FloatArray>>)[0]
                        outputCopy = elapsed(); stage = System.nanoTime()
                        try { CtcAlignment().align(logits, text, audio.sampleRate, audio.samples.size) }
                        finally { ctc = elapsed() }
                    }
                }
            } finally {
                lastTimings = AlignmentStageTimings(load / 1_000_000, resampling / 1_000_000,
                    normalization / 1_000_000, inference / 1_000_000, outputCopy / 1_000_000,
                    ctc / 1_000_000, (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)
            }
        }
    }
    suspend fun close() = withContext(Dispatchers.Default) { mutex.withLock { releaseSessionLocked() } }

    private fun releaseSessionLocked() {
        val previous = session
        session = null
        if (previous != null) speechNativeCall { previous.close() }
    }
}
