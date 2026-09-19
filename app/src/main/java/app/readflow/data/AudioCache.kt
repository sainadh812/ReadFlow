package app.readflow.data

import android.content.Context
import androidx.room.withTransaction
import app.readflow.core.*
import app.readflow.models.fileHash
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AudioCache(context: Context, private val database: ReadFlowDatabase) {
    private val root = File(context.noBackupFilesDir, "audio").apply { mkdirs() }
    private val dao = database.dao()
    suspend fun get(key: String): AlignedAudio? = withContext(Dispatchers.IO) {
        val row = dao.chunk(key) ?: return@withContext null
        val data = runCatching { Json.decodeFromString<AlignedAudio>(row.metadata) }.getOrNull() ?: return@withContext null
        val audio = File(data.audioPath)
        if (!audio.exists() || fileHash(audio) != data.audioSha256) { remove(row); return@withContext null }
        dao.touchChunk(key, System.currentTimeMillis()); data
    }
    fun file(key: String) = File(root, "$key.wav")
    suspend fun writePcm(key: String, audio: PcmAudio): PcmAudio = withContext(Dispatchers.IO) {
        val bytes = ByteBuffer.allocate(44 + audio.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + audio.samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(1).putShort(1).putInt(audio.sampleRate).putInt(audio.sampleRate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(audio.samples.size * 2)
        val quantized = FloatArray(audio.samples.size) { index ->
            val sample = (audio.samples[index].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            bytes.putShort(sample); sample.toFloat() / 32768
        }
        val partial = File(root, "$key.part")
        partial.outputStream().use { it.write(bytes.array()); it.fd.sync() }
        check(partial.renameTo(file(key)))
        PcmAudio(quantized, audio.sampleRate)
    }
    suspend fun commit(document: String, audio: AlignedAudio) = database.withTransaction {
        dao.putChunk(ChunkEntity(audio.key, document, Json.encodeToString(AlignedAudio.serializer(), audio), File(audio.audioPath).length()))
        dao.putTimings(audio.timings.map { TimingEntity(audio.key, it.wordId, it.startSample, it.endSample, it.method, it.confidence) })
    }
    suspend fun trim(limit: Long, protected: Set<String>) = withContext(Dispatchers.IO) {
        val rows = dao.chunks(); var bytes = rows.sumOf { it.bytes }
        for (row in rows) if (bytes > limit && row.key !in protected) { remove(row); bytes -= row.bytes }
        val known = dao.chunks().map { "${it.key}.wav" }.toSet()
        root.listFiles()?.filter { it.name !in known && it.nameWithoutExtension !in protected && System.currentTimeMillis() - it.lastModified() > 60_000 }?.forEach { it.delete() }
    }
    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) { dao.chunks().filter { it.documentId == id }.forEach { remove(it) } }
    private suspend fun remove(row: ChunkEntity) { dao.deleteChunk(row.key); file(row.key).delete() }
    suspend fun clear() = withContext(Dispatchers.IO) { dao.chunks().forEach { remove(it) }; root.listFiles()?.forEach { it.delete() }; Unit }
}
