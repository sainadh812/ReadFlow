package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.ReaderLightingSettings
import app.readflow.dictionary.DictionaryEntry
import app.readflow.dictionary.DictionaryLookup
import app.readflow.dictionary.DictionaryPackPhase
import app.readflow.dictionary.DictionaryRepository
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Definitions remain separate from narration selection and the document's user data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DictionarySheet(
    repository: DictionaryRepository,
    query: String,
    lighting: ReaderLightingSettings,
    onQuery: (String) -> Unit,
    bookmark: (() -> Unit)?,
    dismiss: () -> Unit,
) {
    val pack by repository.state.collectAsStateWithLifecycle()
    val manifest = repository.manifest
    var result by remember(query) { mutableStateOf<DictionaryLookup?>(null) }
    var lookingUp by remember(query) { mutableStateOf(false) }
    var lookupError by remember(query) { mutableStateOf<String?>(null) }
    var lookupAttempt by remember { mutableIntStateOf(0) }
    var draft by rememberSaveable(query) { mutableStateOf(query) }
    var searching by rememberSaveable { mutableStateOf(query.isBlank()) }
    var moreMeanings by rememberSaveable(query) { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .82f
    val definitions = result?.entries.orEmpty()

    fun openLink(url: String) {
        linkError = !url.startsWith("https://") || runCatching { uriHandler.openUri(url) }.isFailure
    }
    fun search(value: String) {
        val word = value.trim().take(120)
        draft = word
        onQuery(word)
        lookupAttempt++
        keyboard?.hide()
    }

    LaunchedEffect(query, pack.installed, pack.phase, lookupAttempt) {
        if (!pack.installed || query.isBlank()) {
            result = null
            lookingUp = false
            return@LaunchedEffect
        }
        lookingUp = true
        lookupError = null
        try {
            result = repository.lookup(query)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            lookupError = "Could not open this definition. Try searching again."
        } finally {
            lookingUp = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ReaderBrightnessEffect(lighting)
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (managing) "English dictionary" else "Dictionary", modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!managing) IconButton(onClick = { searching = !searching; if (!searching) keyboard?.hide() }) {
                    Icon(Icons.Default.Search, if (searching) "Hide dictionary search" else "Search dictionary")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Dictionary options") }
                    DropdownMenu(menu, { menu = false }) {
                        if (bookmark != null && !managing) DropdownMenuItem(
                            text = { Text("Bookmark word in document") },
                            onClick = { menu = false; bookmark() },
                            leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                        )
                        DropdownMenuItem(text = { Text(if (managing) "Back to definition" else "Manage dictionary") },
                            onClick = { menu = false; managing = !managing },
                            leadingIcon = { Icon(Icons.Default.Download, null) })
                    }
                }
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close dictionary") }
            }

            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (searching && !managing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(120) },
                        label = { Text("English word") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(draft) }),
                        trailingIcon = {
                            IconButton(onClick = { search(draft) }, enabled = draft.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Look up word")
                            }
                        },
                    )
                    // The dialog has its own composition. Request focus only after
                    // this field is attached, and cancel when the field is removed.
                    LaunchedEffect(focus) { focus.requestFocus() }
                }

                if (managing || !pack.installed) {
                    Text(if (pack.installed) "Available offline" else "Meanings, wherever you read",
                        style = MaterialTheme.typography.titleLarge)
                    Text(if (pack.installed) "Definitions are stored on this device. Lookups work without an internet connection."
                        else "Download once, then look up English words without an internet connection.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (pack.installed) Text("${dictionarySize(pack.installedBytes)} on this device",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!pack.installed || pack.updateAvailable) Text(
                        "${if (pack.updateAvailable) "Update" else "Download"} ${dictionarySize(manifest.downloadBytes)} · ${manifest.name}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pack.phase == DictionaryPackPhase.CHECKING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Checking dictionary…", style = MaterialTheme.typography.bodyMedium)
                    } else if (pack.busy) {
                        if (pack.phase == DictionaryPackPhase.DOWNLOADING && pack.total > 0) {
                            LinearProgressIndicator(progress = { (pack.received.toFloat() / pack.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${dictionarySize(pack.received)} of ${dictionarySize(pack.total)}", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (pack.phase == DictionaryPackPhase.INSTALLING) "Installing dictionary…" else "Downloading dictionary…",
                                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = repository::cancelInstall) { Text("Cancel") }
                        }
                    } else {
                        pack.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!pack.installed || pack.updateAvailable) Button(onClick = repository::install, modifier = Modifier.fillMaxWidth()) {
                                Text(if (pack.error != null) "Retry" else if (pack.updateAvailable) "Update dictionary" else "Download dictionary")
                            }
                            if (pack.installed) TextButton(onClick = repository::remove) { Text("Remove download") }
                        }
                    }
                    if (managing) {
                        Text(manifest.credits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { managing = false }) { Text("Back to definition") }
                    }
                } else if (query.isBlank()) {
                    Text("Look up an English word", style = MaterialTheme.typography.titleLarge)
                    Text("Search here, or long press a word while reading.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (lookingUp || (result == null && lookupError == null)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Looking up “$query”…", style = MaterialTheme.typography.bodyMedium)
                } else if (lookupError != null || result?.error != null) {
                    Text(lookupError ?: result?.error.orEmpty(), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { searching = true }) { Text("Search another word") }
                } else if (definitions.isEmpty()) {
                    Text(query, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                    Text("No definition found", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text("Check the spelling or try another word.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result?.suggestions?.forEach { suggestion ->
                        TextButton(onClick = { search(suggestion) }) { Text(suggestion) }
                    }
                    if (!searching) TextButton(onClick = { searching = true }) { Text("Edit word") }
                } else {
                    val shown = if (moreMeanings) definitions else definitions.take(1)
                    shown.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DictionaryMeaning(entry)
                    }
                    if (definitions.size > 1) TextButton(onClick = { moreMeanings = !moreMeanings }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (moreMeanings) "Fewer meanings" else "More meanings (${definitions.size - 1})")
                        Spacer(Modifier.width(4.dp))
                        Icon(if (moreMeanings) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openLink(definitions.firstOrNull()?.sourceUrl ?: "https://en.wiktionary.org/") },
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("Wiktionary", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp))
                    }
                    TextButton(onClick = { openLink(manifest.licenseUrl) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("License", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (linkError) Text("Could not open the link on this device.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable private fun DictionaryMeaning(entry: DictionaryEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(entry.headword, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif,
            modifier = Modifier.semantics { heading() })
        Text(listOf(entry.pos, entry.ipa).filter { it.isNotBlank() }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium)
        if (entry.labels.isNotBlank()) Text(entry.labels, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.definition, style = MaterialTheme.typography.bodyLarge)
        if (entry.example.isNotBlank()) Text("“${entry.example}”", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dictionarySize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
