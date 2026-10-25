package app.readflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.core.*
import app.readflow.data.Preferences
import app.readflow.data.DocumentEntity
import app.readflow.playback.ReaderPlayback
import java.util.Locale

private val Accent = Color(0xFF126D63)
private val SelectionBackground = Color(0xFFD4EEE6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReadFlowUi(vm: ReaderViewModel, requestNotifications: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var urlDialog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    BackHandler(screen != "library") {
        if (screen == "benchmark") vm.leaveBenchmark()
        else vm.screen.value = if (screen == "reader" || playback.document == null) "library" else "reader"
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    MaterialTheme(colorScheme = if (prefs.dark) darkColorScheme(
        primary = Color(0xFF88D5C5), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF164D43), onPrimaryContainer = Color(0xFFC4F4E4),
        secondary = Color(0xFFB6CDC3), secondaryContainer = Color(0xFF2F4941), onSecondaryContainer = Color(0xFFD2E9DD),
        surface = Color(0xFF171D1B), onSurface = Color(0xFFE2E9E3), surfaceVariant = Color(0xFF35413C), onSurfaceVariant = Color(0xFFB8C8BF),
        background = Color(0xFF101614), onBackground = Color(0xFFE2E9E3), outlineVariant = Color(0xFF35413C),
        surfaceContainerLowest = Color(0xFF0B110F), surfaceContainerLow = Color(0xFF171D1B), surfaceContainer = Color(0xFF1B2420),
        surfaceContainerHigh = Color(0xFF25302A), surfaceContainerHighest = Color(0xFF303C35))
        else lightColorScheme(
            primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFD9EFE7), onPrimaryContainer = Color(0xFF124C40),
            secondary = Color(0xFF526C60), secondaryContainer = Color(0xFFE4EEE5), onSecondaryContainer = Color(0xFF284D3C),
            surface = Color(0xFFFFFEFA), onSurface = Color(0xFF192B25), surfaceVariant = Color(0xFFE7ECE4), onSurfaceVariant = Color(0xFF57675F),
            background = Color(0xFFF3F5EF), onBackground = Color(0xFF192B25), outlineVariant = Color(0xFFD8E1D7),
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F7F1), surfaceContainer = Color(0xFFEFF2E9),
            surfaceContainerHigh = Color(0xFFE9EDE4), surfaceContainerHighest = Color(0xFFE2E8DE)),
        typography = Typography(
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-.5).sp),
            headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 0.sp)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when (screen) {
                    "reader" -> Reader(vm, playback, prefs, requestNotifications)
                    "voices" -> Voices(vm)
                    "settings" -> Settings(vm, prefs)
                    "ocr" -> OcrSettingsScreen(vm, prefs)
                    "benchmark" -> BenchmarkScreen(vm, prefs)
                    "issues" -> IssueLogsScreen(vm)
                    else -> Library(vm, { picker.launch(arrayOf("application/pdf", "image/*")) }, { urlDialog = true })
                }
            }
        }
        if (urlDialog) {
            var url by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { urlDialog = false }, title = { Text("Import webpage") },
                text = { OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { urlDialog = false; vm.importUrl(url) }, enabled = url.startsWith("https://")) { Text("Import") } },
                dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Cancel") } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Library(vm: ReaderViewModel, import: () -> Unit, url: () -> Unit) {
    val documents by vm.library.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<DocumentEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("ReadFlow", fontWeight = FontWeight.SemiBold) }, actions = {
            Tool(Icons.Default.RecordVoiceOver, "Voice models") { vm.screen.value = "voices" }
            Tool(Icons.Default.Settings, "Settings") { vm.screen.value = "settings" }
        })
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.OfflineBolt, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(4.dp))
                        Text("On device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(if (documents.isEmpty()) "Bring a book, PDF or article." else "${documents.size} ${if (documents.size == 1) "document" else "documents"} ready to open",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = import, enabled = !importing, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Import file")
            }
            OutlinedButton(onClick = url, enabled = !importing, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Link, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Webpage")
            }
        }
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            Text("Adding to your library…", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (documents.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(24.dp))
            Text("A little more time to read", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Import a document, then listen and follow along.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { vm.sample() }, enabled = !importing) { Text("Try a sample") }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(documents, key = { it.id }) { doc ->
                Card(onClick = { vm.open(doc) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(width = 48.dp, height = 64.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (doc.mime == "application/pdf") Icons.Default.PictureAsPdf else if (doc.mime.startsWith("image")) Icons.Default.Image else Icons.Default.Article,
                                    null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("${if (doc.mime == "application/pdf") "PDF" else if (doc.mime.startsWith("image")) "Image" else "Article"} · ${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Tool(Icons.Default.DeleteOutline, "Delete ${doc.title}") { deleting = doc }
                    }
                }
            }
        }
    }
    deleting?.let { document ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove from library?") },
            text = { Text("Remove “${document.title}” and its saved reading data from ReadFlow? Your original file stays on your device.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteDocument(document) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Reader(vm: ReaderViewModel, state: ReaderPlayback, prefs: Preferences, requestNotifications: () -> Unit) {
    KeepReaderScreenAwake()
    val refreshingText by vm.refreshingText.collectAsStateWithLifecycle()
    val ocrProgress by vm.ocrProgress.collectAsStateWithLifecycle()
    val hasOriginal = state.document?.mime == "application/pdf" || state.document?.mime?.startsWith("image/") == true
    var original by remember(state.document?.id) { mutableStateOf(hasOriginal) }
    var contents by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var speeds by remember { mutableStateOf(false) }
    var preparePages by remember { mutableStateOf(false) }
    var pageOnly by rememberSaveable(state.document?.id) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var follow by remember(state.document?.id) { mutableStateOf(true) }
    val page = state.page
    val pageIndex = page?.index ?: state.viewPageIndex
    var rotation by remember(state.document?.id, pageIndex) { mutableIntStateOf(0) }
    var fitWidth by remember(state.document?.id, pageIndex) { mutableStateOf(true) }
    val selected = page?.words?.firstOrNull { it.id == state.selectedWordId }
    val clipboard = LocalClipboardManager.current
    val status = ocrProgress ?: if (refreshingText) "Updating text recognition…" else state.error ?: state.status
    BackHandler(pageOnly) { pageOnly = false }
    // Reveal recovery controls if playback stops on an error while the chrome is hidden.
    LaunchedEffect(state.error, state.blockedSentence) {
        if (state.error != null || state.blockedSentence != null) pageOnly = false
    }
    Column(Modifier.fillMaxSize()) {
        // Keep navigation in the header and listening controls in one dock; the page owns the remaining height.
        if (!pageOnly) Surface {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.AutoMirrored.Filled.ArrowBack, "Library") { vm.screen.value = "library" }
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Contents and bookmarks") { contents = true }.padding(vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(state.document?.title ?: "Reader", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Page ${pageIndex + 1} of ${state.document?.pageCount ?: 1}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Tool(Icons.Default.Fullscreen, "Hide controls for more page space") { searching = false; pageOnly = true }
                Box {
                    Tool(Icons.Default.MoreVert, "Reader menu") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        if (ocrProgress != null || refreshingText) {
                            DropdownMenuItem(text = { Text("Cancel text recognition") }, onClick = { vm.cancelOcr(); menu = false }, leadingIcon = { Icon(Icons.Default.Close, null) })
                        }
                        if (selected != null && !state.wantsToPlay) {
                            DropdownMenuItem(text = { Text("Read from selected word") }, onClick = { menu = false; requestNotifications(); vm.app.playback.start(wordId = selected.id) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) })
                            DropdownMenuItem(text = { Text("Copy selected word") }, onClick = { clipboard.setText(AnnotatedString(selected.text)); menu = false }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                            DropdownMenuItem(text = { Text("Bookmark selected word") }, onClick = { vm.bookmark(selected); menu = false }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) })
                        }
                        DropdownMenuItem(text = { Text("Contents and bookmarks") }, onClick = { contents = true; menu = false }, leadingIcon = { Icon(Icons.Default.List, null) })
                        DropdownMenuItem(text = { Text("Prepare pages before listening") }, onClick = { preparePages = true; menu = false },
                            enabled = state.document != null && !state.preparing && !refreshingText,
                            leadingIcon = { Icon(Icons.Default.Download, null) })
                        if ((state.document?.pageCount ?: 0) > 1) {
                            DropdownMenuItem(text = { Text("Previous page") }, onClick = { vm.page(pageIndex - 1); menu = false }, enabled = pageIndex > 0, leadingIcon = { Icon(Icons.Default.ChevronLeft, null) })
                            DropdownMenuItem(text = { Text("Next page") }, onClick = { vm.page(pageIndex + 1); menu = false }, enabled = pageIndex + 1 < (state.document?.pageCount ?: 1), leadingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        }
                        if (hasOriginal) {
                            DropdownMenuItem(text = { Text(if (original) "Show reader text" else "Show original page") }, onClick = { original = !original; if (original) searching = false; menu = false }, leadingIcon = { Icon(if (original) Icons.Default.TextFields else Icons.Default.Description, null) })
                            if (original) {
                                DropdownMenuItem(text = { Text(if (fitWidth) "Fit whole page" else "Fit page width") }, onClick = { fitWidth = !fitWidth; menu = false }, leadingIcon = { Icon(Icons.Default.FitScreen, null) })
                                DropdownMenuItem(text = { Text(if (prefs.swipePages) "Page swipes: on" else "Page swipes: off") }, onClick = { vm.update(prefs.copy(swipePages = !prefs.swipePages)); menu = false })
                                DropdownMenuItem(text = { Text("Rotate page view") }, onClick = { rotation = (rotation + 90) % 360; menu = false }, leadingIcon = { Icon(Icons.Default.RotateRight, null) })
                            }
                        }
                        DropdownMenuItem(text = { Text("Find in page text") }, onClick = { searching = true; original = false; menu = false }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        DropdownMenuItem(text = { Text("Playback speed · ${String.format(Locale.US, "%.2f×", prefs.speed)}") }, onClick = { speeds = true; menu = false }, leadingIcon = { Icon(Icons.Default.Speed, null) })
                        DropdownMenuItem(text = { Text("Back ten seconds") }, onClick = { vm.app.playback.skip(-10); menu = false }, leadingIcon = { Icon(Icons.Default.Replay10, null) })
                        DropdownMenuItem(text = { Text("Forward ten seconds") }, onClick = { vm.app.playback.skip(10); menu = false }, leadingIcon = { Icon(Icons.Default.Forward10, null) })
                        DropdownMenuItem(text = { Text("Voice models") }, onClick = { vm.screen.value = "voices"; menu = false }, leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) })
                        DropdownMenuItem(text = { Text(if (follow) "Stop following reading" else "Follow reading") }, onClick = { follow = !follow; menu = false }, leadingIcon = { Icon(Icons.Default.MyLocation, null) })
                        DropdownMenuItem(text = { Text(if (prefs.showPerformance) "Hide performance" else "Show performance") }, onClick = { vm.update(prefs.copy(showPerformance = !prefs.showPerformance)); menu = false }, leadingIcon = { Icon(Icons.Default.Speed, null) })
                        DropdownMenuItem(text = { Text("Text recognition · ${modelDisplayName(prefs.ocrEngine)}") }, onClick = { vm.screen.value = "ocr"; menu = false }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) })
                        DropdownMenuItem(text = { Text("Phone testbench") }, onClick = { vm.screen.value = "benchmark"; menu = false }, leadingIcon = { Icon(Icons.Default.Science, null) })
                        DropdownMenuItem(text = { Text("Settings and appearance") }, onClick = { vm.screen.value = "settings"; menu = false }, leadingIcon = { Icon(Icons.Default.Tune, null) })
                        DropdownMenuItem(text = { Text("Reading details") }, onClick = { details = true; menu = false }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        DropdownMenuItem(text = { Text("Issue logs") }, onClick = { vm.showIssues(); menu = false }, leadingIcon = { Icon(Icons.Default.BugReport, null) })
                        if (hasOriginal) {
                            DropdownMenuItem(text = { Text("Retry text recognition") }, onClick = { vm.retryOcr(); menu = false }, enabled = !refreshingText && !state.preparing, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                            DropdownMenuItem(text = { Text("Rotate scan and retry OCR") }, onClick = { vm.rotateOcr(); menu = false }, enabled = !refreshingText && !state.preparing, leadingIcon = { Icon(Icons.Default.RotateRight, null) })
                        }
                    }
                }
            }
        }
        if (searching) OutlinedTextField(query, { query = it }, label = { Text("Find on page") }, trailingIcon = { Tool(Icons.Default.Close, "Close search") { query = ""; searching = false } }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (original) OriginalPage(vm, state, Modifier.fillMaxSize(), follow, rotation, fitWidth, prefs.swipePages, prefs.swipeRightAdvances) { follow = false }
            else if (page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (state.preparing) CircularProgressIndicator() }
            else ReaderText(page, state, prefs, query, follow, { follow = false }, { vm.app.playback.select(it) }, { vm.bookmark(it) }, Modifier.fillMaxSize())
            if (pageOnly) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                Tool(Icons.Default.FullscreenExit, "Show reader controls") { pageOnly = false }
            }
            if (!follow && !pageOnly) FilledTonalButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Follow reading")
            }
        }
        if (!pageOnly) state.prepareAheadProgress?.let { progress ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(progress.status, style = MaterialTheme.typography.bodyMedium)
                    if (progress.running) LinearProgressIndicator(progress = { progress.completedPages.toFloat() / progress.totalPages }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${progress.completedPages}/${progress.totalPages} pages · ${formatTime(progress.audioMs)} audio ready",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (progress.running) TextButton(onClick = { vm.app.playback.stop() }) { Text("Cancel") }
                    }
                }
            }
        }
        if (!pageOnly && prefs.showPerformance) PerformancePanel(vm, prefs)
        if (!pageOnly) PlaybackPanel(vm, state, requestNotifications, prefs.speed, { speeds = true }, hasOriginal, original,
            { original = !original; if (original) searching = false }, selected, status,
            showDetails = { details = true }, copySelection = { selected?.let { clipboard.setText(AnnotatedString(it.text)) } })
    }
    if (preparePages && state.document != null) {
        var endPage by remember(state.document.id, pageIndex) { mutableStateOf(minOf(pageIndex + 10, state.document.pageCount).toString()) }
        val end = endPage.toIntOrNull()?.takeIf { it in pageIndex + 1..state.document.pageCount }
        AlertDialog(onDismissRequest = { preparePages = false }, title = { Text("Prepare pages before listening") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save speech from page ${pageIndex + 1} through the page you choose. For a chapter, enter its last page. Listening starts when you press Play.")
                OutlinedTextField(endPage, { endPage = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("Last page") }, suffix = { Text("/ ${state.document.pageCount}") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Text("Keep ReadFlow open while preparing. Completed audio is kept if you cancel. Preparation stops before exceeding your ${prefs.cacheMb} MB audio cache.",
                    style = MaterialTheme.typography.bodySmall)
                if (prefs.ocrEngine == "teleocr") Text("New TeleOCR pages can take several minutes each.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { end?.let { preparePages = false; vm.app.playback.prepareAhead(it - 1) } }, enabled = end != null) { Text("Prepare") } },
            dismissButton = { TextButton(onClick = { preparePages = false }) { Text("Cancel") } })
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Reading details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (state.bufferedListeningMs > 0) {
                Text("Prepared audio: ${formatTime(state.bufferedListeningMs)} at your current speed")
                Text("${state.bufferedSentences} complete ${if (state.bufferedSentences == 1) "sentence" else "sentences"} ahead")
            }
            page?.let { Text("Text source: ${it.extraction}") }
            if (state.durationMs > 0) Text("Audio: ${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}")
            page?.warnings?.forEach { Text(it) }
            if (state.error != null) TextButton(onClick = { details = false; vm.showIssues() }) { Text("Open issue logs") }
            if (state.blockedSentence != null) TextButton(onClick = { details = false; requestNotifications(); vm.app.playback.skipBlockedSentence() }) { Text("Skip sentence") }
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
    if (speeds) AlertDialog(onDismissRequest = { speeds = false }, title = { Text("Playback speed") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { speed ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = prefs.speed == speed, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { vm.update(prefs.copy(speed = speed)); speeds = false }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = prefs.speed == speed, onClick = null)
                    Spacer(Modifier.width(12.dp)); Text("${speed}×")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { speeds = false }) { Text("Close") } })
    if (contents && state.document != null) {
        val bookmarks by vm.app.documents.dao.bookmarks(state.document.id).collectAsStateWithLifecycle(emptyList())
        var goToPage by remember { mutableStateOf((pageIndex + 1).toString()) }
        val targetPage = goToPage.toIntOrNull()?.takeIf { it in 1..state.document.pageCount }
        AlertDialog(onDismissRequest = { contents = false }, title = { Text("Contents") },
            text = { Column {
                OutlinedTextField(goToPage, { goToPage = it.filter(Char::isDigit).take(5) }, label = { Text("Page") },
                    singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    suffix = { Text("/ ${state.document.pageCount}") })
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(bookmarks) { bookmark -> TextButton(onClick = { contents = false; requestNotifications(); vm.readBookmark(bookmark) }) { Icon(Icons.Default.Bookmark, null); Text("${bookmark.label} · Page ${bookmark.page + 1}") } }
                    items(state.document.pageCount) { index -> ListItem(headlineContent = { Text("Page ${index + 1}") }, modifier = Modifier.clickable { contents = false; vm.page(index) }) }
                }
            } }, confirmButton = { TextButton(onClick = { targetPage?.let { contents = false; vm.page(it - 1) } }, enabled = targetPage != null) { Text("Go") } },
            dismissButton = { TextButton(onClick = { contents = false }) { Text("Close") } })
    }
}

@Composable private fun ReaderText(page: PageContent, state: ReaderPlayback, prefs: Preferences, query: String, follow: Boolean, manualScroll: () -> Unit,
    tap: (String) -> Unit, bookmark: (SourceWord) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val active = page.words.firstOrNull { it.id == state.activeWordId }
    LaunchedEffect(dragging) { if (dragging) manualScroll() }
    LaunchedEffect(active?.paragraphId, follow) {
        if (follow && active != null) {
            val index = page.paragraphs.indexOfFirst { it.id == active.paragraphId }
            if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index && it.offset >= 0 }) list.animateScrollToItem(index)
        }
    }
    LaunchedEffect(query) {
        if (query.isNotBlank()) page.paragraphs.indexOfFirst { page.reading.substring(it.start, it.end).contains(query, true) }.takeIf { it >= 0 }?.let { list.animateScrollToItem(it) }
    }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        items(page.paragraphs, key = { it.id }) { paragraph ->
            val words = page.words.filter { it.paragraphId == paragraph.id }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            LaunchedEffect(state.activeWordId, follow, layout) {
                if (follow && active?.paragraphId == paragraph.id) {
                    val measured = layout ?: return@LaunchedEffect
                    val box = measured.getBoundingBox(active.start - paragraph.start)
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == paragraph.id }
                    val y = (item?.offset ?: -10000) + box.top
                    if (y < 0 || y + box.height > list.layoutInfo.viewportEndOffset) list.animateScrollToItem(paragraph.order, (box.top - 40).toInt().coerceAtLeast(0))
                }
            }
            val text = buildAnnotatedString {
                append(page.reading.substring(paragraph.start, paragraph.end))
                words.forEach { word ->
                    val start = word.start - paragraph.start; val end = word.end - paragraph.start
                    if (word.sentenceId == active?.sentenceId) addStyle(SpanStyle(background = if (prefs.dark) Color(0xFF254D43) else SelectionBackground), start, end)
                    if (query.isNotBlank() && word.text.contains(query, true)) addStyle(SpanStyle(background = Color(0xFFFFDB75), color = Color(0xFF202020)), start, end)
                    if (word.id in state.activeWordIds) addStyle(SpanStyle(background = Accent, color = Color.White), start, end)
                    else if (word.id == state.selectedWordId) addStyle(SpanStyle(background = if (prefs.dark) Color(0xFF355B4D) else SelectionBackground), start, end)
                }
            }
            fun hit(position: androidx.compose.ui.geometry.Offset): SourceWord? {
                val measured = layout ?: return null
                val offset = measured.getOffsetForPosition(position)
                if (offset !in text.indices || !measured.getBoundingBox(offset).contains(position)) return null
                return words.firstOrNull { offset + paragraph.start in it.start until it.end }
            }
            Text(text, fontSize = (prefs.fontSize + if (paragraph.heading) 3 else 0).sp, lineHeight = (prefs.fontSize * 1.65f).sp, fontFamily = FontFamily.Serif,
                fontWeight = if (paragraph.heading) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth()
                    .pointerInput(page.id, paragraph.id) { detectTapGestures(onTap = { point -> hit(point)?.let { tap(it.id) } }, onLongPress = { point -> hit(point)?.let(bookmark) }) }
                    .semantics { customActions = listOf(CustomAccessibilityAction("Read this paragraph") { words.firstOrNull()?.let { tap(it.id) }; true }) })
        }
        if (page.words.isEmpty()) item { Text("No readable text on this page.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun PlaybackPanel(vm: ReaderViewModel, state: ReaderPlayback, notifications: () -> Unit,
    speed: Float, changeSpeed: () -> Unit, hasOriginal: Boolean, original: Boolean, toggleOriginal: () -> Unit,
    selectedWord: SourceWord?, status: String, showDetails: () -> Unit, copySelection: () -> Unit) {
    val selected = selectedWord != null && !state.wantsToPlay && !state.preparing
    val preparingPlayback = state.fillingBuffer || (state.preparing && state.currentChunk == null)
    val ready = "${state.bufferedSentences} ${if (state.bufferedSentences == 1) "sentence" else "sentences"} ready"
    val playbackStatus = when {
        state.error != null && (state.playing || state.wantsToPlay) -> "Issue ahead · Tap for details"
        state.error != null -> "Reading stopped · Tap for details"
        status != state.status -> status
        state.fillingBuffer && state.bufferedSentences < state.bufferTargetSentences -> "Preparing audio · ${state.bufferedSentences} of ${state.bufferTargetSentences} ahead"
        state.fillingBuffer -> "Preparing audio · ${formatTime(state.bufferedListeningMs)} ready"
        state.playing && state.bufferedSentences > 0 -> "Playing · $ready"
        state.playing && state.preparing -> "Playing · Preparing next sentence"
        state.playing -> "Playing"
        state.preparing -> "Preparing audio…"
        state.bufferedSentences > 0 -> "Paused · $ready"
        else -> status
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 1.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (selected) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { notifications(); vm.app.playback.start(wordId = selectedWord?.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text("Read from “${selectedWord?.text}”", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Tool(Icons.Default.ContentCopy, "Copy selected word", copySelection)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                DockAction("${String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')}×", Icons.Default.Speed, "Playback speed", changeSpeed, Modifier.weight(1f))
                IconButton(onClick = { vm.app.playback.sentence(-1) }, enabled = state.page?.words?.isNotEmpty() == true, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous sentence", Modifier.size(28.dp))
                }
                FilledIconButton(onClick = {
                    if (!state.wantsToPlay && !state.preparing) notifications()
                    if (selected) vm.app.playback.start(wordId = selectedWord?.id) else vm.app.playback.toggle()
                }, modifier = Modifier.size(64.dp).semantics {
                    contentDescription = when {
                        preparingPlayback -> "Cancel preparation"
                        state.wantsToPlay -> "Pause"
                        selected -> "Read from selected word"
                        else -> "Play"
                    }
                }, enabled = state.page?.words?.isNotEmpty() == true || state.preparing || state.wantsToPlay) {
                    Box(contentAlignment = Alignment.Center) {
                        if (preparingPlayback) CircularProgressIndicator(Modifier.size(52.dp), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .65f), strokeWidth = 2.dp)
                        Icon(when {
                            preparingPlayback -> Icons.Default.Close
                            state.wantsToPlay -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        }, null, Modifier.size(34.dp))
                    }
                }
                IconButton(onClick = {
                    if (state.blockedSentence != null) { notifications(); vm.app.playback.skipBlockedSentence() }
                    else vm.app.playback.sentence(1)
                }, enabled = state.page?.words?.isNotEmpty() == true || state.blockedSentence != null, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, if (state.blockedSentence != null) "Skip blocked sentence" else "Next sentence", Modifier.size(28.dp))
                }
                if (hasOriginal) DockAction(if (original) "Text" else "Original", if (original) Icons.Default.TextFields else Icons.Default.Description,
                    if (original) "Show reader text" else "Show original page", toggleOriginal, Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(onClickLabel = "Reading details", onClick = showDetails).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(when {
                    state.error != null -> Icons.Default.ErrorOutline
                    state.bufferedSentences > 0 && !state.fillingBuffer -> Icons.Default.CheckCircle
                    else -> Icons.Default.Headphones
                }, null, Modifier.size(14.dp), tint = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(playbackStatus, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun DockAction(text: String, icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = description }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(icon, label) }
    }
}
