package app.readflow.data

import android.content.Context
import androidx.room.*
import app.readflow.core.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents") data class DocumentEntity(
    @PrimaryKey val id: String, val hash: String, val source: String, val title: String,
    val localPath: String, val mime: String, val pageCount: Int, val state: String = "Ready",
    val processingVersion: String = PIPELINE_VERSION, val importedAt: Long = System.currentTimeMillis(),
)
@Entity(tableName = "pages", primaryKeys = ["documentId", "pageIndex"], foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class PageEntity(val documentId: String, val pageIndex: Int, val content: String, val status: String, val rotation: Int = 0)
@Entity(tableName = "words", indices = [Index("documentId"), Index("pageId")], foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class WordEntity(@PrimaryKey val id: String, val documentId: String, val pageId: String, val paragraphId: String, val sentenceId: String, val order: Int, val sourceStart: Int, val sourceEnd: Int, val readingStart: Int, val readingEnd: Int, val original: String, val geometry: String)
@Entity(tableName = "positions", foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class ReadingPosition(@PrimaryKey val documentId: String, val page: Int, val wordId: String, val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "chunks", indices = [Index("documentId")])
data class ChunkEntity(@PrimaryKey val key: String, val documentId: String, val metadata: String, val bytes: Long, val usedAt: Long = System.currentTimeMillis())
@Entity(tableName = "timings", primaryKeys = ["chunkId", "wordId"], foreignKeys = [ForeignKey(entity = ChunkEntity::class, parentColumns = ["key"], childColumns = ["chunkId"], onDelete = ForeignKey.CASCADE)])
data class TimingEntity(val chunkId: String, val wordId: String, val startSample: Long, val endSample: Long, val method: String, val quality: Float)
@Entity(tableName = "bookmarks", primaryKeys = ["documentId", "wordId"], foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class BookmarkEntity(val documentId: String, val wordId: String, val page: Int, val label: String)
@Dao interface ReadFlowDao {
    @Query("SELECT * FROM documents ORDER BY importedAt DESC") fun library(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE id=:id") suspend fun document(id: String): DocumentEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDocument(document: DocumentEntity)
    @Query("DELETE FROM documents WHERE id=:id") suspend fun deleteDocument(id: String)
    @Query("SELECT * FROM pages WHERE documentId=:id AND pageIndex=:page") suspend fun page(id: String, page: Int): PageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPage(page: PageEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWords(words: List<WordEntity>)
    @Query("DELETE FROM words WHERE pageId=:id") suspend fun deleteWords(id: String)
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY pageIndex") suspend fun pages(id: String): List<PageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosition(position: ReadingPosition)
    @Query("SELECT * FROM positions WHERE documentId=:id") suspend fun position(id: String): ReadingPosition?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChunk(chunk: ChunkEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTimings(timings: List<TimingEntity>)
    @Query("SELECT * FROM chunks WHERE `key`=:key") suspend fun chunk(key: String): ChunkEntity?
    @Query("SELECT * FROM chunks ORDER BY usedAt ASC") suspend fun chunks(): List<ChunkEntity>
    @Query("DELETE FROM chunks WHERE `key`=:key") suspend fun deleteChunk(key: String)
    @Query("UPDATE chunks SET usedAt=:time WHERE `key`=:key") suspend fun touchChunk(key: String, time: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bookmark(item: BookmarkEntity)
    @Query("SELECT * FROM bookmarks WHERE documentId=:id") fun bookmarks(id: String): Flow<List<BookmarkEntity>>
}
@Database(entities = [DocumentEntity::class, PageEntity::class, WordEntity::class, ReadingPosition::class, ChunkEntity::class, TimingEntity::class, BookmarkEntity::class], version = 1, exportSchema = true)
abstract class ReadFlowDatabase : RoomDatabase() {
    abstract fun dao(): ReadFlowDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context, ReadFlowDatabase::class.java, "readflow.db").build() }
}
