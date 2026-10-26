package app.readflow.models

import android.content.Context
import app.readflow.core.sha256
import app.readflow.core.Voice
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable data class PackFile(val path: String, val bytes: Long, val sha256: String, val url: String = "")
@Serializable data class VoicePack(val id: String, val name: String, val version: String, val runtime: String, val archiveRoot: String,
    val url: String, val sha256: String, val downloadBytes: Long, val installedBytes: Long, val files: List<PackFile>,
    val extras: List<PackFile>, val licenses: List<String>, val voices: List<Voice>, val alignment: String, val support: String)
data class PackProgress(val phase: String = "Not installed", val received: Long = 0, val total: Long = 0,
    val busy: Boolean = false, val canResume: Boolean = false)

fun fileHash(file: File, progress: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val bytes = ByteArray(PACK_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val n = input.read(bytes)
            if (n < 0) break
            digest.update(bytes, 0, n)
            count += n
            progress(count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun safeChild(root: File, path: String): File {
    require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path)
    return File(root, path).canonicalFile.also { require(it.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe asset path" } }
}
fun verifyAsset(root: File, item: PackFile, progress: (Long) -> Unit = {}) {
    val file = safeChild(root, item.path)
    check(file.isFile && file.length() == item.bytes && fileHash(file, progress) == item.sha256) { "Invalid model asset: ${item.path}" }
}
interface PackRepository {
    fun directory(id: String): File
    fun installed(id: String): Boolean
    suspend fun install(id: String)
    suspend fun delete(id: String)
}
class ModelStore internal constructor(
    val packs: List<VoicePack>,
    private val root: File,
    private val downloadRoot: File,
) : PackRepository {
    constructor(context: Context, manifestAsset: String = "model-manifests.json", storageSuffix: String = "") : this(
        Json.decodeFromString(context.assets.open(manifestAsset).bufferedReader().use { it.readText() }),
        File(context.noBackupFilesDir, "models$storageSuffix"), File(context.noBackupFilesDir, "downloads$storageSuffix"),
    )
    private val mutex = Mutex()
    private val verifiedThisProcess = mutableSetOf<String>()
    private val manifestHashes = packs.associate { it.id to sha256(Json.encodeToString(VoicePack.serializer(), it).toByteArray()) }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).followSslRedirects(false).build()
    private val mutableProgress = MutableStateFlow<Map<String, PackProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()
    init {
        check(root.isDirectory || root.mkdirs()) { "Cannot create model directory" }
        check(downloadRoot.isDirectory || downloadRoot.mkdirs()) { "Cannot create download directory" }
        packs.forEach { recoverInterruptedReplacement(it.id) }
        mutableProgress.value = packs.filter { !installed(it.id) && File(downloadRoot, "${it.id}.part").length() > 0 }
            .associate { it.id to PackProgress("Setup interrupted; ready to resume", canResume = true) }
    }
    override fun directory(id: String) = File(root, packs.first { it.id == id }.id)
    override fun installed(id: String): Boolean = File(directory(id), ".verified").readTextOrNull() == manifestHash(id)
    /** Local setup candidate only. Call verifyInstalled before opening any model. */
    fun hasLocalInstallation(id: String): Boolean = File(directory(id), ".verified").isFile
    private fun manifestHash(id: String) = manifestHashes.getValue(id)
    private fun update(id: String, phase: String, bytes: Long = 0, total: Long = 0, busy: Boolean = true) {
        mutableProgress.update { it + (id to PackProgress(phase, bytes, total, busy,
            canResume = !busy && !installed(id) && File(downloadRoot, "$id.part").length() > 0)) }
    }
    private fun reporter(id: String, phase: String, total: Long): (Long) -> Unit {
        update(id, phase, total = total)
        var lastUpdate = System.nanoTime()
        return { count ->
            val now = System.nanoTime()
            if (count >= total || now - lastUpdate >= 200_000_000L) {
                update(id, phase, count, total)
                lastUpdate = now
            }
        }
    }
    suspend fun verifyInstalled(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = packs.first { it.id == id }
            if (!installed(id)) {
                check(hasLocalInstallation(id)) { "Download ${pack.name} in Voice models first." }
                verifiedThisProcess -= id
                try {
                    // An app update may change catalog metadata or remove unused
                    // files without changing the model bytes. Playback must repair
                    // that setup locally instead of demanding another Download tap.
                    // Never call install here: it takes this mutex and can download.
                    check(reuseCompatibleInstallation(id, pack)) {
                        "Existing ${pack.name} files are incomplete or damaged. Open Voice models and update setup."
                    }
                } catch (cancel: CancellationException) {
                    update(id, "Setup check cancelled; retry to continue", busy = false)
                    throw cancel
                } catch (error: Exception) {
                    update(id, error.message ?: "Model setup check failed", busy = false)
                    throw error
                }
            }
            if (id !in verifiedThisProcess) {
                verify(directory(id), pack) {}
                verifiedThisProcess += id
            }
        }
    }
    override suspend fun install(id: String) = withContext(Dispatchers.IO) {
        val pack = packs.first { it.id == id }
        val staging = File(root, ".$id.installing")
        var ownsStaging = false
        update(id, "Waiting for other model setup")
        try {
            mutex.lock()
            ownsStaging = true
            if (installed(id)) {
                try {
                    verify(directory(id), pack, reporter(id, "Checking installed files", pack.installedBytes))
                    verifiedThisProcess += id
                    update(id, "Installed", busy = false)
                    return@withContext
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: IllegalStateException) { File(directory(id), ".verified").delete(); verifiedThisProcess -= id }
            }
            if (reuseCompatibleInstallation(id, pack)) return@withContext
            val pending = (pack.downloadBytes - File(downloadRoot, "$id.part").length()).coerceAtLeast(0) +
                pack.extras.sumOf { (it.bytes - extraDownload(id, it).length()).coerceAtLeast(0) }
            check(root.usableSpace > pending + pack.installedBytes + 64L * 1024 * 1024) { "Not enough free storage for download and installation" }
            val archive = download(id, pack.url, File(downloadRoot, "$id.part"), pack.sha256, pack.downloadBytes, "Downloading model")
            check(staging.mkdirs()) { "Cannot create installation directory" }
            if (pack.archiveRoot.isBlank()) {
                val target = pack.files.single()
                check(target.bytes == pack.downloadBytes && target.sha256 == pack.sha256) { "Invalid single-file model manifest" }
                copyPackAsset(archive, safeChild(staging, target.path), reporter(id, "Installing model", pack.downloadBytes))
            } else {
                extractPack(archive.inputStream(), staging, pack, reporter(id, "Unpacking model", pack.downloadBytes))
            }
            for (extra in pack.extras) {
                val asset = download(id, extra.url, extraDownload(id, extra), extra.sha256, extra.bytes,
                    if (extra.path.startsWith("voices/")) "Downloading voice" else "Downloading supporting assets")
                val target = safeChild(staging, extra.path)
                copyPackAsset(asset, target, reporter(id, "Installing ${extra.path.substringAfterLast('/')}", extra.bytes))
            }
            finishInstallation(id, pack, staging)
        } catch (cancel: CancellationException) {
            if (ownsStaging) staging.deleteRecursively()
            update(id, "Setup cancelled; ready to resume", busy = false)
            throw cancel
        } catch (error: Exception) {
            if (ownsStaging) staging.deleteRecursively()
            update(id, error.message ?: "Model setup failed", busy = false)
            throw error
        } finally {
            if (ownsStaging) mutex.unlock()
        }
    }
    /** Caller owns mutex. Reuse is offline, hash-checked and atomically promoted. */
    private suspend fun reuseCompatibleInstallation(id: String, pack: VoicePack): Boolean {
        val staging = File(root, ".$id.installing")
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot clear interrupted installation" }
        try {
            if (!stageCompatibleInstallation(id, pack, staging)) return false
            finishInstallation(id, pack, staging)
            return true
        } finally {
            // Cancellation or an incomplete local pack must leave the previous
            // installation available for retry, and no half-written replacement.
            staging.deleteRecursively()
        }
    }
    private suspend fun stageCompatibleInstallation(id: String, pack: VoicePack, staging: File): Boolean {
        val existing = directory(id)
        if (!File(existing, ".verified").isFile) return false
        try {
            verify(existing, pack, reporter(id, "Checking reusable model files", pack.installedBytes))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IllegalStateException) {
            return false
        }
        check(root.usableSpace > pack.installedBytes + 64L * 1024 * 1024) { "Not enough free storage to update model installation" }
        check(staging.mkdirs()) { "Cannot create installation directory" }
        for (item in pack.files + pack.extras) {
            copyPackAsset(safeChild(existing, item.path), safeChild(staging, item.path),
                reporter(id, "Reusing ${item.path.substringAfterLast('/')}", item.bytes))
        }
        return true
    }
    private suspend fun finishInstallation(id: String, pack: VoicePack, staging: File) {
        verify(staging, pack, reporter(id, "Checking installed files", pack.installedBytes))
        currentCoroutineContext().ensureActive()
        update(id, "Finishing installation")
        File(staging, ".verified").writeText(manifestHash(id))
        val installed = directory(id)
        val previous = previousInstallation(id)
        check(!previous.exists() || previous.deleteRecursively()) { "Cannot clear previous model installation" }
        // Rename within the same directory/filesystem. Retain the old verified
        // installation until promotion succeeds; startup recovers an interrupted
        // swap before any model is offered to inference.
        val replacing = installed.exists()
        if (replacing) check(installed.renameTo(previous)) { "Cannot preserve previous model installation" }
        if (!staging.renameTo(installed)) {
            check(!replacing || previous.renameTo(installed)) { "Model installation failed; previous installation will be recovered on restart" }
            error("Atomic model installation failed; previous model kept")
        }
        check(!previous.exists() || previous.deleteRecursively()) { "Model installed; previous files could not be removed" }
        verifiedThisProcess += id
        File(downloadRoot, "$id.part").delete()
        pack.extras.forEach { extraDownload(id, it).delete() }
        update(id, "Installed", busy = false)
    }
    private fun previousInstallation(id: String) = File(root, ".$id.previous")
    private fun recoverInterruptedReplacement(id: String) {
        val previous = previousInstallation(id)
        if (!previous.exists()) return
        val installed = directory(id)
        if (!installed.exists()) {
            check(previous.renameTo(installed)) { "Could not recover previous model installation" }
        } else {
            // A fully verified staging directory was already promoted. Failure
            // to remove old files must not make a valid model unusable at startup.
            previous.deleteRecursively()
        }
    }
    private fun extraDownload(id: String, extra: PackFile) = File(downloadRoot, "$id-${extra.path.substringAfterLast('/')}.part")
    private suspend fun download(id: String, url: String, file: File, hash: String, expected: Long, phase: String): File {
        require(url.startsWith("https://"))
        val context = currentCoroutineContext()
        fun valid(): Boolean {
            if (file.length() != expected) return false
            val progress = reporter(id, "Checking download", expected)
            return fileHash(file) { count -> context.ensureActive(); progress(count) } == hash
        }
        repeat(3) { attempt ->
            context.ensureActive()
            if (valid()) return file
            if (file.length() >= expected) file.delete()
            val offset = file.length()
            val progress = reporter(id, if (attempt == 0) phase else "$phase (retry ${attempt + 1}/3)", expected)
            progress(offset)
            val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            try {
                withDownloadCall(request) { response ->
                    check(response.request.url.isHttps) { "Insecure download redirect rejected" }
                    check(response.isSuccessful) { "Download HTTP ${response.code}" }
                    val resume = offset > 0 && response.code == 206
                    if (resume) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid resume response" }
                    response.body!!.byteStream().use { input -> FileOutputStream(file, resume).use { output ->
                        val buffer = ByteArray(64 * 1024); var count = if (resume) offset else 0L
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            count += n; check(count <= expected) { "Oversized download rejected" }
                            output.write(buffer, 0, n); progress(count)
                        }
                        output.fd.sync()
                    } }
                }
                check(valid()) { "Asset checksum or size mismatch" }
                return file
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (attempt == 2) throw error; delay((attempt + 1) * 1000L) }
        }
        error("Download failed")
    }
    private suspend fun <T> withDownloadCall(request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use(read) } finally { cancellation.cancel() }
    }
    private suspend fun verify(directory: File, pack: VoicePack, progress: (Long) -> Unit) {
        val context = currentCoroutineContext()
        var verified = 0L
        (pack.files + pack.extras).forEach { item ->
            context.ensureActive()
            verifyAsset(directory, item) { count -> context.ensureActive(); progress(verified + count) }
            verified += item.bytes
        }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory(id).deleteRecursively(); File(root, ".$id.installing").deleteRecursively(); previousInstallation(id).deleteRecursively()
            downloadRoot.listFiles()?.filter { it.name == "$id.part" || it.name.startsWith("$id-") }?.forEach { it.delete() }
            verifiedThisProcess -= id; update(id, "Not installed", busy = false)
        }
        Unit
    }
}
private fun File.readTextOrNull() = if (isFile) readText() else null
