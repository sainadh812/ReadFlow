package app.readflow.speech

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MisakiKokoroEngineTest {
    @Test fun expandsMisakiSymbolsInsteadOfUsingTheUntrainedUppercaseTokenIds() {
        val phones = KokoroV019Codec.legacyPhones("həlˈO wˈɜɹld, bˈʌTəɹ ænd ʧˈAŋʤ.")
        assertEquals("həlˈoʊ wˈɜɹld, bˈʌɾəɹ ænd tʃˈeɪŋdʒ.", phones)
        assertEquals("aɪ aʊ ɔɪ əʊ ə", KokoroV019Codec.legacyPhones("I W Y Q ᵊ"))
    }

    @Test fun retainsStressAndPunctuationForProsody() {
        assertEquals("«hˈaɪ» — 'jˈɛs'!", KokoroV019Codec.legacyPhones("(hˈI)\n\t- ‘jˈɛs’!"))
    }

    @Test fun readsTheLiteralSpaceTokenAndPreservesBosEosAndTokenIds() {
        val tokens = KokoroV019Codec.readTokens("$ 0\r\n  16\r\nh 50\r\ne 47\r\nɪ 102\r\nˈ 156\r\n! 5\r\n")
        assertArrayEquals(longArrayOf(0, 50, 156, 47, 102, 16, 50, 5, 0), KokoroV019Codec.encode("hˈA h!", tokens))
    }

    @Test fun rejectsAnUnknownPhoneWithoutDroppingItsWord() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            KokoroV019Codec.encode("h❓", minimalTokens())
        }
        assertTrue(failure.message!!.contains("U+2753"))
    }

    @Test fun rejectsIncompleteOrSilentPronunciationsAndBoundaryInjection() {
        for (phones in listOf("", "   ", "!?", "h$")) {
            assertThrows(IllegalArgumentException::class.java) { KokoroV019Codec.encode(phones, minimalTokens()) }
        }
    }

    @Test fun enforcesTheLimitAfterExpandingPhonesAndNeverTruncates() {
        val tokens = minimalTokens() + mapOf('e'.code to 47L, 'ɪ'.code to 102L)
        val longest = KokoroV019Codec.encode("A".repeat(255), tokens)
        assertEquals(512, longest.size)
        assertEquals(0L, longest.first())
        assertEquals(0L, longest.last())
        assertThrows(IllegalArgumentException::class.java) { KokoroV019Codec.encode("A".repeat(256), tokens) }
    }

    @Test fun rejectsDamagedTokenCatalogs() {
        for (text in listOf("$ 0\n  16\nh 50\nh 51", "$ 0\n  16\nh 50\ne 50", "$ 1\n  16", "$ 0\n  17", "$ 0\n  16\nINVALID")) {
            assertThrows(IllegalArgumentException::class.java) { KokoroV019Codec.readTokens(text) }
        }
    }

    @Test fun matchesExistingPauseLengthWithoutChangingSpeechSamples() {
        val first = floatArrayOf(.2f, -.3f, .7f)
        val second = floatArrayOf(-.6f, .2f)
        val samples = FloatArray(5_000) + first + FloatArray(4_800) + second + FloatArray(6_000)
        val expected = FloatArray(1_000) + first + FloatArray(960) + second + FloatArray(1_200)
        assertArrayEquals(expected, KokoroV019Pauses.shorten(samples), 0f)
    }

    @Test fun retainsShortQuietConsonantsAndTheExistingTrailingPauseBoundary() {
        val samples = floatArrayOf(.2f) + FloatArray(4_799) + floatArrayOf(-.3f) + FloatArray(4_800)
        assertSame(samples, KokoroV019Pauses.shorten(samples))
    }

    @Test fun selectsTheChosenSpeakerAndUnpaddedLengthWithoutAnOffByOne() {
        val file = Files.createTempFile("kokoro-styles", ".bin").toFile()
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(11L * 511 * 256 * 4)
                for (count in listOf(1, 17, 510)) {
                    val bytes = ByteBuffer.allocate(256 * 4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(256) { bytes.putFloat(count.toFloat() + it / 256f) }
                    output.seek((2L * 511 * 256 + count * 256) * 4)
                    output.write(bytes.array())
                }
            }
            val voice = KokoroV019Style.read(file, 2)
            for (count in listOf(1, 17, 510)) {
                val style = voice.forTokenCount(count)
                assertEquals(256, style.size)
                assertEquals(count.toFloat(), style[0], 0f)
                assertEquals(count + 255 / 256f, style[255], 0f)
            }
            assertArrayEquals(FloatArray(256), KokoroV019Style.read(file, 1).forTokenCount(17), 0f)
            assertThrows(IllegalArgumentException::class.java) { voice.forTokenCount(0) }
            assertThrows(IllegalArgumentException::class.java) { voice.forTokenCount(511) }
            assertThrows(IllegalArgumentException::class.java) { KokoroV019Style.read(file, 11) }
            RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }
            assertThrows(IllegalArgumentException::class.java) { KokoroV019Style.read(file, 0) }
        } finally { file.delete() }
    }

    private fun minimalTokens() = mapOf('$'.code to 0L, ' '.code to 16L, 'h'.code to 50L)
}
