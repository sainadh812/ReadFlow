package app.readflow.dictionary

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val BUFFER_BYTES = 64 * 1024

/** A strict output bound prevents decompression from exceeding the pinned pack size. */
internal suspend fun copyDictionaryBytes(input: InputStream, output: OutputStream, expected: Long, progress: (Long) -> Unit = {}): String {
    require(expected > 0)
    val context = currentCoroutineContext()
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_BYTES)
    var copied = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        check(count.toLong() <= expected - copied) { "Dictionary data exceeds the expected size" }
        output.write(buffer, 0, count)
        hash.update(buffer, 0, count)
        copied += count
        progress(copied)
    }
    check(copied == expected) { "Dictionary download is incomplete" }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal suspend fun dictionaryFileHash(file: File): String {
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(BUFFER_BYTES).use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal suspend fun unpackDictionary(archive: File, destination: File, bytes: Long, hash: String, progress: (Long) -> Unit) {
    try {
        archive.inputStream().buffered(BUFFER_BYTES).use { source ->
            GZIPInputStream(source, BUFFER_BYTES).use { input ->
                FileOutputStream(destination).use { output ->
                    check(copyDictionaryBytes(input, output, bytes, progress) == hash) { "Dictionary checksum did not match" }
                    output.fd.sync()
                }
            }
        }
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

/** Same-filesystem directory swap; a failed or interrupted replacement keeps the previous pack. */
internal fun promoteDictionary(staging: File, installed: File, previous: File) {
    check(!previous.exists() || previous.deleteRecursively()) { "Cannot clear old dictionary files" }
    val replacing = installed.exists()
    if (replacing) check(installed.renameTo(previous)) { "Cannot preserve the installed dictionary" }
    if (!staging.renameTo(installed)) {
        check(!replacing || previous.renameTo(installed)) { "Dictionary will be recovered on restart" }
        error("Could not install dictionary; previous copy kept")
    }
    previous.deleteRecursively()
}

internal fun recoverDictionary(installed: File, previous: File) {
    if (!previous.exists()) return
    if (!installed.exists()) check(previous.renameTo(installed)) { "Cannot recover the previous dictionary" }
    else previous.deleteRecursively()
}
