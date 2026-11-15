package app.readflow.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
@Entity(tableName = "manual_reading_history", foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class ManualReadingHistory(@PrimaryKey val documentId: String, val page: Int, val lastOpenedAt: Long, val lastViewedAt: Long)

internal const val CREATE_MANUAL_HISTORY = "CREATE TABLE IF NOT EXISTS `manual_reading_history` (`documentId` TEXT NOT NULL, `page` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL, PRIMARY KEY(`documentId`), FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
internal const val RECORD_MANUAL_OPEN = """INSERT INTO manual_reading_history (documentId, page, lastOpenedAt, lastViewedAt)
    SELECT id, :page, :time, :time FROM documents WHERE id = :id
    ON CONFLICT(documentId) DO UPDATE SET
    page = CASE WHEN excluded.lastViewedAt >= lastViewedAt THEN excluded.page ELSE page END,
    lastOpenedAt = MAX(lastOpenedAt, excluded.lastOpenedAt), lastViewedAt = MAX(lastViewedAt, excluded.lastViewedAt)"""
internal const val RECORD_MANUAL_VIEW = """INSERT INTO manual_reading_history (documentId, page, lastOpenedAt, lastViewedAt)
    SELECT id, :page, :time, :time FROM documents WHERE id = :id
    ON CONFLICT(documentId) DO UPDATE SET
    page = CASE WHEN excluded.lastViewedAt >= lastViewedAt THEN excluded.page ELSE page END,
    lastViewedAt = MAX(lastViewedAt, excluded.lastViewedAt)"""
internal const val READING_HISTORY_QUERY = """SELECT d.id AS documentId, COALESCE(h.page, p.page) AS page,
    COALESCE(h.lastOpenedAt, p.updatedAt) AS lastOpenedAt, COALESCE(h.lastViewedAt, p.updatedAt) AS lastViewedAt
    FROM documents d LEFT JOIN manual_reading_history h ON h.documentId = d.id
    LEFT JOIN positions p ON p.documentId = d.id
    WHERE h.documentId IS NOT NULL OR p.documentId IS NOT NULL
    ORDER BY lastOpenedAt DESC, lastViewedAt DESC, d.id"""
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
    @Query(READING_HISTORY_QUERY) fun readingHistory(): Flow<List<ManualReadingHistory>>
    @Query("SELECT * FROM manual_reading_history WHERE documentId=:id") suspend fun manualPosition(id: String): ManualReadingHistory?
    @Query(RECORD_MANUAL_OPEN) suspend fun recordManualOpen(id: String, page: Int, time: Long)
    @Query(RECORD_MANUAL_VIEW) suspend fun recordManualView(id: String, page: Int, time: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChunk(chunk: ChunkEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTimings(timings: List<TimingEntity>)
    @Query("SELECT * FROM chunks WHERE `key`=:key") suspend fun chunk(key: String): ChunkEntity?
    @Query("SELECT * FROM chunks ORDER BY usedAt ASC") suspend fun chunks(): List<ChunkEntity>
    @Query("DELETE FROM chunks WHERE `key`=:key") suspend fun deleteChunk(key: String)
    @Query("UPDATE chunks SET usedAt=:time WHERE `key`=:key") suspend fun touchChunk(key: String, time: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bookmark(item: BookmarkEntity)
    @Query("SELECT * FROM bookmarks WHERE documentId=:id") fun bookmarks(id: String): Flow<List<BookmarkEntity>>
}
@Database(entities = [DocumentEntity::class, PageEntity::class, WordEntity::class, ReadingPosition::class, ManualReadingHistory::class, ChunkEntity::class, TimingEntity::class, BookmarkEntity::class], version = 2, exportSchema = true)
abstract class ReadFlowDatabase : RoomDatabase() {
    abstract fun dao(): ReadFlowDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL(CREATE_MANUAL_HISTORY) }
        }
        fun open(context: Context) = Room.databaseBuilder(context, ReadFlowDatabase::class.java, "readflow.db")
            .addMigrations(MIGRATION_1_2).build()
    }
}
