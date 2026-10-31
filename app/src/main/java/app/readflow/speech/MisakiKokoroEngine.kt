package app.readflow.speech

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.readflow.core.PcmAudio
import app.readflow.core.TtsEngine
import app.readflow.core.Voice
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Experimental, direct ONNX inference using the existing verified Kokoro v0.19 pack.
 * The injected Misaki frontend owns its own lifecycle. This engine never invokes Sherpa or eSpeak.
 */
class MisakiKokoroEngine(
    private val packDirectory: (String) -> File,
    private val voiceCatalog: (String) -> List<Voice>,
    private val phonemize: suspend (String) -> String,
) : TtsEngine {
    companion object {
        const val MODEL_ID = "kokoro-misaki"
        const val ADAPTER_VERSION = "kokoro-v019-ipa-1"
    }

    override val timingCapability = "PCM only; separate known-transcript CTC alignment required"
    override val cancellationCapability = "Checked between phonemization and inference; in-flight ONNX calls drain before unload"
    override fun voices(pack: String): List<Voice> = if (pack == MODEL_ID || pack == "kokoro") voiceCatalog("kokoro") else emptyList()

    private val mutex = Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var runtime = SpeechRuntimeConfig()
    private var session: OrtSession? = null
    private var tokens: Map<Int, Long> = emptyMap()
    private var styles: KokoroV019Style? = null
    private var voice = ""
    @Volatile var lastTimings = MisakiSpeechTimings()
        private set

    suspend fun configure(config: SpeechRuntimeConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (runtime != config) { releaseLocked(); runtime = config }
        }
    }

    override suspend fun load(pack: String, voice: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(pack == MODEL_ID || pack == "kokoro") { "Misaki requires the Kokoro English model" }
            require(voices(pack).any { it.id == voice }) { "This voice is not in the verified model catalog" }
            if (session != null && this@MisakiKokoroEngine.voice == voice) return@withLock
            val directory = packDirectory("kokoro")
            require(File(directory, ".verified").isFile) { "Download and verify the Kokoro model first" }
            val speaker = requireNotNull(voice.toIntOrNull()) { "Invalid Kokoro voice" }
            val newStyle = KokoroV019Style.read(File(directory, "voices.bin"), speaker)
            if (session != null) {
                styles = newStyle
                this@MisakiKokoroEngine.voice = voice
                return@withLock
            }
            val newTokens = KokoroV019Codec.readTokens(File(directory, "tokens.txt").readText())
            val created = speechNativeCall { OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(runtime.threads)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", if (runtime.allowSpinning) "1" else "0")
                options.addConfigEntry("session.inter_op.allow_spinning", if (runtime.allowSpinning) "1" else "0")
                environment.createSession(File(directory, "model.onnx").absolutePath, options)
            } }
            try {
                val metadata = created.metadata.customMetadata
                require(metadata["model_type"] == "kokoro" && metadata["version"] == "1" &&
                    metadata["sample_rate"] == "24000" && metadata["style_dim"] == "511,1,256" &&
                    metadata["n_speakers"] == "11") { "This experimental adapter requires the verified Kokoro v0.19 pack" }
                require(created.inputNames == setOf("tokens", "style", "speed") && created.outputNames == setOf("audio")) {
                    "Unsupported Kokoro model inputs or outputs"
                }
                val tokenInfo = created.inputInfo.getValue("tokens").info as? TensorInfo
                val styleInfo = created.inputInfo.getValue("style").info as? TensorInfo
                val speedInfo = created.inputInfo.getValue("speed").info as? TensorInfo
                require(tokenInfo?.type == OnnxJavaType.INT64 && tokenInfo.shape.size == 2 && tokenInfo.shape[0] == 1L &&
                    styleInfo?.type == OnnxJavaType.FLOAT && styleInfo.shape.contentEquals(longArrayOf(1, 256)) &&
                    speedInfo?.type == OnnxJavaType.FLOAT && speedInfo.shape.contentEquals(longArrayOf(1))) {
                    "Unsupported Kokoro tensor dimensions"
                }
                session = created
                tokens = newTokens
                styles = newStyle
                this@MisakiKokoroEngine.voice = voice
            } catch (failure: Throwable) {
                try { created.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                if (failure is LinkageError) throw IllegalStateException("Misaki Kokoro runtime could not start. Copy playback diagnostics from Settings.", failure)
                throw failure
            }
        }
    }

    override suspend fun synthesize(text: String, isCurrent: () -> Boolean): PcmAudio = withContext(Dispatchers.Default) {
        mutex.withLock {
            val started = System.nanoTime()
            var phonemizerNs = 0L
            var inferenceNs = 0L
            var outputNs = 0L
            suspend fun checkCurrent() {
                currentCoroutineContext().ensureActive()
                check(isCurrent()) { "Superseded speech request" }
            }
            try {
                checkCurrent()
                val loaded = checkNotNull(session) { "No Misaki Kokoro model loaded" }
                require(text.isNotBlank()) { "No spoken text to synthesize" }
                val ids = KokoroV019Codec.encode(phonemize(text), tokens)
                val style = checkNotNull(styles).forTokenCount(ids.size - 2)
                phonemizerNs = System.nanoTime() - started
                checkCurrent()
                speechNativeCall { OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())) }.use { tokenInput ->
                    speechNativeCall { OnnxTensor.createTensor(environment, FloatBuffer.wrap(style), longArrayOf(1, 256)) }.use { styleInput ->
                        // Reading speed is applied by the player, as in the existing local engines.
                        speechNativeCall { OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)) }.use { speedInput ->
                            val inferenceStart = System.nanoTime()
                            speechNativeCall { loaded.run(mapOf("tokens" to tokenInput, "style" to styleInput, "speed" to speedInput)) }.use { output ->
                                inferenceNs = System.nanoTime() - inferenceStart
                                checkCurrent()
                                val outputStart = System.nanoTime()
                                val tensor = output[0] as? OnnxTensor ?: error("Kokoro returned an unsupported audio output")
                                require(tensor.info.type == OnnxJavaType.FLOAT && tensor.info.shape.size == 1) {
                                    "Kokoro returned an unsupported audio format"
                                }
                                val count = tensor.info.numElements
                                require(count in 1..(24_000L * 40)) { "Generated speech is empty or exceeds the 40-second alignment limit" }
                                val samples = FloatArray(count.toInt())
                                tensor.floatBuffer.get(samples)
                                require(samples.all(Float::isFinite)) { "Kokoro returned invalid audio samples" }
                                val paused = KokoroV019Pauses.shorten(samples)
                                outputNs = System.nanoTime() - outputStart
                                PcmAudio(paused, 24_000)
                            }
                        }
                    }
                }
            } catch (failure: LinkageError) {
                throw IllegalStateException("Misaki Kokoro runtime failed. Copy playback diagnostics from Settings.", failure)
            } finally {
                lastTimings = MisakiSpeechTimings(phonemizerNs / 1_000_000, inferenceNs / 1_000_000,
                    outputNs / 1_000_000, (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)
            }
        }
    }

    override suspend fun unload() = withContext(Dispatchers.Default) { mutex.withLock { releaseLocked() } }

    private fun releaseLocked() {
        val previous = session
        session = null
        tokens = emptyMap()
        styles = null
        voice = ""
        if (previous != null) speechNativeCall { previous.close() }
    }
}

/** Match the current Sherpa Kokoro engine's 0.2 silence scale for a useful frontend comparison. */
internal object KokoroV019Pauses {
    // Same policy as sherpa-onnx 1.13.8 GeneratedAudio::ScaleSilence (Apache-2.0):
    // Copyright (c) 2023 Xiaomi Corporation; adapted for Kotlin's FloatArray.
    // low-amplitude runs >=200 ms are kept at 20%; a trailing run must exceed 200 ms.
    fun shorten(samples: FloatArray): FloatArray {
        val intervals = mutableListOf<IntRange>()
        var start = -1
        for (i in samples.indices) {
            if (kotlin.math.abs(samples[i]) <= .01f) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= 4_800) intervals += start until i
                start = -1
            }
        }
        if (start >= 0 && samples.size - start > 4_800) intervals += start until samples.size
        if (intervals.isEmpty()) return samples
        val remaining = samples.size - intervals.sumOf { range ->
            val size = range.last - range.first + 1
            size - (size * .2f).toInt()
        }
        val result = FloatArray(remaining)
        var source = 0
        var target = 0
        for (range in intervals) {
            val keepUntil = range.first + ((range.last - range.first + 1) * .2f).toInt()
            samples.copyInto(result, target, source, keepUntil)
            target += keepUntil - source
            source = range.last + 1
        }
        samples.copyInto(result, target, source)
        return result
    }
}

data class MisakiSpeechTimings(
    val phonemizerMs: Long = 0,
    val inferenceMs: Long = 0,
    val outputCopyMs: Long = 0,
    val totalMs: Long = 0,
)

/** Misaki's compressed alphabet must be expanded for the older v0.19 acoustic model. */
internal object KokoroV019Codec {
    // Representation mapping documented in hexgrad/misaki/EN_PHONES.md (to_espeak).
    // This is a string conversion; it does not load or call the eSpeak library.
    fun legacyPhones(phones: String): String = buildString {
        for (phone in phones) append(when (phone) {
            'A' -> "eɪ"; 'I' -> "aɪ"; 'O' -> "oʊ"; 'Q' -> "əʊ"; 'W' -> "aʊ"; 'Y' -> "ɔɪ"
            'ʤ' -> "dʒ"; 'ʧ' -> "tʃ"; 'ᵊ' -> "ə"; 'T' -> "ɾ"
            '(', '[' -> "«"; ')', ']' -> "»"; '‘', '’' -> "'"; '-', '–' -> "—"
            else -> if (phone.isWhitespace()) " " else phone.toString()
        })
    }.replace(Regex(" +"), " ").trim()

    fun readTokens(text: String): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        val ids = mutableSetOf<Long>()
        for (line in text.lineSequence().filter(String::isNotBlank)) {
            val match = requireNotNull(Regex("^(.) +([0-9]+)$").matchEntire(line.trimEnd('\r'))) { "Invalid Kokoro token file" }
            val symbol = match.groupValues[1].codePointAt(0)
            val id = match.groupValues[2].toLong()
            require(result.put(symbol, id) == null && ids.add(id)) { "Duplicate Kokoro token" }
        }
        require(result['$'.code] == 0L && result[' '.code] == 16L) { "Invalid Kokoro boundary or space token" }
        return result
    }

    fun encode(phones: String, tokens: Map<Int, Long>): LongArray {
        val expanded = legacyPhones(phones)
        require(expanded.any(Char::isLetter)) { "Misaki returned no spoken phonemes" }
        val symbols = expanded.codePoints().toArray()
        require(symbols.size <= 510) { "This pronunciation exceeds Kokoro's 510-token limit; it was not truncated" }
        return LongArray(symbols.size + 2).also { ids ->
            for ((index, symbol) in symbols.withIndex()) {
                val token = tokens[symbol]
                require(token != null && token != 0L) { "Misaki returned an unsupported phoneme (U+${symbol.toString(16).uppercase()}); speech was not truncated" }
                ids[index + 1] = token
            }
        }
    }
}

/** Load only the selected voice, preserving the export's length-indexed, little-endian layout. */
internal class KokoroV019Style(private val values: FloatArray) {
    fun forTokenCount(count: Int): FloatArray {
        require(count in 1..510) { "Invalid Kokoro phoneme count" }
        return values.copyOfRange(count * 256, (count + 1) * 256)
    }

    companion object {
        private const val VOICE_BYTES = 511 * 256 * 4
        fun read(file: File, speaker: Int): KokoroV019Style {
            require(speaker in 0..10) { "Invalid Kokoro voice index" }
            require(file.length() == 11L * VOICE_BYTES) { "Corrupt Kokoro voice styles; verify the model pack again" }
            val bytes = ByteArray(VOICE_BYTES)
            RandomAccessFile(file, "r").use { input -> input.seek(speaker.toLong() * VOICE_BYTES); input.readFully(bytes) }
            val values = FloatArray(511 * 256)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
            require(values.all(Float::isFinite)) { "Kokoro voice styles contain invalid values" }
            return KokoroV019Style(values)
        }
    }
}
