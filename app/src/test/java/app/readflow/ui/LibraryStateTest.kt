package app.readflow.ui

import app.readflow.data.DocumentEntity
import app.readflow.data.ManualReadingHistory
import app.readflow.data.ReadingPosition
import org.junit.Assert.*
import org.junit.Test

class LibraryStateTest {
    private fun doc(id: String, title: String = id, mime: String = "application/pdf", imported: Long = 1) =
        DocumentEntity(id, "hash", "source", title, "", mime, 10, importedAt = imported)

    @Test fun manualResumeWinsWhileListeningKeepsItsOwnWordAndPage() {
        val document = doc("book")
        val audio = ReadingPosition("book", 2, "spoken-word", 30)
        val manual = ManualReadingHistory("book", 7, 40, 50)
        assertEquals(7, manualResumePage(document, manual, audio))
        assertEquals(2 to "spoken-word", listeningResume(document, audio))
    }

    @Test fun upgradeAndInvalidHistoryFallBackToExistingAudioThenFirstPage() {
        val document = doc("book")
        val audio = ReadingPosition("book", 3, "word", 30)
        assertEquals(3, manualResumePage(document, null, audio))
        assertEquals(3, manualResumePage(document, ManualReadingHistory("book", 99, 40, 50), audio))
        assertEquals(3, manualResumePage(document, ManualReadingHistory("other", 7, 40, 50), audio))
        assertEquals(0, manualResumePage(document, null, audio.copy(page = -1)))
        assertEquals(0 to null, listeningResume(document, audio.copy(page = 50)))
        assertEquals(0 to null, listeningResume(document, audio.copy(documentId = "other")))
    }

    @Test fun resumeUsesActiveSessionThenLatestValidOpenAndIgnoresDeletedDocuments() {
        val a = doc("a"); val b = doc("b"); val c = doc("c")
        val history = listOf(ManualReadingHistory("deleted", 1, 100, 100), ManualReadingHistory("c", 99, 90, 90),
            ManualReadingHistory("b", 4, 80, 80), ManualReadingHistory("a", 1, 70, 999))
        assertEquals(a, latestResumeDocument(listOf(a, b, c), history, "a"))
        assertEquals(b, latestResumeDocument(listOf(a, b, c), history, null))
        assertEquals(b, latestResumeDocument(listOf(a, b, c), history, "deleted"))
        assertNull(latestResumeDocument(emptyList(), history, null))
    }

    @Test fun filtersSearchAndSortComposeWithoutMutatingTheLibrary() {
        val items = listOf(doc("a", "A quiet book", imported = 10), doc("b", "Book news", "text/html", 30),
            doc("c", "BOOK scan", "image/png", 20), doc("d", "A note", "text/plain", 40))
        assertEquals(listOf("b", "c", "a"), filterLibrary(items, LibraryState(query = " book ")).map { it.id })
        assertEquals(listOf("b"), filterLibrary(items, LibraryState("book", LibraryFilter.ARTICLES)).map { it.id })
        assertEquals(listOf("d", "b"), filterLibrary(items, LibraryState(filter = LibraryFilter.ARTICLES)).map { it.id })
        assertEquals(listOf("c"), filterLibrary(items, LibraryState(filter = LibraryFilter.IMAGES)).map { it.id })
        assertEquals(listOf("a"), filterLibrary(items, LibraryState(filter = LibraryFilter.PDFS)).map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), filterLibrary(items, LibraryState(sort = LibrarySort.TITLE)).map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.id })
    }
}
