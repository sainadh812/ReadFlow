package app.readflow.ui

import app.readflow.data.DocumentEntity
import app.readflow.data.ManualReadingHistory
import app.readflow.data.ReadingPosition
import java.util.Locale

enum class LibraryFilter(val label: String) { ALL("All"), PDFS("PDFs"), ARTICLES("Articles"), IMAGES("Images") }
enum class LibrarySort(val label: String) { NEWEST("Newest"), TITLE("Title") }
data class LibraryState(val query: String = "", val filter: LibraryFilter = LibraryFilter.ALL, val sort: LibrarySort = LibrarySort.NEWEST)

internal fun filterLibrary(documents: List<DocumentEntity>, state: LibraryState): List<DocumentEntity> {
    val query = state.query.trim()
    val filtered = documents.filter { document ->
        (query.isBlank() || document.title.contains(query, ignoreCase = true)) && when (state.filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.PDFS -> document.mime == "application/pdf"
            LibraryFilter.ARTICLES -> document.mime != "application/pdf" && !document.mime.startsWith("image/")
            LibraryFilter.IMAGES -> document.mime.startsWith("image/")
        }
    }
    return when (state.sort) {
        LibrarySort.NEWEST -> filtered.sortedWith(compareByDescending<DocumentEntity> { it.importedAt }.thenBy { it.id })
        LibrarySort.TITLE -> filtered.sortedWith(compareBy<DocumentEntity> { it.title.lowercase(Locale.ROOT) }.thenByDescending { it.importedAt }.thenBy { it.id })
    }
}

internal fun manualResumePage(document: DocumentEntity, manual: ManualReadingHistory?, audio: ReadingPosition?): Int =
    manual?.takeIf { it.documentId == document.id && it.page in 0 until document.pageCount }?.page
        ?: audio?.takeIf { it.documentId == document.id && it.page in 0 until document.pageCount }?.page ?: 0

internal fun listeningResume(document: DocumentEntity, audio: ReadingPosition?): Pair<Int, String?> =
    audio?.takeIf { it.documentId == document.id && it.page in 0 until document.pageCount }
        ?.let { it.page to it.wordId.takeIf(String::isNotBlank) } ?: (0 to null)

internal fun latestResumeDocument(documents: List<DocumentEntity>, history: List<ManualReadingHistory>, activeId: String?): DocumentEntity? {
    val byId = documents.associateBy { it.id }
    byId[activeId]?.let { return it }
    return history.sortedWith(compareByDescending<ManualReadingHistory> { it.lastOpenedAt }.thenByDescending { it.lastViewedAt })
        .firstNotNullOfOrNull { row -> byId[row.documentId]?.takeIf { row.page in 0 until it.pageCount } }
}
