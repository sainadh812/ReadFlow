package app.readflow.benchmark

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One bounded report in private app storage. No model inputs from the library are accepted. */
class BenchmarkStore(private val directory: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = File(directory, "latest.json")

    @Synchronized
    fun load(): BenchmarkState? = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val state = json.decodeFromString<BenchmarkState>(file.readText())
        if (state.rows.size > MAX_ROWS) return null
        if (state.running) state.copy(status = BenchmarkStatus.CANCELLED,
            phase = "Previous test interrupted", error = "The app stopped before this test finished.") else state
    }.getOrNull()

    @Synchronized
    fun save(state: BenchmarkState) {
        require(state.rows.size <= MAX_ROWS)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot save benchmark report" }
        val bytes = report(state).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(directory, "latest.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(file)) { "Cannot finish saving benchmark report" }
    }

    fun report(state: BenchmarkState): String = json.encodeToString(state)

    companion object { const val MAX_ROWS = 512; const val MAX_BYTES = 2 * 1024 * 1024 }
}
