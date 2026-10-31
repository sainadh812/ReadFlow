package app.readflow.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PcmWaveTest {
    @Test fun readsExactSignedPcmWithoutChangingRateOrAmplitude() {
        val result = read(wave(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)))
        assertEquals(24_000, result.sampleRate)
        assertArrayEquals(floatArrayOf(-1f, -1 / 32768f, 0f, 1 / 32768f, 32767 / 32768f), result.samples, 0f)
    }

    @Test fun acceptsPaddedMetadataChunksAndExtendedPcmFormat() {
        val result = read(wave(shortArrayOf(123, -456), extra = true))
        assertArrayEquals(floatArrayOf(123 / 32768f, -456 / 32768f), result.samples, 0f)
    }

    @Test fun rejectsTruncatedAudioRatherThanReturningPartialSpeech() {
        rejects(wave(shortArrayOf(1, 2, 3)).dropLast(1).toByteArray())
    }

    @Test fun rejectsStereoAndCompressedFormats() {
        rejects(wave(shortArrayOf(1), channels = 2))
        rejects(wave(shortArrayOf(1), format = 3))
    }

    @Test fun rejectsEmptyOutput() { rejects(wave(shortArrayOf())) }

    @Test fun rejectsIncorrectRiffIdentityAndHugeChunkLength() {
        rejects(wave(shortArrayOf(1)).also { it[0] = 'X'.code.toByte() })
        rejects(wave(shortArrayOf(1)).also { for (i in 40..43) it[i] = 0xff.toByte() })
    }

    @Test fun rejectsExcessiveAudioDurationEvenInsideFileSizeLimit() {
        rejects(wave(ShortArray(8_000 * 60 + 1), rate = 8_000))
    }

    private fun read(bytes: ByteArray) = Files.createTempFile("tts-wave-test", ".wav").toFile().let { file ->
        try { file.writeBytes(bytes); readTtsWave(file) } finally { file.delete() }
    }

    private fun rejects(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { read(bytes) }
    }

    private fun wave(samples: ShortArray, extra: Boolean = false, channels: Int = 1, format: Int = 1, rate: Int = 24_000): ByteArray {
        val body = ByteArrayOutputStream()
        fun chunk(tag: String, bytes: ByteArray) {
            body.write(tag.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            body.write(bytes)
            if (bytes.size % 2 != 0) body.write(0)
        }
        val fmt = ByteBuffer.allocate(if (extra) 18 else 16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(format.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16)
        chunk("fmt ", fmt.array())
        if (extra) chunk("LIST", byteArrayOf(42, 43, 44))
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(pcm::putShort)
        chunk("data", pcm.array())
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size() + 4).array())
            write("WAVE".toByteArray())
            write(body.toByteArray())
        }.toByteArray()
    }
}
