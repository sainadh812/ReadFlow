package app.readflow.speech

import app.readflow.core.PcmAudio
import java.io.File
import java.io.RandomAccessFile

/** Reads the exact PCM16 output of an Android TTS engine, without resampling or normalization. */
internal fun readTtsWave(file: File): PcmAudio = RandomAccessFile(file, "r").use { input ->
    require(input.length() in 44..MAX_TTS_WAVE_BYTES) { "Invalid or oversized speech WAV" }
    fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    require(tag() == "RIFF") { "Speech output is not a RIFF WAV" }
    val end = 8L + u32()
    require(end in 44..input.length() && tag() == "WAVE") { "Truncated speech WAV" }
    var rate = 0
    var channels = 0
    var dataOffset = -1L
    var dataBytes = 0L
    while (input.filePointer + 8 <= end) {
        val kind = tag()
        val bytes = u32()
        val start = input.filePointer
        require(bytes <= end - start) { "Truncated WAV chunk" }
        when (kind) {
            "fmt " -> {
                require(bytes >= 16 && rate == 0) { "Invalid WAV format chunk" }
                require(u16() == 1) { "Speech engine must return uncompressed PCM16" }
                channels = u16()
                rate = u32().toInt()
                val byteRate = u32()
                val blockAlign = u16()
                val bits = u16()
                require(channels == 1 && rate in 8_000..48_000 && bits == 16 && blockAlign == 2 && byteRate == rate * 2L) {
                    "Speech engine must return mono PCM16 at 8–48 kHz"
                }
            }
            "data" -> {
                require(dataOffset == -1L && bytes > 0 && bytes % 2 == 0L) { "Invalid WAV audio chunk" }
                dataOffset = start
                dataBytes = bytes
            }
        }
        val paddedEnd = start + bytes + (bytes and 1L)
        require(paddedEnd <= end) { "Missing WAV chunk padding" }
        input.seek(paddedEnd)
    }
    require(rate > 0 && dataOffset >= 0 && dataBytes / 2 <= rate * 60L) { "Missing or excessively long speech audio" }
    input.seek(dataOffset)
    val pcm = ByteArray(dataBytes.toInt()).also(input::readFully)
    val samples = FloatArray(pcm.size / 2) { i ->
        val signed = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        signed / 32768f
    }
    PcmAudio(samples, rate)
}

private const val MAX_TTS_WAVE_BYTES = 6_000_000L
