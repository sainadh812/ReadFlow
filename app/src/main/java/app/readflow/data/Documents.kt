package app.readflow.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.readflow.core.*
import app.readflow.ingest.*
import app.readflow.diagnostics.*
import app.readflow.models.fileHash
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Documents(private val context: Context, val database: ReadFlowDatabase, val extractor: LocalExtraction, private val articles: ArticleExtractor,
    private val issues: IssueLogs? = null,
) {
    val dao = database.dao()
    private val root = File(context.noBackupFilesDir, "documents").apply { mkdirs() }
    private val extractionLock = Mutex()
    private val pipeline = DocumentPipeline()
    suspend fun import(uri: Uri): DocumentEntity = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Imported document"
        val temporary = File.createTempFile("import-", ".part", root)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected file" }
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= 512L * 1024 * 1024) { "This MVP supports files up to 512 MB" }
                        check(root.usableSpace > 16L * 1024 * 1024) { "Not enough storage to import" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            val pdf = temporary.inputStream().use { String(it.readNBytes(5), Charsets.US_ASCII) } == "%PDF-"
            require(pdf || mime.startsWith("image/")) { "Choose a PDF or image" }
            val hash = fileHash(temporary); val id = stableId(hash, PIPELINE_VERSION)
            dao.document(id)?.let { return@withContext it }
            val destination = File(root, "$id.${if (pdf) "pdf" else "image"}")
            check(temporary.renameTo(destination)) { "Cannot save imported document" }
            try {
                val count = if (pdf) extractor.pageCount(destination.path) else 1
                DocumentEntity(id, hash, uri.toString(), title, destination.path, if (pdf) "application/pdf" else mime, count).also { dao.insertDocument(it) }
            } catch (error: SecurityException) { destination.delete(); error("Password-protected PDF: import an unlocked copy. Password entry is not available in this MVP.") }
            catch (error: Exception) { destination.delete(); throw IllegalArgumentException("The document is corrupt or unsupported: ${error.message}", error) }
        } finally { temporary.delete() }
    }
    suspend fun importUrl(url: String): DocumentEntity = withContext(Dispatchers.IO) {
        val article = articles.import(url)
        val hash = sha256(article.sanitizedHtml.toByteArray()); val id = stableId(hash, PIPELINE_VERSION)
        dao.document(id)?.let { return@withContext it }
        val local = File(root, "$id.html").apply { writeText(article.sanitizedHtml) }
        val document = DocumentEntity(id, hash, url, article.title, local.path, "text/html", 1)
        dao.insertDocument(document)
        val page = pipeline.process(id, 0, article.page)
        save(page)
        if (article.page.warnings.isNotEmpty()) issues?.record("ARTICLE_WARNING", input = documentIssueInput(document, page),
            warnings = article.page.warnings, dedupeKey = stableId(document.id, article.page.warnings.joinToString()))
        document
    }
    suspend fun loadPage(document: DocumentEntity, index: Int, rotation: Int? = null): PageContent = extractionLock.withLock {
        require(index in 0 until document.pageCount)
        val old = dao.page(document.id, index)
        if (rotation == null && old != null) return@withLock Json.decodeFromString(old.content)
        check(document.mime != "text/html") { "Saved article content is unavailable" }
        val content = pipeline.process(document.id, index, extractor.extract(document.localPath, index, rotation ?: old?.rotation ?: 0))
        save(content, rotation ?: old?.rotation ?: 0)
        val issueWarnings = content.warnings.filterNot { it.startsWith("OCR supplies word geometry") }
        if (issueWarnings.isNotEmpty()) issues?.record("EXTRACTION_WARNING", input = documentIssueInput(document, content,
            words = content.words.take(128), details = mapOf("rotation" to (rotation ?: old?.rotation ?: 0).toString())),
            warnings = issueWarnings, dedupeKey = stableId(document.id, index, rotation ?: 0, issueWarnings.joinToString()))
        content
    }
    private suspend fun save(page: PageContent, rotation: Int = 0) {
        database.withTransaction {
            dao.putPage(PageEntity(page.documentId, page.index, Json.encodeToString(PageContent.serializer(), page), page.extraction, rotation))
            dao.deleteWords(page.id)
            dao.putWords(page.words.map { WordEntity(it.id, page.documentId, page.id, it.paragraphId, it.sentenceId, it.order, it.sourceStart, it.sourceEnd, it.start, it.end, it.text, Json.encodeToString(ListSerializer(Box.serializer()), it.boxes)) })
        }
    }
    suspend fun delete(document: DocumentEntity) = withContext(Dispatchers.IO) {
        dao.deleteDocument(document.id)
        File(document.localPath).delete()
        Unit
    }
}
