package app.readflow.models

import app.readflow.core.sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackInstallTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                entries.forEach { (name, data) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun pack(archive: ByteArray, data: ByteArray, id: String = "test") = VoicePack(
        id, "Fixture", "1", "test", "fixture", "https://invalid.example/model.tar.bz2",
        sha256(archive), archive.size.toLong(), data.size.toLong(),
        listOf(PackFile("model.onnx", data.size.toLong(), sha256(data))),
        emptyList(), emptyList(), emptyList(), "test", "test",
    )

    @Test fun decompressionBuffersUnderlyingReadsAndReportsRealProgress() = runBlocking {
        val data = Random(42).nextBytes(512 * 1024)
        val bytes = archive(listOf("fixture/model.onnx" to data))
        val baseline = CountingInput(bytes)
        BZip2CompressorInputStream(baseline).use { it.copyTo(ByteArrayOutputStream()) }
        val buffered = CountingInput(bytes)
        val destination = temporary.newFolder()
        val progress = mutableListOf<Long>()
        extractPack(buffered, destination, pack(bytes, data)) { progress += it }
        assertArrayEquals(data, File(destination, "model.onnx").readBytes())
        assertTrue("Old path must reproduce byte-at-a-time reads", baseline.singleReads > bytes.size / 2)
        assertEquals("No unbuffered reads may reach the file", 0, buffered.singleReads)
        assertTrue(buffered.bulkReads <= bytes.size / PACK_BUFFER_SIZE + 2)
        assertTrue(buffered.closed)
        assertTrue(progress.size > 1)
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(bytes.size.toLong(), progress.last())
        println("Compressed bytes=${bytes.size}; old single reads=${baseline.singleReads}; buffered bulk reads=${buffered.bulkReads}")
    }

    @Test fun completedDownloadResumesOfflineAndSurvivesStoreRecreation() = runBlocking {
        val data = Random(8).nextBytes(200_000)
        val bytes = archive(listOf("fixture/model.onnx" to data))
        val pack = pack(bytes, data)
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(bytes)
        File(root, ".test.installing").mkdirs()
        File(root, ".test.installing/interrupted").writeText("partial")
        val store = ModelStore(listOf(pack), root, downloads)
        assertTrue(store.progress.value.getValue("test").canResume)
        assertFalse(store.installed("test"))
        store.install("test")
        assertTrue(store.installed("test"))
        assertArrayEquals(data, File(store.directory("test"), "model.onnx").readBytes())
        assertFalse(File(root, ".test.installing").exists())
        assertFalse(File(downloads, "test.part").exists())
        assertEquals("Installed", store.progress.value.getValue("test").phase)
        assertFalse(store.progress.value.getValue("test").busy)
        ModelStore(listOf(pack), root, downloads).verifyInstalled("test")
    }

    @Test fun rawAlignmentPackUsesSameVerifiedInstallationPath() = runBlocking {
        val data = Random(9).nextBytes(200_000)
        val pack = pack(data, data).copy(archiveRoot = "")
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(data)
        val store = ModelStore(listOf(pack), root, downloads)
        store.install("test")
        assertTrue(store.installed("test"))
        assertArrayEquals(data, File(store.directory("test"), "model.onnx").readBytes())
    }

    @Test fun changedManifestReusesOnlyVerifiedAllowlistedFilesWithoutNetwork() = runBlocking {
        val model = "same acoustic weights".toByteArray()
        val voices = "same voice styles".toByteArray()
        val oldBytes = archive(listOf("fixture/model.onnx" to model, "fixture/espeak-ng-data/unused" to "legacy".toByteArray()))
        val oldPack = pack(oldBytes, model).copy(
            files = listOf(PackFile("model.onnx", model.size.toLong(), sha256(model)),
                PackFile("espeak-ng-data/unused", 6, sha256("legacy".toByteArray()))),
            extras = listOf(PackFile("voices.bin", voices.size.toLong(), sha256(voices), "https://invalid.example/voices.bin")),
            installedBytes = model.size + voices.size + 6L,
        )
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(oldBytes)
        File(downloads, "test-voices.bin.part").writeBytes(voices)
        ModelStore(listOf(oldPack), root, downloads).install("test")
        val replacement = oldPack.copy(version = "misaki", archiveRoot = "", sha256 = sha256(model),
            downloadBytes = model.size.toLong(), installedBytes = (model.size + voices.size).toLong(),
            files = oldPack.files.filterNot { it.path.startsWith("espeak") })
        val store = ModelStore(listOf(replacement), root, downloads)
        assertFalse(store.installed("test"))
        store.install("test") // invalid.example cannot supply files; this must reuse verified local bytes.
        assertTrue(store.installed("test"))
        assertEquals(setOf("model.onnx", "voices.bin", ".verified"), store.directory("test").list()!!.toSet())
        assertArrayEquals(model, File(store.directory("test"), "model.onnx").readBytes())
        assertArrayEquals(voices, File(store.directory("test"), "voices.bin").readBytes())
        assertFalse(File(root, ".test.previous").exists())
        store.verifyInstalled("test")
    }

    @Test fun changedManifestDoesNotTrustSameSizeCorruptReusableFiles() = runBlocking {
        val model = "verified model".toByteArray()
        val source = pack(model, model).copy(archiveRoot = "")
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(model)
        val old = ModelStore(listOf(source), root, downloads)
        old.install("test")
        File(old.directory("test"), "model.onnx").writeBytes(model.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        File(downloads, "test.part").writeBytes(model)
        val replacement = ModelStore(listOf(source.copy(version = "new-manifest")), root, downloads)
        replacement.install("test")
        assertArrayEquals(model, File(replacement.directory("test"), "model.onnx").readBytes())
        replacement.verifyInstalled("test")
    }

    @Test fun interruptedReplacementRestoresOldInstallationBeforeNewStoreUsesIt() = runBlocking {
        val model = "recoverable model".toByteArray()
        val source = pack(model, model).copy(archiveRoot = "")
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(model)
        val store = ModelStore(listOf(source), root, downloads)
        store.install("test")
        assertTrue(store.directory("test").renameTo(File(root, ".test.previous")))
        val recovered = ModelStore(listOf(source), root, downloads)
        assertTrue(recovered.installed("test"))
        recovered.verifyInstalled("test")
        assertFalse(File(root, ".test.previous").exists())
    }

    @Test fun cancelledReuseKeepsWorkingOldInstallation() = runBlocking {
        val model = Random(31).nextBytes(512 * 1024)
        val source = pack(model, model).copy(archiveRoot = "")
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(model)
        ModelStore(listOf(source), root, downloads).install("test")
        val next = ModelStore(listOf(source.copy(version = "next")), root, downloads)
        val job = launch(start = CoroutineStart.LAZY) { next.install("test") }
        val watcher = launch(Dispatchers.Unconfined) {
            next.progress.first { it["test"]?.phase?.startsWith("Reusing") == true }
            job.cancel()
        }
        job.start(); job.join(); watcher.join()
        assertTrue(job.isCancelled)
        val original = ModelStore(listOf(source), root, downloads)
        assertTrue(original.installed("test"))
        original.verifyInstalled("test")
        assertFalse(File(root, ".test.installing").exists())
    }

    @Test fun rawGgufAndProjectorResumeOfflineAndBothRequireVerifiedChecksums() = runBlocking {
        val model = Random(90).nextBytes(200_000)
        val projector = Random(91).nextBytes(80_000)
        val id = "teleocr-fixture"
        val modelFile = PackFile("teleocr-q4.gguf", model.size.toLong(), sha256(model))
        val projectorFile = PackFile("mmproj-f16.gguf", projector.size.toLong(), sha256(projector),
            "https://invalid.example/mmproj-f16.gguf")
        val pack = pack(model, model, id).copy(archiveRoot = "", files = listOf(modelFile),
            extras = listOf(projectorFile), installedBytes = (model.size + projector.size).toLong())
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "$id.part").writeBytes(model)
        File(downloads, "$id-${projectorFile.path}.part").writeBytes(projector)
        File(root, ".$id.installing").mkdirs()
        File(root, ".$id.installing/interrupted").writeText("partial")

        val store = ModelStore(listOf(pack), root, downloads)
        assertTrue(store.progress.value.getValue(id).canResume)
        store.install(id)
        assertTrue(store.installed(id))
        assertArrayEquals(model, File(store.directory(id), modelFile.path).readBytes())
        assertArrayEquals(projector, File(store.directory(id), projectorFile.path).readBytes())
        assertFalse(File(store.directory(id), "model.onnx").exists())
        assertFalse(File(root, ".$id.installing").exists())
        assertFalse(File(downloads, "$id.part").exists())
        assertFalse(File(downloads, "$id-${projectorFile.path}.part").exists())
        ModelStore(listOf(pack), root, downloads).verifyInstalled(id)

        // A surviving marker and matching length must not let a damaged projector
        // through when the app process starts again.
        val corruptProjector = projector.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        File(store.directory(id), projectorFile.path).writeBytes(corruptProjector)
        try {
            ModelStore(listOf(pack), root, downloads).verifyInstalled(id)
            fail("A corrupted projector must not be loaded")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(projectorFile.path))
        }
    }

    @Test fun corruptExtractedFileIsNeverActivatedAndCanBeRetried() = runBlocking {
        val data = "model data".toByteArray()
        val bytes = archive(listOf("fixture/model.onnx" to data))
        val pack = pack(bytes, data).let { it.copy(files = listOf(it.files.single().copy(sha256 = "0".repeat(64)))) }
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(bytes)
        val store = ModelStore(listOf(pack), root, downloads)
        try { store.install("test"); fail("Invalid model activated") } catch (_: IllegalStateException) { }
        assertFalse(store.installed("test"))
        assertFalse(File(root, ".test.installing").exists())
        assertTrue(File(downloads, "test.part").exists())
        assertFalse(store.progress.value.getValue("test").busy)
        assertTrue(store.progress.value.getValue("test").canResume)
    }

    @Test fun extractionRejectsTraversalDuplicateAndMissingFiles() = runBlocking {
        val data = "model data".toByteArray()
        for (entries in listOf(
            listOf("fixture/../escape" to data),
            listOf("fixture/model.onnx" to data, "fixture/model.onnx" to data),
            listOf("fixture/unlisted" to data),
        )) {
            val bytes = archive(entries)
            try {
                extractPack(bytes.inputStream(), temporary.newFolder(), pack(bytes, data)) {}
                fail("Unsafe or incomplete archive accepted")
            } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }

    @Test fun cancelDuringUnpackingClosesInputBeforeEntireModelIsWritten() = runBlocking {
        val data = Random(10).nextBytes(512 * 1024)
        val bytes = archive(listOf("fixture/model.onnx" to data))
        val source = CountingInput(bytes)
        val destination = temporary.newFolder()
        val job = launch {
            extractPack(source, destination, pack(bytes, data)) { cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(source.closed)
        assertTrue(File(destination, "model.onnx").length() < data.size)
    }

    @Test fun cancelledVerificationKeepsDownloadForRetryAndClearsBusyState() = runBlocking {
        val data = Random(11).nextBytes(200_000)
        val bytes = archive(listOf("fixture/model.onnx" to data))
        val pack = pack(bytes, data)
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        File(downloads, "test.part").writeBytes(bytes)
        val store = ModelStore(listOf(pack), root, downloads)
        val job = launch(start = CoroutineStart.LAZY) { store.install("test") }
        val watcher = launch(Dispatchers.Unconfined) {
            store.progress.first { it["test"]?.let { p -> p.phase == "Checking download" && p.received > 0 } == true }
            job.cancel()
        }
        job.start()
        job.join()
        watcher.join()
        assertTrue(job.isCancelled)
        assertFalse(store.installed("test"))
        assertFalse(store.progress.value.getValue("test").busy)
        assertTrue(store.progress.value.getValue("test").canResume)
        assertArrayEquals(bytes, File(downloads, "test.part").readBytes())
        store.install("test")
        assertTrue(store.installed("test"))
    }

    @Test fun hashingChecksCancellationBetweenBlocks() = runBlocking {
        val source = temporary.newFile()
        source.writeBytes(Random(12).nextBytes(512 * 1024))
        var checked = 0L
        val job = launch {
            val context = currentCoroutineContext()
            fileHash(source) { count ->
                context.ensureActive()
                checked = count
                cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(checked in 1 until source.length())
    }

    @Test fun installBothPinnedModelArchivesAndAlignmentWithoutNetwork() = runBlocking {
        val deps = File(System.getenv("READFLOW_MODEL_TEST_DIR") ?: "../.deps")
        assumeTrue("Run scripts/fetch_models.py and scripts/fetch_voice.py to enable the real-pack test", File(deps, "kokoro-en-v0_19.tar.bz2").isFile)
        val packs: List<VoicePack> = Json.decodeFromString(File("src/main/assets/model-manifests.json").readText())
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        val store = ModelStore(packs, root, downloads)
        for (pack in packs) {
            val archive = if (pack.archiveRoot.isEmpty()) File(deps, "alignment/model.onnx") else File(deps, "${pack.archiveRoot}.tar.bz2")
            Files.createLink(File(downloads, "${pack.id}.part").toPath(), archive.toPath())
            for (extra in pack.extras) {
                val source = File(deps, if (extra.path.startsWith("voices/")) extra.path else "alignment/${extra.path}")
                Files.createLink(File(downloads, "${pack.id}-${extra.path.substringAfterLast('/')}.part").toPath(), source.toPath())
            }
            val start = System.nanoTime()
            store.install(pack.id)
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            assertTrue(store.installed(pack.id))
            assertFalse(store.progress.value.getValue(pack.id).busy)
            (pack.files + pack.extras).forEach { verifyAsset(store.directory(pack.id), it) }
            println("Pinned pack ${pack.id}: ${pack.files.size + pack.extras.size} files; install and verification ${"%.3f".format(seconds)} s (host JVM, not Android)")
        }
    }

    private class CountingInput(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var singleReads = 0
        var bulkReads = 0
        var closed = false
        override fun read(): Int { singleReads++; return delegate.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            bulkReads++
            return delegate.read(bytes, offset, length)
        }
        override fun close() { closed = true; delegate.close() }
    }
}
