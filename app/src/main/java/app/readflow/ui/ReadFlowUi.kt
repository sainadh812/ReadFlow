package app.readflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import app.readflow.data.AppTheme
import app.readflow.playback.ReaderPlayback
import app.readflow.playback.failedSentenceIds
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReadFlowUi(vm: ReaderViewModel, requestNotifications: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val readerPaper = remember(prefs.readerTheme) { ReaderPapers.selected(prefs.readerTheme) }
    val appTheme = AppTheme.fromId(prefs.appTheme)
    ReaderSystemBarAppearance(if (screen == "reader") readerPaper.dark else appTheme.dark)
    var urlDialog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    BackHandler(screen != "library") { vm.back() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    AppThemeAppearance(appTheme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (screen in setOf("library", "voices", "settings")) LibraryNavigation(screen, vm::navigate) },
            containerColor = if (screen == "reader") readerPaper.chrome else MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                when (screen) {
                    "reader" -> ReaderTheme(prefs.readerTheme) { Reader(vm, playback, prefs, requestNotifications) }
                    "voices" -> Voices(vm)
                    "settings" -> Settings(vm, prefs)
                    "legal" -> LegalScreen(vm)
                    "ocr" -> OcrSettingsScreen(vm, prefs)
                    "benchmark" -> BenchmarkScreen(vm, prefs)
                    "issues" -> IssueLogsScreen(vm)
                    else -> LibraryScreen(vm, appTheme, { picker.launch(arrayOf("application/pdf", "image/*")) }, { urlDialog = true }, requestNotifications)
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

@Composable private fun LibraryNavigation(screen: String, navigate: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(
            Triple("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
            Triple("voices", "Voices", Icons.Default.Headphones),
            Triple("settings", "Settings", Icons.Default.Tune),
        ).forEach { (route, label, icon) ->
            NavigationBarItem(selected = screen == route, onClick = { navigate(route) },
                icon = { Icon(icon, null) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Reader(vm: ReaderViewModel, state: ReaderPlayback, prefs: Preferences, requestNotifications: () -> Unit) {
    KeepReaderScreenAwake()
    val lighting = rememberReaderLighting(vm, prefs)
    ReaderBrightnessEffect(lighting.settings)
    val refreshingText by vm.refreshingText.collectAsStateWithLifecycle()
    val ocrProgress by vm.ocrProgress.collectAsStateWithLifecycle()
    val hasOriginal = state.document?.mime == "application/pdf" || state.document?.mime?.startsWith("image/") == true
    var original by rememberSaveable(state.document?.id) { mutableStateOf(hasOriginal) }
    var contents by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var speeds by remember { mutableStateOf(false) }
    var preparePages by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    var readerControls by remember(state.document?.id) { mutableStateOf(false) }
    var dictionaryOpen by rememberSaveable(state.document?.id) { mutableStateOf(false) }
    var dictionaryQuery by rememberSaveable(state.document?.id) { mutableStateOf("") }
    var dictionaryWordId by rememberSaveable(state.document?.id) { mutableStateOf<String?>(null) }
    var pageOnly by rememberSaveable(state.document?.id) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var follow by rememberSaveable(state.document?.id) { mutableStateOf(true) }
    val page = state.page
    val pageIndex = page?.index ?: state.viewPageIndex
    var originalReady by remember(state.document?.id, pageIndex, original) { mutableStateOf(false) }
    val pageTurn = rememberPageTurnState((state.document?.id to original) to pageIndex, pageIndex,
        state.document?.pageCount ?: 1, prefs.pageTurnStyle, enabled = !readerControls && !appearance && !contents && !details && !speeds && !preparePages && !dictionaryOpen,
        rightAdvances = prefs.swipeRightAdvances, onTurn = { delta -> follow = false; vm.page(pageIndex + delta) })
    val adjacentPages = rememberAdjacentReaderPages(vm, state.document, pageIndex, prefs.pageTurnStyle != "off", state.failedSentences)
    var rotation by rememberSaveable(state.document?.id, pageIndex) { mutableIntStateOf(0) }
    var fitWidth by rememberSaveable(state.document?.id, pageIndex) { mutableStateOf(true) }
    // These states outlive the conditional PDF/text surfaces, but only retain the current page.
    val originalViewport = rememberReaderViewportState(state.document?.id, pageIndex, rotation, fitWidth)
    val textViewport = rememberSaveable(state.document?.id, pageIndex, saver = LazyListState.Saver) { LazyListState() }
    val selected = page?.words?.firstOrNull { it.id == state.selectedWordId }
    val clipboard = LocalClipboardManager.current
    val status = ocrProgress ?: if (refreshingText) "Updating text recognition…" else state.error ?: state.status
    fun showOriginalPage(showOriginal: Boolean) {
        if (!hasOriginal || original == showOriginal) return
        pageTurn.reset()
        original = showOriginal
        if (showOriginal) searching = false
    }
    fun pauseForDictionary() {
        val current = vm.app.playback.state.value
        // Initial page extraction and silent prepare-ahead are not narration.
        if (current.playing || current.wantsToPlay || current.fillingBuffer) vm.app.playback.pauseFromControls()
    }
    fun openDictionary(word: SourceWord? = null) {
        // Lookup never changes playback selection or the durable reading position.
        pauseForDictionary()
        pageTurn.reset()
        menu = false
        readerControls = false
        appearance = false
        contents = false
        details = false
        speeds = false
        preparePages = false
        dictionaryQuery = word?.text.orEmpty()
        dictionaryWordId = word?.id
        dictionaryOpen = true
    }
    // A saved sheet restored after rotation must remain a paused reading surface, too.
    LaunchedEffect(dictionaryOpen) { if (dictionaryOpen) pauseForDictionary() }
    BackHandler(pageOnly && !dictionaryOpen) { pageOnly = false }
    // Reveal recovery controls if playback stops on an error while the chrome is hidden.
    LaunchedEffect(state.error, state.blockedSentence) {
        if (state.error != null || state.blockedSentence != null) pageOnly = false
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Keep navigation in the header and listening controls in one dock; the page owns the remaining height.
        if (!pageOnly) Surface {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.back() }
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Contents and bookmarks") { contents = true }.padding(vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(state.document?.title ?: "Reader", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Page ${pageIndex + 1} of ${state.document?.pageCount ?: 1}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { appearance = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Reader appearance: paper, font and size" }) {
                    Text("Aa", fontFamily = FontFamily.Serif, fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
                }
                Box {
                    Tool(Icons.Default.MoreVert, "Reader menu") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reader controls") }, onClick = { readerControls = true; menu = false }, leadingIcon = { Icon(Icons.Default.Tune, null) })
                        DropdownMenuItem(text = { Text("Paper, font and size") }, onClick = { appearance = true; menu = false }, leadingIcon = { Icon(Icons.Default.TextFields, null) })
                        DropdownMenuItem(text = { Text("Dictionary") }, onClick = { openDictionary() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) })
                        if (selected != null) {
                            DropdownMenuItem(text = { Text("Define selected word") }, onClick = { openDictionary(selected) }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        }
                        DropdownMenuItem(text = { Text("Hide controls for more page space") }, onClick = { searching = false; pageOnly = true; menu = false }, leadingIcon = { Icon(Icons.Default.Fullscreen, null) })
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
                            DropdownMenuItem(text = { Text("Previous page") }, onClick = { pageTurn.requestTurn(-1); menu = false }, enabled = pageIndex > 0, leadingIcon = { Icon(Icons.Default.ChevronLeft, null) })
                            DropdownMenuItem(text = { Text("Next page") }, onClick = { pageTurn.requestTurn(1); menu = false }, enabled = pageIndex + 1 < (state.document?.pageCount ?: 1), leadingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        }
                        if (hasOriginal) {
                            DropdownMenuItem(text = { Text(if (original) "Show reader text" else "Show original page") }, onClick = { showOriginalPage(!original); menu = false }, leadingIcon = { Icon(if (original) Icons.Default.TextFields else Icons.Default.Description, null) })
                            if (original) {
                                DropdownMenuItem(text = { Text(if (fitWidth) "Fit whole page" else "Fit page width") }, onClick = { pageTurn.reset(); fitWidth = !fitWidth; menu = false }, leadingIcon = { Icon(Icons.Default.FitScreen, null) })
                                DropdownMenuItem(text = { Text("Rotate page view") }, onClick = { pageTurn.reset(); rotation = (rotation + 90) % 360; menu = false }, leadingIcon = { Icon(Icons.Default.RotateRight, null) })
                            }
                        }
                        if ((state.document?.pageCount ?: 0) > 1) {
                            DropdownMenuItem(text = { Text(if (prefs.swipePages) "Page swipes: on" else "Page swipes: off") }, onClick = { vm.update(prefs.copy(swipePages = !prefs.swipePages)); menu = false })
                        }
                        DropdownMenuItem(text = { Text("Find in page text") }, onClick = { showOriginalPage(false); searching = true; menu = false }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        DropdownMenuItem(text = { Text("Playback speed · ${String.format(Locale.US, "%.2f×", prefs.speed)}") }, onClick = { speeds = true; menu = false }, leadingIcon = { Icon(Icons.Default.Speed, null) })
                        DropdownMenuItem(text = { Text("Back ten seconds") }, onClick = { vm.app.playback.skip(-10); menu = false }, leadingIcon = { Icon(Icons.Default.Replay10, null) })
                        DropdownMenuItem(text = { Text("Forward ten seconds") }, onClick = { vm.app.playback.skip(10); menu = false }, leadingIcon = { Icon(Icons.Default.Forward10, null) })
                        DropdownMenuItem(text = { Text("Voice models") }, onClick = { vm.navigate("voices"); menu = false }, leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) })
                        DropdownMenuItem(text = { Text(if (follow) "Stop following reading" else "Follow reading") }, onClick = { follow = !follow; menu = false }, leadingIcon = { Icon(Icons.Default.MyLocation, null) })
                        DropdownMenuItem(text = { Text(if (prefs.showPerformance) "Hide performance" else "Show performance") }, onClick = { vm.update(prefs.copy(showPerformance = !prefs.showPerformance)); menu = false }, leadingIcon = { Icon(Icons.Default.Speed, null) })
                        DropdownMenuItem(text = { Text("Text recognition · ${modelDisplayName(prefs.ocrEngine)}") }, onClick = { vm.navigate("ocr"); menu = false }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) })
                        DropdownMenuItem(text = { Text("Phone testbench") }, onClick = { vm.navigate("benchmark"); menu = false }, leadingIcon = { Icon(Icons.Default.Science, null) })
                        DropdownMenuItem(text = { Text("Settings and appearance") }, onClick = { vm.navigate("settings"); menu = false }, leadingIcon = { Icon(Icons.Default.Tune, null) })
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
        Box(Modifier.weight(1f).fillMaxWidth().readerPaper(LocalReaderPaper.current)) {
            PageTurnViewport(pageTurn, contentReady = if (original) originalReady else page != null || state.error != null,
                modifier = Modifier.fillMaxSize().readerWarmLight(lighting.settings), preview = { target ->
                    val previewPage = adjacentPages[target]
                    val failedIds = remember(previewPage, state.failedSentences) {
                        previewPage?.let { state.failedSentenceIds(it) }.orEmpty()
                    }
                    if (original) OriginalPageTurnPreview(pageTurn, target, rotation, fitWidth, previewPage, failedIds)
                    else ReaderTextTurnPreview(previewPage, prefs, target, failedIds)
                }) {
                if (original) OriginalPage(vm, state, Modifier.fillMaxSize(), follow && !dictionaryOpen, rotation, fitWidth,
                    prefs.swipePages && !readerControls && !dictionaryOpen, prefs.swipeRightAdvances,
                    pageTurn = pageTurn, onReady = { originalReady = it }, define = { openDictionary(it) },
                    lookupWordId = dictionaryWordId.takeIf { dictionaryOpen }, viewportState = originalViewport) { follow = false }
                else if (page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.error != null) Text(state.error, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator()
                }
                else key(page.id) {
                    ReaderText(page, state, prefs, if (searching) query else "", follow && !dictionaryOpen, { follow = false }, { vm.app.playback.select(it) }, { openDictionary(it) },
                        dictionaryWordId.takeIf { dictionaryOpen }, textViewport,
                        Modifier.fillMaxSize().readerPageSwipes(page.id, prefs.swipePages && !readerControls && !dictionaryOpen, prefs.swipeRightAdvances,
                            manualScroll = { follow = false }, turn = { vm.page(pageIndex + it) },
                            onDrag = pageTurn::drag, onRelease = pageTurn::release, onCancel = pageTurn::cancel))
                }
            }
            if (pageOnly) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                Tool(Icons.Default.FullscreenExit, "Show reader controls") { pageOnly = false }
            }
            if (!follow && !pageOnly) FilledTonalButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Follow reading")
            }
        }
        if (!pageOnly) PlaybackPanel(vm, state, prefs, requestNotifications, { speeds = true }, status,
            recognizing = refreshingText || ocrProgress != null,
            originalLabel = if (hasOriginal) { if (state.document?.mime == "application/pdf") "PDF" else "Image" } else null,
            original = original, changeView = ::showOriginalPage,
            showDetails = { details = true },
            showControls = { readerControls = true })
    }
    if (dictionaryOpen) {
        val lookupWord = page?.words?.firstOrNull { it.id == dictionaryWordId }
        DictionarySheet(vm.app.dictionary, dictionaryQuery, lighting.settings,
            onQuery = { dictionaryQuery = it; dictionaryWordId = null },
            bookmark = lookupWord?.let { word -> { vm.bookmark(word) } },
            dismiss = { dictionaryOpen = false })
    }
    if (readerControls) ReaderControlsSheet(vm, state, prefs, lighting = lighting, dismiss = { readerControls = false }, notifications = requestNotifications)
    if (appearance) ReaderAppearanceSheet(prefs, vm::update, lighting.settings) { appearance = false }
    if (preparePages && state.document != null) {
        var endPage by remember(state.document.id, pageIndex) { mutableStateOf(minOf(pageIndex + 10, state.document.pageCount).toString()) }
        val end = endPage.toIntOrNull()?.takeIf { it in pageIndex + 1..state.document.pageCount }
        AlertDialog(onDismissRequest = { preparePages = false }, title = { Text("Prepare pages before listening") }, text = {
            ReaderBrightnessEffect(lighting.settings)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save speech from page ${pageIndex + 1} through the page you choose. For a chapter, enter its last page. Listening starts when you press Play.")
                OutlinedTextField(endPage, { endPage = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text("Last page") }, suffix = { Text("/ ${state.document.pageCount}") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Text("Keep Read Assist open while preparing. Completed audio is kept if you cancel. Preparation stops before exceeding your ${prefs.cacheMb} MB audio cache.",
                    style = MaterialTheme.typography.bodySmall)
                if (prefs.ocrEngine == "teleocr") Text("New TeleOCR pages can take several minutes each.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { end?.let { preparePages = false; vm.app.playback.prepareAhead(it - 1) } }, enabled = end != null) { Text("Prepare") } },
            dismissButton = { TextButton(onClick = { preparePages = false }) { Text("Cancel") } })
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Reading details") }, text = {
        ReaderBrightnessEffect(lighting.settings)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            state.prepareAheadProgress?.let { progress ->
                Text(progress.status)
                Text("${progress.completedPages}/${progress.totalPages} pages · ${formatTime(progress.audioMs)} audio ready")
                if (progress.running) {
                    LinearProgressIndicator(progress = { progress.completedPages.toFloat() / progress.totalPages.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.app.playback.stop() }) { Text("Cancel preparation") }
                }
            }
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
        ReaderBrightnessEffect(lighting.settings)
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
            text = { ReaderBrightnessEffect(lighting.settings); Column {
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
    tap: (String) -> Unit, define: (SourceWord) -> Unit, lookupWordId: String?, list: LazyListState, modifier: Modifier) {
    val paper = LocalReaderPaper.current
    val failedIds = remember(page, state.failedSentences) { state.failedSentenceIds(page) }
    val readerFont = ReaderFonts.family(prefs.readerFont)
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val active = page.words.firstOrNull { it.id == state.activeWordId }
    val onTap by rememberUpdatedState(tap)
    val onDefine by rememberUpdatedState(define)
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
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = list, modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 28.dp),
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
                        if (word.sentenceId == active?.sentenceId) addStyle(SpanStyle(background = paper.sentence), start, end)
                        if (query.isNotBlank() && word.text.contains(query, true)) addStyle(SpanStyle(background = Color(0xFFFFDB75), color = Color(0xFF202020)), start, end)
                        if (word.id in state.activeWordIds) addStyle(SpanStyle(background = paper.accent, color = paper.onAccent), start, end)
                        else if (word.id == state.selectedWordId) addStyle(SpanStyle(background = paper.sentence), start, end)
                        if (word.id == lookupWordId) addStyle(SpanStyle(background = paper.sentence, color = paper.ink), start, end)
                        if (word.sentenceId in failedIds) addStyle(SpanStyle(color = ReaderFailureInk,
                            background = ReaderFailureBackground), start, end)
                    }
                }
                fun hit(position: androidx.compose.ui.geometry.Offset): SourceWord? {
                    val measured = layout ?: return null
                    val offset = measured.getOffsetForPosition(position)
                    if (offset !in text.indices || !measured.getBoundingBox(offset).contains(position)) return null
                    return words.firstOrNull { offset + paragraph.start in it.start until it.end }
                }
                Text(text, fontSize = (prefs.fontSize + if (paragraph.heading) 3 else 0).sp, lineHeight = (prefs.fontSize * 1.65f).sp, fontFamily = readerFont,
                    color = paper.ink,
                    fontWeight = if (paragraph.heading) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth()
                        .pointerInput(page.id, paragraph.id) { detectTapGestures(onTap = { point -> hit(point)?.let { onTap(it.id) } }, onLongPress = { point -> hit(point)?.let(onDefine) }) }
                        .semantics { customActions = listOf(CustomAccessibilityAction("Read this paragraph") { words.firstOrNull()?.let { tap(it.id) }; true }) })
            }
            if (page.words.isEmpty()) item { Text("No readable text on this page.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun PlaybackPanel(vm: ReaderViewModel, state: ReaderPlayback, prefs: Preferences, notifications: () -> Unit,
    changeSpeed: () -> Unit, status: String, recognizing: Boolean,
    originalLabel: String?, original: Boolean, changeView: (Boolean) -> Unit,
    showDetails: () -> Unit, showControls: () -> Unit) {
    val progress = state.prepareAheadProgress
    val playbackStatus = when {
        state.error != null && (state.playing || state.wantsToPlay) -> "Issue ahead"
        state.error != null -> "Reading stopped"
        state.blockedSentence != null -> "Sentence needs attention"
        status != state.status -> status
        progress?.running == true -> "Preparing pages…"
        state.fillingBuffer || state.preparing -> "Preparing audio…"
        state.playing || state.wantsToPlay -> "Listening"
        progress != null -> progress.status
        else -> "Ready"
    }
    ReaderFloatingDock(status = playbackStatus, busy = state.preparing || state.fillingBuffer || progress?.running == true || recognizing,
        hasIssue = state.error != null || state.blockedSentence != null,
        originalLabel = originalLabel, original = original, changeView = changeView,
        showDetails = showDetails, showControls = showControls,
        progress = progress?.takeIf { it.running }?.let { it.completedPages.toFloat() / it.totalPages.coerceAtLeast(1) },
        performance = if (prefs.showPerformance) { { PerformancePanel(vm, prefs) } } else null) {
        ReaderTransportRow(vm, state, notifications, prefs.speed, changeSpeed,
            expanded = false, toggleControls = showControls)
    }
}

private fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(icon, label) }
    }
}
