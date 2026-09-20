package app.readflow.playback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.readflow.BuildConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PlaybackStage { VERIFYING_MODELS, LOADING_MODEL, SYNTHESIZING, ALIGNING, STARTING_AUDIO, AUDIO_READY, FAILED }

@Serializable
data class PlaybackCheckpoint(
    val version: String,
    val model: String,
    val stage: PlaybackStage,
    val timestampMs: Long,
    val failureTypes: List<String> = emptyList(),
)

/** A single local checkpoint, never document text, URLs, paths, or exception messages. */
class PlaybackDiagnostics(private val directory: File) {
    private val mutex = Mutex()
    private val file = File(directory, "playback.json")

    suspend fun record(model: String, stage: PlaybackStage, error: Throwable? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val checkpoint = PlaybackCheckpoint(
                BuildConfig.VERSION_NAME, model.takeIf { it in setOf("kokoro", "pocket") } ?: "unknown",
                stage, System.currentTimeMillis(),
                generateSequence(error) { it.cause }.take(4).map { it.javaClass.name }.toList(),
            )
            try {
                if (!directory.isDirectory && !directory.mkdirs()) return@withLock
                val partial = File(directory, "playback.part")
                partial.outputStream().use { output ->
                    output.write(Json.encodeToString(PlaybackCheckpoint.serializer(), checkpoint).toByteArray())
                    output.fd.sync()
                }
                if (!partial.renameTo(file)) partial.delete()
            } catch (_: IOException) {
                // A full disk must not turn optional diagnostics into a playback failure.
            }
        }
    }

    suspend fun checkpoint(): PlaybackCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { if (file.isFile) Json.decodeFromString<PlaybackCheckpoint>(file.readText()) else null }
            catch (_: Exception) { null }
        }
    }

    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        val checkpoint = checkpoint()
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .joinToString("\n") { exit ->
                    "${Instant.ofEpochMilli(exit.timestamp)}: ${exitReason(exit.reason)}, status=${exit.status}, pss=${exit.pss} KiB, rss=${exit.rss} KiB"
                }.ifBlank { "No process exits recorded by Android" }
        } catch (_: Exception) { "Android process-exit history unavailable" }
        buildString {
            appendLine("ReadFlow playback diagnostics")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Java heap limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB")
            appendLine("Runtime: Sherpa 1.13.8; alignment ORT 1.20.0; CPU, two threads")
            appendLine("Last playback step: ${checkpoint?.let { Json.encodeToString(PlaybackCheckpoint.serializer(), it) } ?: "No playback attempt recorded"}")
            appendLine("Recent process exits:\n$exits")
            append("No document text, titles, URLs, or audio included. Nothing uploaded.")
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_ANR -> "Not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User stopped app"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App exited"
        else -> "Android reason $reason"
    }
}
