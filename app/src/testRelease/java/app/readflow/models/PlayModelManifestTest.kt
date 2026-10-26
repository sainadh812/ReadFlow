package app.readflow.models

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayModelManifestTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manifests(): List<VoicePack> = Json.decodeFromString(File("src/release/assets/model-manifests.json").readText())

    @Test fun releaseOffersOnlyCleanDirectKokoroAndAlignmentFiles() {
        val packs = manifests()
        assertEquals(setOf("kokoro", "alignment"), packs.map { it.id }.toSet())
        val kokoro = packs.single { it.id == "kokoro" }
        assertEquals(setOf("model.onnx", "voices.bin", "tokens.txt", "LICENSE"), (kokoro.files + kokoro.extras).map { it.path }.toSet())
        assertEquals(351323831L, kokoro.installedBytes)
        for (pack in packs) {
            assertEquals("", pack.archiveRoot)
            assertEquals(pack.installedBytes, (pack.files + pack.extras).sumOf { it.bytes })
            assertEquals(pack.downloadBytes, pack.files.single().bytes)
            assertEquals(pack.sha256, pack.files.single().sha256)
            assertTrue((pack.files + pack.extras).none { it.path.contains("espeak", ignoreCase = true) })
            assertTrue((listOf(pack.url) + pack.extras.map { it.url }).all {
                Regex("https://huggingface\\.co/.+/resolve/[0-9a-f]{40}/.+").matches(it)
            })
        }
    }

    @Test fun kokoroUsesExactlyThePreviouslyTestedModelAndVoiceBytes() {
        val before: List<VoicePack> = Json.decodeFromString(File("src/main/assets/model-manifests.json").readText())
        val expected = before.single { it.id == "kokoro" }.files.associateBy { it.path }
        val after = manifests().single { it.id == "kokoro" }
        for (file in after.files + after.extras) {
            assertEquals(expected.getValue(file.path).bytes, file.bytes)
            assertEquals(expected.getValue(file.path).sha256, file.sha256)
        }
    }

    @Test fun pinnedDirectSpeechFilesInstallAndVerifyWithoutNetwork() = runBlocking {
        val deps = File(System.getenv("READFLOW_MODEL_TEST_DIR") ?: "../.deps")
        assumeTrue("Local pinned Kokoro files enable the offline release installation test", File(deps, "kokoro-en-v0_19/model.onnx").isFile)
        val packs = manifests()
        val root = temporary.newFolder()
        val downloads = temporary.newFolder()
        for (pack in packs) {
            val source = File(deps, if (pack.id == "kokoro") "kokoro-en-v0_19" else "alignment")
            val primary = pack.files.single()
            Files.createLink(File(downloads, "${pack.id}.part").toPath(), File(source, primary.path).toPath())
            for (extra in pack.extras) {
                Files.createLink(File(downloads, "${pack.id}-${extra.path.substringAfterLast('/')}.part").toPath(), File(source, extra.path).toPath())
            }
        }
        val store = ModelStore(packs, root, downloads)
        for (pack in packs) {
            store.install(pack.id)
            assertTrue(store.installed(pack.id))
            (pack.files + pack.extras).forEach { verifyAsset(store.directory(pack.id), it) }
            assertEquals((pack.files + pack.extras).map { it.path }.toSet() + ".verified", store.directory(pack.id).list()!!.toSet())
        }
    }
}
