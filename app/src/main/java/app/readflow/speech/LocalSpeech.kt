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

    override suspend fun load(pack: String, voice: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(voices(pack).any { it.id == voice }) { "This voice is not in the verified model catalog" }
            if (active == pack && this@SherpaEngine.voice == voice && native != null) return@withLock
            native?.free(); native = null; active = ""
            val dir = packDirectory(pack)
            require(File(dir, ".verified").exists()) { "Download and verify this model first" }
            fun path(name: String) = File(dir, name).absolutePath
            val model = when (pack) {
                "kokoro" -> OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                    model = path("model.onnx"), voices = path("voices.bin"), tokens = path("tokens.txt"),
                    dataDir = path("espeak-ng-data"), lang = "en-us"), numThreads = 2, debug = false)
                "pocket" -> OfflineTtsModelConfig(pocket = OfflineTtsPocketModelConfig(
                    lmFlow = path("lm_flow.int8.onnx"), lmMain = path("lm_main.int8.onnx"), encoder = path("encoder.onnx"),
                    decoder = path("decoder.int8.onnx"), textConditioner = path("text_conditioner.onnx"),
                    vocabJson = path("vocab.json"), tokenScoresJson = path("token_scores.json")), numThreads = 2, debug = false)
                else -> error("Unknown model")
            }
            native = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
            active = pack; this@SherpaEngine.voice = voice; root = dir
        }
    }
    override suspend fun synthesize(text: String, isCurrent: () -> Boolean): PcmAudio = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(isCurrent()) { "Superseded speech request" }
            val engine = checkNotNull(native) { "No model loaded" }
            val config = if (active == "pocket") {
                val wave = WaveReader.readWave(File(root, "voices/$voice.wav").absolutePath)
                GenerationConfig(referenceAudio = wave.samples, referenceSampleRate = wave.sampleRate, numSteps = 5,
                    extra = mapOf("temperature" to "0.7", "chunk_size" to "15"))
            } else GenerationConfig(sid = voice.toInt(), speed = 1f)
            val generated = engine.generateWithConfigAndCallback(text, config) { if (isCurrent()) 1 else 0 }
            check(isCurrent()) { "Superseded speech request" }
            check(generated.samples.isNotEmpty()) { "The speech model returned no audio" }
            PcmAudio(generated.samples, generated.sampleRate)
        }
    }
    // The same lock guards inference and destruction, including uncancellable JNI work.
    override suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock { native?.free(); native = null; active = ""; root = null }
    }
}

class OnnxForcedAligner(private val modelFile: () -> File) : SpeechAligner {
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private val environment = OrtEnvironment.getEnvironment()
    override suspend fun align(audio: PcmAudio, text: SpeechText): List<WordTiming> = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(audio.samples.size.toDouble() / audio.sampleRate <= 40) { "Generated chunk exceeds the 40-second alignment limit" }
            if (session == null) {
                require(modelFile().exists()) { "Download the word-alignment pack first" }
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    session = environment.createSession(modelFile().absolutePath, options)
                }
            }
            val samples = resample(audio.samples, audio.sampleRate, 16000)
            val mean = samples.average()
            val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
            for (i in samples.indices) samples[i] = ((samples[i] - mean) / sqrt(variance + 1e-7)).toFloat()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
                checkNotNull(session).run(mapOf("input_values" to input)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (output[0].value as Array<Array<FloatArray>>)[0]
                    CtcAlignment().align(logits, text, audio.sampleRate, audio.samples.size)
                }
            }
        }
    }
    suspend fun close() = withContext(Dispatchers.Default) { mutex.withLock { session?.close(); session = null } }
}

/** Windowed sinc resampling keeps the aligner's 16 kHz input band limited. Timings stay in original PCM coordinates. */
fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to) return input.copyOf()
    val ratio = from.toDouble() / to
    val cutoff = min(1.0, to.toDouble() / from) * .95
    return FloatArray((input.size / ratio).roundToInt()) { out ->
        val center = out * ratio
        var sum = 0.0; var weight = 0.0
        for (i in floor(center).toInt() - 24..floor(center).toInt() + 24) {
            if (i !in input.indices) continue
            val x = (center - i) * cutoff
            val sinc = if (abs(x) < 1e-8) 1.0 else sin(PI * x) / (PI * x)
            val window = .5 + .5 * cos(PI * (center - i) / 25)
            val coefficient = sinc * window * cutoff
            sum += input[i] * coefficient; weight += coefficient
        }
        (sum / weight).toFloat()
    }
}
