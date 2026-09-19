package app.readflow.models

import android.content.Context
import app.readflow.core.sha256
import app.readflow.core.Voice
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

@Serializable data class PackFile(val path: String, val bytes: Long, val sha256: String, val url: String = "")
@Serializable data class VoicePack(val id: String, val name: String, val version: String, val runtime: String, val archiveRoot: String,
    val url: String, val sha256: String, val downloadBytes: Long, val installedBytes: Long, val files: List<PackFile>,
    val extras: List<PackFile>, val licenses: List<String>, val voices: List<Voice>, val alignment: String, val support: String)
data class PackProgress(val phase: String = "Not installed", val received: Long = 0, val total: Long = 0, val busy: Boolean = false)

fun fileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun safeChild(root: File, path: String): File {
    require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path)
    return File(root, path).canonicalFile.also { require(it.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe asset path" } }
}
fun verifyAsset(root: File, item: PackFile) {
    val file = safeChild(root, item.path)
    check(file.isFile && file.length() == item.bytes && fileHash(file) == item.sha256) { "Invalid model asset: ${item.path}" }
}
interface PackRepository {
    fun directory(id: String): File
    fun installed(id: String): Boolean
    suspend fun install(id: String)
    suspend fun delete(id: String)
}
class ModelStore(context: Context) : PackRepository {
    val packs: List<VoicePack> = Json.decodeFromString(context.assets.open("model-manifests.json").bufferedReader().use { it.readText() })
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val downloadRoot = File(context.noBackupFilesDir, "downloads").apply { mkdirs() }
    private val mutex = Mutex()
    private val verifiedThisProcess = mutableSetOf<String>()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).followSslRedirects(false).build()
    private val mutableProgress = MutableStateFlow<Map<String, PackProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()
    override fun directory(id: String) = File(root, packs.first { it.id == id }.id)
    override fun installed(id: String): Boolean = File(directory(id), ".verified").readTextOrNull() == manifestHash(id)
    private fun manifestHash(id: String) = sha256(Json.encodeToString(VoicePack.serializer(), packs.first { it.id == id }).toByteArray())
    private fun update(id: String, phase: String, bytes: Long = 0, total: Long = 0, busy: Boolean = true) {
        mutableProgress.value = mutableProgress.value + (id to PackProgress(phase, bytes, total, busy))
    }
    suspend fun verifyInstalled(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(installed(id)) { "Download $id first" }
            if (id !in verifiedThisProcess) {
                verify(directory(id), packs.first { it.id == id })
                verifiedThisProcess += id
            }
        }
    }
    override suspend fun install(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = packs.first { it.id == id }
            val staging = File(root, ".$id.installing")
            try {
                if (installed(id)) {
                    try { verify(directory(id), pack); update(id, "Installed", busy = false); return@withLock }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: IllegalStateException) { File(directory(id), ".verified").delete(); verifiedThisProcess -= id }
                }
                check(root.usableSpace > pack.downloadBytes + pack.installedBytes + 64L * 1024 * 1024) { "Not enough free storage for download and installation" }
                update(id, "Downloading", total = pack.downloadBytes)
                val archive = download(pack.url, File(downloadRoot, "$id.part"), pack.sha256, pack.downloadBytes) { count -> update(id, "Downloading", count, pack.downloadBytes) }
                staging.deleteRecursively(); staging.mkdirs()
                update(id, "Verifying and installing")
                if (pack.archiveRoot.isBlank()) archive.copyTo(File(staging, "model.onnx"))
                else extract(archive, staging, pack)
                for (extra in pack.extras) {
                    val asset = download(extra.url, File(downloadRoot, "$id-${extra.path.substringAfterLast('/')}.part"), extra.sha256, extra.bytes) { count -> update(id, if (extra.path.startsWith("voices/")) "Downloading voice" else "Downloading alignment assets", count, extra.bytes) }
                    val target = safeChild(staging, extra.path)
                    target.parentFile!!.mkdirs(); asset.copyTo(target, overwrite = true)
                }
                verify(staging, pack)
                currentCoroutineContext().ensureActive()
                File(staging, ".verified").writeText(manifestHash(id))
                check(!directory(id).exists() || directory(id).deleteRecursively())
                check(staging.renameTo(directory(id))) { "Atomic model installation failed" }
                verifiedThisProcess += id
                archive.delete()
                pack.extras.forEach { File(downloadRoot, "$id-${it.path.substringAfterLast('/')}.part").delete() }
                update(id, "Installed", busy = false)
            } catch (cancel: CancellationException) {
                staging.deleteRecursively(); update(id, "Cancelled; download can resume", busy = false); throw cancel
            } catch (error: Exception) {
                staging.deleteRecursively(); update(id, error.message ?: "Download failed", busy = false); throw error
            }
        }
    }
    private suspend fun download(url: String, file: File, hash: String, expected: Long, progress: (Long) -> Unit): File {
        require(url.startsWith("https://"))
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (file.length() == expected && fileHash(file) == hash) return file
            if (file.length() >= expected) file.delete()
            val offset = file.length()
            val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            try {
                client.newCall(request).execute().use { response ->
                    check(response.request.url.isHttps) { "Insecure download redirect rejected" }
                    check(response.isSuccessful) { "Download HTTP ${response.code}" }
                    val resume = offset > 0 && response.code == 206
                    if (resume) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid resume response" }
                    response.body!!.byteStream().use { input -> FileOutputStream(file, resume).use { output ->
                        val buffer = ByteArray(64 * 1024); var count = if (resume) offset else 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            count += n; check(count <= expected) { "Oversized download rejected" }
                            output.write(buffer, 0, n); progress(count)
                        }
                        output.fd.sync()
                    } }
                }
                check(file.length() == expected && fileHash(file) == hash) { "Asset checksum or size mismatch" }
                return file
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (attempt == 2) throw error; delay((attempt + 1) * 1000L) }
        }
        error("Download failed")
    }
    private suspend fun extract(archive: File, destination: File, pack: VoicePack) {
        val allowed = pack.files.associateBy { it.path }
        val seen = mutableSetOf<String>()
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextTarEntry ?: break
                require(!entry.isSymbolicLink && !entry.isLink && (entry.isFile || entry.isDirectory)) { "Unsafe archive entry" }
                require(entry.name.startsWith(pack.archiveRoot + "/") || entry.name == pack.archiveRoot) { "Unexpected archive root" }
                val relative = entry.name.removePrefix(pack.archiveRoot).removePrefix("/")
                if (relative.isEmpty()) continue
                val target = safeChild(destination, relative)
                if (entry.isDirectory) continue
                val expected = allowed[relative] ?: continue
                require(seen.add(relative) && entry.size == expected.bytes) { "Duplicate or invalid archive entry" }
                target.parentFile!!.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val n = tar.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                }
            }
        }
        check(seen == allowed.keys) { "Incomplete archive" }
    }
    private suspend fun verify(directory: File, pack: VoicePack) {
        (pack.files + pack.extras).forEach { item ->
            currentCoroutineContext().ensureActive()
            verifyAsset(directory, item)
        }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory(id).deleteRecursively(); File(root, ".$id.installing").deleteRecursively()
            downloadRoot.listFiles()?.filter { it.name == "$id.part" || it.name.startsWith("$id-") }?.forEach { it.delete() }
            verifiedThisProcess -= id; update(id, "Not installed", busy = false)
        }
        Unit
    }
}
private fun File.readTextOrNull() = if (isFile) readText() else null
