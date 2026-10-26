package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.readflow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline-readable privacy information and the actual notices shipped in this build. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LegalScreen(vm: ReaderViewModel) {
    val assets = LocalContext.current.assets
    val uriHandler = LocalUriHandler.current
    var selected by remember { mutableStateOf<String?>(null) }
    val notices by produceState<List<String>>(emptyList(), assets) {
        value = withContext(Dispatchers.IO) {
            fun list(path: String): List<String> = assets.list(path).orEmpty().flatMap { name ->
                val child = "$path/$name"
                if (assets.list(child).orEmpty().isNotEmpty()) list(child) else listOf(child)
            }
            list("licenses").sorted() + "vendor/READABILITY-LICENSE.md"
        }
    }
    val privacy by produceState("Loading…", assets) {
        value = withContext(Dispatchers.IO) { assets.open("privacy-information.txt").bufferedReader().use { it.readText() } }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Privacy and licenses") }, navigationIcon = {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.screen.value = "settings" }
        })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SelectionContainer { Text(privacy, style = MaterialTheme.typography.bodyMedium) } }
            if (BuildConfig.PRIVACY_POLICY_URL.isNotEmpty()) item {
                TextButton(onClick = { runCatching { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) }
                    .onFailure { vm.message.value = "No browser could open the privacy policy." } }) { Text("Read the published privacy policy") }
            }
            item { HorizontalDivider(); Text("Third-party notices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
            items(notices, key = { it }) { path ->
                TextButton(onClick = { selected = path }, modifier = Modifier.fillMaxWidth()) {
                    Text(path.removePrefix("licenses/"), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    selected?.let { path ->
        val content by produceState("Loading…", path) {
            value = withContext(Dispatchers.IO) { runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrElse { "This notice could not be opened." } }
        }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(path.substringAfterLast('/')) },
            text = { SelectionContainer { Text(content, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
}
