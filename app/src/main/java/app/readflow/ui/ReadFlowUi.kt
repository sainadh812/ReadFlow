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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.core.*
import app.readflow.data.Preferences
import app.readflow.playback.ReaderPlayback
import java.util.Locale

private val Blue = Color(0xFF1554D1)
private val LightBlue = Color(0xFFDCEAFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReadFlowUi(vm: ReaderViewModel, requestNotifications: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var urlDialog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    BackHandler(screen != "library") { vm.screen.value = if (screen == "reader" || playback.document == null) "library" else "reader" }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    MaterialTheme(colorScheme = if (prefs.dark) darkColorScheme(primary = Color(0xFFAEC6FF), secondary = Color(0xFF77D3BC), surface = Color(0xFF15171B), background = Color(0xFF111317))
        else lightColorScheme(primary = Blue, secondary = Color(0xFF16745C), surface = Color.White, background = Color(0xFFF8F9FC)),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 0.sp))) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when (screen) {
                    "reader" -> Reader(vm, playback, prefs, requestNotifications)
                    "voices" -> Voices(vm)
                    "settings" -> Settings(vm, prefs)
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("ReadFlow") }, actions = {
            Tool(Icons.Default.RecordVoiceOver, "Voice models") { vm.screen.value = "voices" }
            Tool(Icons.Default.Settings, "Settings") { vm.screen.value = "settings" }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = import, enabled = !importing) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Import file") }
            OutlinedButton(onClick = url, enabled = !importing) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Webpage") }
        }
        if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Importing", Modifier.padding(20.dp)) }
        if (documents.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp)); Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.sample() }) { Text("Open sample document") }
        } else LazyColumn(Modifier.weight(1f)) {
            items(documents, key = { it.id }) { doc ->
                ListItem(headlineContent = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"} · ${if (doc.mime == "application/pdf") "PDF" else if (doc.mime.startsWith("image")) "Image" else "Article"}") },
                    leadingContent = { Icon(if (doc.mime == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Description, null) },
                    trailingContent = { Tool(Icons.Default.DeleteOutline, "Delete ${doc.title}") { vm.deleteDocument(doc) } },
                    modifier = Modifier.clickable { vm.open(doc) })
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Reader(vm: ReaderViewModel, state: ReaderPlayback, prefs: Preferences, requestNotifications: () -> Unit) {
    KeepReaderScreenAwake()
    val refreshingText by vm.refreshingText.collectAsStateWithLifecycle()
    val hasOriginal = state.document?.mime == "application/pdf" || state.document?.mime?.startsWith("image/") == true
    var original by remember(state.document?.id) { mutableStateOf(hasOriginal) }
    var contents by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var speeds by remember { mutableStateOf(false) }
    var pageOnly by rememberSaveable(state.document?.id) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var follow by remember(state.document?.id) { mutableStateOf(true) }
    val page = state.page
    val pageIndex = page?.index ?: state.viewPageIndex
    var rotation by remember(state.document?.id, pageIndex) { mutableIntStateOf(0) }
    var fitWidth by remember(state.document?.id, pageIndex) { mutableStateOf(true) }
    val selected = page?.words?.firstOrNull { it.id == state.selectedWordId }
    val clipboard = LocalClipboardManager.current
    val status = if (refreshingText) "Updating text recognition…" else state.error ?: state.status
    BackHandler(pageOnly) { pageOnly = false }
    // Reveal recovery controls if playback stops on an error while the chrome is hidden.
    LaunchedEffect(state.error, state.blockedSentence) {
        if (state.error != null || state.blockedSentence != null) pageOnly = false
    }
    Column(Modifier.fillMaxSize()) {
        // Keep persistent chrome to two compact rows so the document gets the remaining height.
        if (!pageOnly) Surface {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.AutoMirrored.Filled.ArrowBack, "Library") { vm.screen.value = "library" }
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Reading details") { details = true }.padding(vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(state.document?.title ?: "Reader", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (original && (state.document?.pageCount ?: 1) > 1) {
                    IconButton(onClick = { vm.page(pageIndex - 1) }, enabled = pageIndex > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }
                }
                TextButton(onClick = { contents = true }, modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of ${state.document?.pageCount ?: 1}. Contents and bookmarks" }) {
                    Text("${pageIndex + 1} / ${state.document?.pageCount ?: 1}", maxLines = 1)
                }
                if (original && (state.document?.pageCount ?: 1) > 1) {
                    IconButton(onClick = { vm.page(pageIndex + 1) }, enabled = pageIndex + 1 < (state.document?.pageCount ?: 1)) { Icon(Icons.Default.ChevronRight, "Next page") }
                }
                Tool(Icons.Default.Fullscreen, "Hide controls for more page space") { searching = false; pageOnly = true }
                Box {
                    Tool(Icons.Default.MoreVert, "Reader menu") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        if (selected != null && !state.wantsToPlay) {
                            DropdownMenuItem(text = { Text("Read from selected word") }, onClick = { menu = false; requestNotifications(); vm.app.playback.start(wordId = selected.id) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) })
                            DropdownMenuItem(text = { Text("Copy selected word") }, onClick = { clipboard.setText(AnnotatedString(selected.text)); menu = false }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                            DropdownMenuItem(text = { Text("Bookmark selected word") }, onClick = { vm.bookmark(selected); menu = false }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) })
                        }
                        DropdownMenuItem(text = { Text("Contents and bookmarks") }, onClick = { contents = true; menu = false }, leadingIcon = { Icon(Icons.Default.List, null) })
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
                        DropdownMenuItem(text = { Text("Text and display") }, onClick = { vm.screen.value = "settings"; menu = false }, leadingIcon = { Icon(Icons.Default.TextFields, null) })
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
        }
        if (!pageOnly) PlaybackPanel(vm, state, requestNotifications, follow) { follow = true }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Reading details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
                    if (word.sentenceId == active?.sentenceId) addStyle(SpanStyle(background = if (prefs.dark) Color(0xFF243D60) else LightBlue), start, end)
                    if (query.isNotBlank() && word.text.contains(query, true)) addStyle(SpanStyle(background = Color(0xFFFFDB75), color = Color(0xFF202020)), start, end)
                    if (word.id in state.activeWordIds) addStyle(SpanStyle(background = Blue, color = Color.White), start, end)
                    else if (word.id == state.selectedWordId) addStyle(SpanStyle(background = if (prefs.dark) Color(0xFF324564) else LightBlue), start, end)
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
    follow: Boolean, resumeFollowing: () -> Unit) {
    val selected = state.selectedWordId != null && !state.wantsToPlay
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.Default.SkipPrevious, "Previous sentence") { vm.app.playback.sentence(-1) }
            FilledIconButton(onClick = {
                notifications()
                if (selected) vm.app.playback.start(wordId = state.selectedWordId) else vm.app.playback.toggle()
            }, modifier = Modifier.size(48.dp).semantics {
                contentDescription = when {
                    state.preparing && state.currentChunk == null -> "Cancel preparation"
                    state.wantsToPlay -> "Pause"
                    selected -> "Read from selected word"
                    else -> "Play"
                }
            }, enabled = state.page?.words?.isNotEmpty() == true) {
                if (state.preparing && state.currentChunk == null) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Icon(if (state.wantsToPlay) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(30.dp))
            }
            Tool(Icons.Default.SkipNext, if (state.blockedSentence != null) "Skip blocked sentence" else "Next sentence") {
                if (state.blockedSentence != null) { notifications(); vm.app.playback.skipBlockedSentence() }
                else vm.app.playback.sentence(1)
            }
            if (!follow) Tool(Icons.Default.MyLocation, "Follow reading", resumeFollowing)
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
