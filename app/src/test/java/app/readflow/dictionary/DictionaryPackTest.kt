package app.readflow.dictionary

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DictionaryPackTest {
    private val manifest = DictionaryManifest(
        "english-dictionary", "2026.10-v1", "English dictionary", "https://example.com/dictionary.sqlite.gz",
        100, "a".repeat(64), 200, "b".repeat(64), 1, "Wiktionary", "Wiktionary contributors", "https://creativecommons.org/licenses/by-sa/4.0/",
    )

    @Test fun onlyPinnedHttpsPacksWithinStorageBoundsAreAccepted() {
        manifest.validate()
        for (invalid in listOf(
            manifest.copy(url = "http://example.com/pack.gz"),
            manifest.copy(url = "https://user:password@example.com/pack.gz"),
            manifest.copy(url = "https://example.com/pack.gz#fragment"),
            manifest.copy(version = "../../elsewhere"),
            manifest.copy(downloadSha256 = "unverified"),
            manifest.copy(downloadBytes = 0),
            manifest.copy(downloadBytes = 513L * 1024 * 1024),
            manifest.copy(installedBytes = 3L * 1024 * 1024 * 1024),
            manifest.copy(schemaVersion = 2),
        )) {
            try { invalid.validate(); fail("Unsafe manifest accepted: $invalid") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun priorPinnedEditionCanBeReadButForeignOrUnsupportedPacksCannot() {
        manifest.copy(version = "2025.01-v1", installedSha256 = "c".repeat(64)).validateInstalledFor(manifest)
        for (incompatible in listOf(manifest.copy(id = "another-dictionary"), manifest.copy(schemaVersion = 2))) {
            try { incompatible.validateInstalledFor(manifest); fail("Incompatible installed receipt accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun boundedCopyChecksExactSizeAndHash() = runTest {
        val bytes = "dictionary data".toByteArray()
        val output = ByteArrayOutputStream()
        assertEquals(hash(bytes), copyDictionaryBytes(bytes.inputStream(), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
        for (expected in listOf(bytes.size - 1, bytes.size + 1)) {
            try { copyDictionaryBytes(bytes.inputStream(), ByteArrayOutputStream(), expected.toLong()); fail("Wrong size accepted") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun interruptedCopyStopsAtTheNextChunk() = runTest {
        val bytes = ByteArray(256 * 1024) { 1 }
        val output = ByteArrayOutputStream()
        val job = launch(start = CoroutineStart.LAZY) {
            copyDictionaryBytes(bytes.inputStream(), output, bytes.size.toLong()) { cancel() }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(output.size() in 1 until bytes.size)
    }

    @Test fun cancelledInstallerKeepsTheDirectoryUntilItsCleanupCompletes() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val installer = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        started.await()
        assertTrue(dictionaryOperationPending(installer, null))
        installer.cancel()
        cleanupStarted.await()
        assertFalse(installer.isActive)
        assertFalse(installer.isCompleted)
        assertTrue("Retry must not overwrite a cancelling installer's staging files", dictionaryOperationPending(installer, null))
        assertTrue("Removal cleanup also keeps new installs out", dictionaryOperationPending(null, installer))
        releaseCleanup.complete(Unit)
        installer.join()
        assertFalse(dictionaryOperationPending(installer, null))
        assertFalse(dictionaryOperationPending(null, null))
    }

    @Test fun damagedGzipAndExpansionPastPinnedSizeRemovePartialDatabase() = runTest {
        val root = Files.createTempDirectory("dictionary-integrity").toFile()
        try {
            val bytes = ByteArray(128 * 1024) { 7 }
            val compressed = ByteArrayOutputStream().also { stream -> GZIPOutputStream(stream).use { it.write(bytes) } }.toByteArray()
            val archive = File(root, "pack.gz")
            val database = File(root, "dictionary.sqlite")
            archive.writeBytes(compressed)
            unpackDictionary(archive, database, bytes.size.toLong(), hash(bytes)) {}
            assertArrayEquals(bytes, database.readBytes())
            assertEquals(hash(bytes), dictionaryFileHash(database))

            for ((data, size, expectedHash) in listOf(
                Triple(compressed, bytes.size.toLong() - 1, hash(bytes)),
                Triple(compressed, bytes.size.toLong(), "0".repeat(64)),
                Triple(compressed.copyOf(compressed.size - 5), bytes.size.toLong(), hash(bytes)),
                Triple(compressed.clone().apply { this[lastIndex - 7] = (this[lastIndex - 7].toInt() xor 1).toByte() }, bytes.size.toLong(), hash(bytes)),
            )) {
                archive.writeBytes(data)
                try { unpackDictionary(archive, database, size, expectedHash) {}; fail("Damaged pack activated") }
                catch (_: Exception) { }
                assertFalse("Partial database survived rejection", database.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedReplacementRestoresThePreviouslyInstalledPack() {
        val root = Files.createTempDirectory("dictionary-rollback").toFile()
        try {
            val installed = File(root, "current").also { it.mkdir() }
            val previous = File(root, ".previous")
            File(installed, "verified").writeText("working dictionary")
            try { promoteDictionary(File(root, "missing-staging"), installed, previous); fail("Missing staging promoted") }
            catch (_: IllegalStateException) { }
            assertEquals("working dictionary", File(installed, "verified").readText())
            assertFalse(previous.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun restartRecoversInterruptedSwapAndSuccessfulSwapKeepsNewPack() {
        val root = Files.createTempDirectory("dictionary-recovery").toFile()
        try {
            val installed = File(root, "current")
            val previous = File(root, ".previous").also { it.mkdir() }
            val staging = File(root, ".installing").also { it.mkdir() }
            File(previous, "verified").writeText("previous")
            File(staging, "verified").writeText("new")
            recoverDictionary(installed, previous)
            assertEquals("previous", File(installed, "verified").readText())
            promoteDictionary(staging, installed, previous)
            assertEquals("new", File(installed, "verified").readText())
            assertFalse(staging.exists())
            assertFalse(previous.exists())
            previous.mkdir()
            File(previous, "verified").writeText("leftover")
            recoverDictionary(installed, previous)
            assertEquals("new", File(installed, "verified").readText())
            assertFalse(previous.exists())
        } finally { root.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
