package app.readflow.models

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal const val PACK_BUFFER_SIZE = 64 * 1024

internal suspend fun extractPack(
    source: InputStream,
    destination: File,
    pack: VoicePack,
    progress: (Long) -> Unit,
) {
    val context = currentCoroutineContext()
    val allowed = pack.files.associateBy { it.path }
    val seen = mutableSetOf<String>()
    val buffer = ByteArray(PACK_BUFFER_SIZE)
    // BZip2 reads individual compressed bytes. Buffer before it to avoid a disk read per byte.
    source.buffered(PACK_BUFFER_SIZE).use { input ->
        BZip2CompressorInputStream(input).use { compressed ->
            TarArchiveInputStream(compressed).use { tar ->
                while (true) {
                    context.ensureActive()
                    val entry = tar.nextTarEntry ?: break
                    require(!entry.isSymbolicLink && !entry.isLink && (entry.isFile || entry.isDirectory)) { "Unsafe archive entry" }
                    require(entry.name.startsWith(pack.archiveRoot + "/") || entry.name == pack.archiveRoot) { "Unexpected archive root" }
                    val relative = entry.name.removePrefix(pack.archiveRoot).removePrefix("/")
                    if (relative.isEmpty()) continue
                    val target = safeChild(destination, relative)
                    if (entry.isDirectory) continue
                    val expected = allowed[relative]
                    if (expected != null) {
                        require(seen.add(relative) && entry.size == expected.bytes) { "Duplicate or invalid archive entry" }
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create model directory" }
                    }
                    // Drain unlisted entries explicitly so cancellation works during skipped data too.
                    val output = if (expected != null) FileOutputStream(target) else null
                    output.use {
                        while (true) {
                            context.ensureActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            output?.write(buffer, 0, count)
                            progress(compressed.compressedCount)
                        }
                    }
                }
                check(seen == allowed.keys) { "Incomplete archive" }
                progress(pack.downloadBytes)
            }
        }
    }
}

internal suspend fun copyPackAsset(source: File, target: File, progress: (Long) -> Unit) {
    val context = currentCoroutineContext()
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create model directory" }
    source.inputStream().use { input ->
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(PACK_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                progress(copied)
            }
            output.fd.sync()
        }
    }
}
