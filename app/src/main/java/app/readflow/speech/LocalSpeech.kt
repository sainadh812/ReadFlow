package app.readflow.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.readflow.core.*
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.*

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
