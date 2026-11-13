package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readflow.core.PageContent
import app.readflow.data.DocumentEntity
import app.readflow.data.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Prefetch two saved neighbours, retaining the incoming page until its live content is ready. */
@Composable internal fun rememberAdjacentReaderPages(
    vm: ReaderViewModel, document: DocumentEntity?, pageIndex: Int, enabled: Boolean,
): Map<Int, PageContent> {
    var pages by remember(document?.id) { mutableStateOf<Map<Int, PageContent>>(emptyMap()) }
    LaunchedEffect(document?.id, pageIndex, enabled) {
        if (!enabled || document == null) { pages = emptyMap(); return@LaunchedEffect }
        val retained = pages.filterKeys { it in pageIndex - 1..pageIndex + 1 }
        pages = retained
        pages = withContext(Dispatchers.IO) {
            buildMap {
                putAll(retained)
                for (index in listOf(pageIndex - 1, pageIndex + 1)) {
                    if (index !in 0 until document.pageCount) continue
                    try {
                        val saved = vm.app.documents.dao.page(document.id, index) ?: continue
                        put(index, Json.decodeFromString<PageContent>(saved.content))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* The normal page-opening path reports a failed load. */ }
                }
            }
        }
    }
    return pages
}

/** A noninteractive first-screen preview, using the same type and spacing as ReaderText. */
@Composable internal fun ReaderTextTurnPreview(page: PageContent?, prefs: Preferences, pageIndex: Int) {
    val paper = LocalReaderPaper.current
    Box(Modifier.fillMaxSize().readerPaper(paper).clearAndSetSemantics {}, contentAlignment = Alignment.TopCenter) {
        if (page == null) Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.titleMedium, color = paper.ink)
            Spacer(Modifier.height(8.dp))
            Text("Text will load when you turn the page", style = MaterialTheme.typography.bodySmall, color = paper.muted)
        } else LazyColumn(
            userScrollEnabled = false,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(page.paragraphs, key = { it.id }) { paragraph ->
                Text(page.reading.substring(paragraph.start, paragraph.end),
                    fontSize = (prefs.fontSize + if (paragraph.heading) 3 else 0).sp,
                    lineHeight = (prefs.fontSize * 1.65f).sp,
                    fontFamily = ReaderFonts.family(prefs.readerFont), color = paper.ink,
                    fontWeight = if (paragraph.heading) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth())
            }
            if (page.words.isEmpty()) item { Text("No readable text on this page.", color = paper.muted) }
        }
    }
}
