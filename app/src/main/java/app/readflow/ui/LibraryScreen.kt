package app.readflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.AppTheme
import app.readflow.data.DocumentEntity
import app.readflow.playback.ReaderPlayback

/** One library, three presentation families. Every layout shares the same durable state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LibraryScreen(
    vm: ReaderViewModel,
    theme: AppTheme,
    onImport: () -> Unit,
    onUrl: () -> Unit,
    requestNotifications: () -> Unit = {},
) {
    val documents by vm.library.collectAsStateWithLifecycle()
    val visible by vm.visibleLibrary.collectAsStateWithLifecycle()
    val libraryState by vm.libraryState.collectAsStateWithLifecycle()
    val history by vm.readingHistory.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val fontScale = LocalDensity.current.fontScale
    var adding by rememberSaveable { mutableStateOf(false) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = remember(history) { history.associate { it.documentId to it.page } }
    val resume = remember(documents, history, playback.document?.id) {
        latestResumeDocument(documents, history, playback.document?.id)
    }
    val voiceReady = remember(vm, prefs.model, downloads) { vm.app.models.installed(prefs.model) && vm.app.models.installed("alignment") }
    val browsing = libraryState.query.isBlank() && libraryState.filter == LibraryFilter.ALL
    val showResume = resume != null && browsing
    val activeAudio = playback.document != null &&
        (playback.currentChunk != null || playback.wantsToPlay || playback.fillingBuffer)
    val showMiniPlayer = activeAudio && !(theme == AppTheme.NIGHT_READER && showResume && resume?.id == playback.document?.id)
    val gridState = rememberLazyGridState()
    LaunchedEffect(libraryState.query, libraryState.filter, libraryState.sort) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = when {
                theme == AppTheme.MODERN_FOCUS -> 1
                fontScale >= 1.4f -> (maxWidth.value / 300f).toInt().coerceIn(1, 3)
                maxWidth < 340.dp -> 1
                maxWidth >= 1000.dp -> 4
                maxWidth >= 700.dp -> 3
                else -> 2
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns), state = gridState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Read Assist", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (theme == AppTheme.MODERN_FOCUS) Button(onClick = { adding = true }, enabled = !importing,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Add")
                        } else OutlinedButton(onClick = { adding = true }, enabled = !importing,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Add")
                        }
                    }
                }
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when (theme) {
                            AppTheme.WARM_LIBRARY -> "Your library"
                            AppTheme.MODERN_FOCUS -> "Library"
                            AppTheme.NIGHT_READER -> "Your reading space"
                        }, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                        if (theme == AppTheme.MODERN_FOCUS) Text("Books, documents and articles",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = libraryState.query, onValueChange = vm::setLibraryQuery,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Search your library") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (libraryState.query.isEmpty()) null else {
                            { IconButton(onClick = { vm.setLibraryQuery("") }) { Icon(Icons.Default.Close, "Clear library search") } }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        shape = if (theme == AppTheme.MODERN_FOCUS) RoundedCornerShape(16.dp) else RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        label = { Text("Search your library") },
                    )
                }
                if (importing) item(key = "importing", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Adding to your library…", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (showResume) item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                    val document = checkNotNull(resume)
                    val page = if (playback.document?.id == document.id) playback.page?.index ?: playback.viewPageIndex
                        else pages[document.id] ?: 0
                    ResumeDocumentCard(document, page, theme, playback, voiceReady,
                        open = { vm.open(document) },
                        listen = {
                            if (playback.document?.id == document.id && playback.wantsToPlay) vm.app.playback.pauseFromControls()
                            else if (voiceReady) { requestNotifications(); vm.openForListening(document) }
                            else vm.navigate("voices")
                        })
                }
                if (documents.isNotEmpty()) {
                    item(key = "documents-title", span = { GridItemSpan(maxLineSpan) }) {
                        LibrarySectionHeader(libraryState.sort, vm::setLibrarySort)
                    }
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryFilter.entries.forEach { filter ->
                                FilterChip(selected = libraryState.filter == filter, onClick = { vm.setLibraryFilter(filter) },
                                    label = { Text(libraryFilterLabel(filter)) }, modifier = Modifier.heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(24.dp))
                            }
                        }
                    }
                }
                if (documents.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryEmptyState(importing, add = { adding = true }, sample = { vm.sample() })
                } else if (visible.isEmpty()) item(key = "no-matches", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("No matching documents", style = MaterialTheme.typography.titleLarge)
                        Text("Try a different title or filter.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.setLibraryQuery(""); vm.setLibraryFilter(LibraryFilter.ALL) }) { Text("Show all documents") }
                    }
                } else items(visible, key = { it.id }) { document ->
                    val page = if (playback.document?.id == document.id) playback.page?.index ?: playback.viewPageIndex else pages[document.id]
                    if (theme == AppTheme.MODERN_FOCUS) LibraryDocumentRow(document, page, open = { vm.open(document) }, remove = { removingId = document.id })
                    else LibraryDocumentCard(document, page, theme, open = { vm.open(document) }, remove = { removingId = document.id })
                }
            }
        }
        if (showMiniPlayer) LibraryAudioBar(playback, open = vm::returnToReader, toggle = {
            if (playback.wantsToPlay) vm.app.playback.pauseFromControls()
            else { requestNotifications(); vm.app.playback.playFromControls() }
        })
    }

    if (adding) AddDocumentSheet(dismiss = { adding = false }, import = { adding = false; onImport() }, url = { adding = false; onUrl() })
    documents.firstOrNull { it.id == removingId }?.let { document ->
        AlertDialog(onDismissRequest = { removingId = null }, title = { Text("Remove from library?") },
            text = { Text("Remove “${document.title}” and its saved reading data from Read Assist? Your original file stays on your device.") },
            confirmButton = { TextButton(onClick = { removingId = null; vm.deleteDocument(document) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Keep") } })
    }
}

@Composable private fun LibrarySectionHeader(sort: LibrarySort, setSort: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val largeType = LocalDensity.current.fontScale >= 1.5f
    @Composable fun SortButton() {
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Sort, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(if (sort == LibrarySort.NEWEST) "Newest" else "Title")
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }) {
                LibrarySort.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(if (value == LibrarySort.NEWEST) "Newest first" else "Title A–Z") },
                        onClick = { setSort(value); expanded = false },
                        leadingIcon = { if (sort == value) Icon(Icons.Default.Check, null) })
                }
            }
        }
    }
    if (largeType) Column {
        Text("Your documents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        SortButton()
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Your documents", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        SortButton()
    }
}

@Composable private fun ResumeDocumentCard(document: DocumentEntity, page: Int, theme: AppTheme, playback: ReaderPlayback, voiceReady: Boolean,
    open: () -> Unit, listen: () -> Unit) {
    val largeType = LocalDensity.current.fontScale >= 1.4f
    val compact = theme == AppTheme.MODERN_FOCUS
    val speaking = playback.document?.id == document.id && playback.wantsToPlay
    val preparing = speaking && (playback.fillingBuffer || (playback.preparing && playback.currentChunk == null))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (theme == AppTheme.WARM_LIBRARY) Text("CONTINUE READING", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(onClick = open, shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (compact || theme == AppTheme.NIGHT_READER) Text(
                    if (speaking) { if (preparing) "PREPARING AUDIO" else "LISTENING NOW" } else "CONTINUE READING",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DocumentThumbnail(document, Modifier.size(width = if (compact || largeType) 58.dp else 92.dp,
                        height = if (compact || largeType) 78.dp else 132.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(document.title, maxLines = if (largeType) 4 else 3, overflow = TextOverflow.Ellipsis,
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(pageLabel(document, page), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (compact && !largeType) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (theme == AppTheme.WARM_LIBRARY) Button(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Continue"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                }
                if (theme == AppTheme.NIGHT_READER) {
                    @Composable fun ListenButton(modifier: Modifier) {
                        Button(onClick = listen, modifier = modifier.heightIn(min = 48.dp)) {
                            Icon(if (preparing) Icons.Default.Close else if (speaking) Icons.Default.Pause else if (!voiceReady) Icons.Default.RecordVoiceOver else Icons.Default.PlayArrow,
                                null, Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (preparing) "Cancel" else if (speaking) "Pause" else if (!voiceReady) "Set up voice" else "Listen")
                        }
                    }
                    if (largeType || (!voiceReady && !speaking)) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ListenButton(Modifier.fillMaxWidth())
                        TextButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open page") }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListenButton(Modifier.weight(1f))
                        TextButton(onClick = open, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Open page") }
                    }
                }
            }
        }
    }
}

@Composable private fun LibraryDocumentCard(document: DocumentEntity, page: Int?, theme: AppTheme, open: () -> Unit, remove: () -> Unit) {
    Card(onClick = open, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (theme == AppTheme.NIGHT_READER) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(if (theme == AppTheme.NIGHT_READER) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                DocumentThumbnail(document, Modifier.fillMaxWidth().aspectRatio(if (theme == AppTheme.NIGHT_READER) 1.25f else .8f).clip(RoundedCornerShape(12.dp)))
                Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                    DocumentOverflow(document, remove)
                }
            }
            Text(document.title, style = if (theme == AppTheme.NIGHT_READER) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(documentMetadata(document), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page != null) Text(pageLabel(document, page), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun LibraryDocumentRow(document: DocumentEntity, page: Int?, open: () -> Unit, remove: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${document.title}", onClick = open).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DocumentThumbnail(document, Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(documentMetadata(document), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page != null) Text(pageLabel(document, page), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DocumentOverflow(document, remove)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}

@Composable private fun DocumentOverflow(document: DocumentEntity, remove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Options for ${document.title}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Remove from library") }, onClick = { expanded = false; remove() },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
        }
    }
}

@Composable private fun LibraryEmptyState(importing: Boolean, add: () -> Unit, sample: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.padding(22.dp).size(42.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("Make room for a good read", style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Add a PDF, image or article. Read at your pace, or listen and follow along.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = add, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add your first document") }
        TextButton(onClick = sample, enabled = !importing) { Text("Try a sample") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AddDocumentSheet(dismiss: () -> Unit, import: () -> Unit, url: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to your library", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close add document") }
            }
            ListItem(headlineContent = { Text("Import PDF or image") }, supportingContent = { Text("Choose a file from your device") },
                leadingContent = { Icon(Icons.Default.UploadFile, null) }, modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = import))
            ListItem(headlineContent = { Text("Import webpage") }, supportingContent = { Text("Save an article from its link") },
                leadingContent = { Icon(Icons.Default.Link, null) }, modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = url))
        }
    }
}

@Composable private fun LibraryAudioBar(playback: ReaderPlayback, open: () -> Unit, toggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClickLabel = "Return to reader", onClick = open).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(playback.document?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(if (playback.fillingBuffer) "Preparing audio…" else if (playback.wantsToPlay) "Listening" else "Resume listening",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            IconButton(onClick = toggle, modifier = Modifier.size(52.dp)) {
                Icon(if (playback.fillingBuffer) Icons.Default.Close else if (playback.wantsToPlay) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playback.fillingBuffer) "Cancel audio preparation" else if (playback.wantsToPlay) "Pause listening" else "Resume listening",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

private fun libraryFilterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ALL -> "All"
    LibraryFilter.PDFS -> "PDFs"
    LibraryFilter.ARTICLES -> "Articles"
    LibraryFilter.IMAGES -> "Images"
}

private fun documentMetadata(document: DocumentEntity): String {
    val type = when {
        document.mime == "application/pdf" -> "PDF"
        document.mime.startsWith("image/") -> "Image"
        else -> "Article"
    }
    return "$type · ${document.pageCount} ${if (document.pageCount == 1) "page" else "pages"}"
}

private fun pageLabel(document: DocumentEntity, page: Int): String =
    "Page ${(page + 1).coerceIn(1, document.pageCount.coerceAtLeast(1))} of ${document.pageCount}"
