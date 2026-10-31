package app.readflow.speech

import java.io.File

/** Execution settings affect compute, not the text, voice or generated sample rate. */
data class SpeechRuntimeConfig(val threads: Int = 2, val allowSpinning: Boolean = false) {
    init { require(threads in 1..8) { "Speech threads must be between 1 and 8" } }

    /** sherpa-onnx 1.13.8 reads these through provider="cpu:<config path>". */
    internal fun sherpaSessionConfig(): String = buildString {
        append("SessionConfig.session.intra_op.allow_spinning=")
        append(if (allowSpinning) "1\n" else "0\n")
        append("SessionConfig.session.inter_op.allow_spinning=")
        append(if (allowSpinning) "1\n" else "0\n")
    }

    internal fun sherpaProvider(directory: File): String {
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the speech runtime settings" }
        val config = File(directory, "cpu-spin-${if (allowSpinning) 1 else 0}.config")
        val contents = sherpaSessionConfig()
        if (!config.isFile || config.readText() != contents) {
            // Parallel engines can share the same immutable settings without a partial read.
            val temporary = File.createTempFile("cpu-session-", ".tmp", directory)
            try {
                temporary.writeText(contents)
                check(temporary.renameTo(config) || (config.isFile && config.readText() == contents)) {
                    "Could not save the speech runtime settings"
                }
            } finally { temporary.delete() }
        }
        return "cpu:${config.absolutePath}"
    }
}

/** Monotonic elapsed wall time for the most recent alignment attempt, including a rejected one. */
data class AlignmentStageTimings(
    val sessionLoadMs: Long = 0,
    val resampleMs: Long = 0,
    val normalizationMs: Long = 0,
    val inferenceMs: Long = 0,
    val outputCopyMs: Long = 0,
    val ctcMs: Long = 0,
    val totalMs: Long = 0,
)
