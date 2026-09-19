package app.readflow.core

import app.readflow.models.*
import app.readflow.playback.GenerationGate
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IntegrityTest {
    @Test fun traversalAndAbsoluteArchivePathsAreRejected() {
        val root = Files.createTempDirectory("readflow").toFile()
        try {
            for (name in listOf("../escape", "/tmp/escape", "foo/../../escape", "..\\escape")) {
                try { safeChild(root, name); fail(name) } catch (_: IllegalArgumentException) { }
            }
            assertEquals(File(root, "voices/alba.wav"), safeChild(root, "voices/alba.wav"))
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptAndPartialAssetsNeverVerify() {
        val root = Files.createTempDirectory("readflow").toFile()
        try {
            val good = "verified weights".toByteArray()
            val expected = PackFile("weights.onnx", good.size.toLong(), sha256(good))
            val asset = File(root, expected.path)
            asset.writeBytes(good); verifyAsset(root, expected)
            for (bytes in listOf(good.dropLast(1).toByteArray(), "corrupt! weights".toByteArray())) {
                asset.writeBytes(bytes)
                try { verifyAsset(root, expected); fail("Corrupt asset activated") } catch (_: IllegalStateException) { }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun rapidJumpsInvalidateAnUninterruptibleLateResult() {
        val gate = GenerationGate(); val old = gate.next()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        var committed = false
        val worker = Thread {
            started.countDown(); release.await(2, TimeUnit.SECONDS)
            committed = gate.current(old); finished.countDown()
        }
        worker.start(); assertTrue(started.await(2, TimeUnit.SECONDS))
        var latest = old
        repeat(1000) { latest = gate.next() }
        release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)); worker.join()
        assertFalse(committed); assertTrue(gate.current(latest))
    }
    @Test fun unknownSymbolsNeverBecomeInventedSpeech() {
        val page = DocumentPipeline().process("equation", 0, ExtractedPage(listOf(TextElement("x = y", emptyList())), 0f, 0f, "fixture"))
        try { EnglishNormalizer().normalize(page.words); fail("Equation should require explicit skipping") } catch (_: IllegalArgumentException) { }
    }
}
