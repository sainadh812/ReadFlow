package app.readflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.diagnostics.IssueReport
import app.readflow.diagnostics.GitHubDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

private val issueDisplayJson = Json { prettyPrint = true; encodeDefaults = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun IssueLogsScreen(vm: ReaderViewModel) {
    val reports by vm.issueReports.collectAsStateWithLifecycle()
    val selected by vm.selectedIssue.collectAsStateWithLifecycle()
    val storageFailure by vm.app.issues.storageFailure.collectAsStateWithLifecycle()
    val github by vm.githubConfiguration.collectAsStateWithLifecycle()
    val sending by vm.sendingIssues.collectAsStateWithLifecycle()
    val githubUrl by vm.githubIssueUrl.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var exportConsent by remember { mutableStateOf(false) }
    var deleteConsent by remember { mutableStateOf(false) }
    var uploadConsent by remember { mutableStateOf(false) }
    var githubSettings by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = vm.pendingIssueExport; vm.pendingIssueExport = null
        if (uri != null && id != null) vm.exportIssue(uri, id)
    }
    fun back() { if (selected != null) vm.selectedIssue.value = null else vm.screen.value = "settings" }
    BackHandler { back() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(if (selected == null) "Error history" else "Issue details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { back() } },
            actions = {
                if (reports.isNotEmpty()) Tool(Icons.Default.SaveAlt, if (selected == null) "Export all errors" else "Export issue log") { exportConsent = true }
                if (reports.isNotEmpty()) Tool(Icons.Default.DeleteOutline, if (selected == null) "Delete all issue logs" else "Delete issue log") { deleteConsent = true }
            })
        Text("Private logs may contain document text, source URLs and generated speech.",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { uploadConsent = true }, enabled = !sending && reports.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(if (sending) "Sending errors" else "Send new errors")
            }
            if (!sending) Tool(Icons.Default.Settings, "GitHub settings") { githubSettings = true }
            githubUrl?.let { url -> Tool(Icons.AutoMirrored.Filled.OpenInNew, "Open GitHub error history") { uriHandler.openUri(url) } }
        }
        if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        storageFailure?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        val report = selected
        if (report != null) {
            val json = remember(report) { issueDisplayJson.encodeToString(IssueReport.serializer(), report) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                item { SelectionContainer { Text(json, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp) } }
            }
        } else if (reports.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No issue logs") }
        else LazyColumn(Modifier.weight(1f)) {
            items(reports, key = { it.id }) { issue ->
                val timestamp = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(issue.timestampMs))
                ListItem(headlineContent = { Text(issue.stage.replace('_', ' '), style = MaterialTheme.typography.titleSmall) },
                    overlineContent = { Text(timestamp) }, supportingContent = { Text(issue.message, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.BugReport, null) },
                    trailingContent = { if (issue.audio) Icon(Icons.Default.AudioFile, "Audio attached") },
                    modifier = Modifier.clickable { vm.readIssue(issue.id) })
                HorizontalDivider()
            }
        }
    }
    if (exportConsent) AlertDialog(onDismissRequest = { exportConsent = false }, title = { Text(if (selected == null) "Export error history?" else "Export private issue log?") },
        text = { Text(if (selected == null) "One ZIP containing a chronological JSONL log of all retained errors and captured inputs. Audio is not included. Review before sharing."
            else "The ZIP includes captured input, error details and any retained speech audio. Review it before sharing. Nothing is uploaded automatically.") },
        confirmButton = { TextButton(onClick = {
            val id = selected?.id ?: "history"
            vm.pendingIssueExport = id; exportConsent = false; picker.launch(if (id == "history") "ReadFlow-error-history.zip" else "ReadFlow-issue-${id.take(8)}.zip")
        }) { Text("Choose destination") } }, dismissButton = { TextButton(onClick = { exportConsent = false }) { Text("Cancel") } })
    if (deleteConsent) AlertDialog(onDismissRequest = { deleteConsent = false }, title = { Text(if (selected == null) "Delete all issue logs?" else "Delete this issue log?") },
        text = { Text("Previously exported copies and GitHub uploads are not deleted.") },
        confirmButton = { TextButton(onClick = { val id = selected?.id; deleteConsent = false; if (id == null) vm.clearIssues() else vm.deleteIssue(id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteConsent = false }) { Text("Cancel") } })
    if (uploadConsent) AlertDialog(onDismissRequest = { uploadConsent = false }, title = { Text("Send retained errors to GitHub?") },
        text = { Text("Destination: ${github.destination.repository}\n\n" + if (github.destination.includeInput)
            "Includes failing text excerpts and error messages. A private repository is required. No audio or original files. Uploaded copies remain on GitHub."
            else "Sanitized technical metadata only. Document text, titles, URLs, error messages and audio are excluded. Uploaded copies remain on GitHub.") },
        confirmButton = { TextButton(onClick = { uploadConsent = false; vm.sendIssues() }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { uploadConsent = false }) { Text("Cancel") } })
    if (githubSettings) GitHubSettingsDialog(vm) { githubSettings = false }
}

@Composable private fun GitHubSettingsDialog(vm: ReaderViewModel, close: () -> Unit) {
    val config by vm.githubConfiguration.collectAsStateWithLifecycle()
    var repository by remember { mutableStateOf(config.destination.repository) }
    var includeInput by remember { mutableStateOf(config.destination.includeInput) }
    var token by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = close, title = { Text("GitHub diagnostics") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(repository, { repository = it }, label = { Text("Owner/repository") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("New repository-scoped token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeInput, { includeInput = it }); Text("Include input excerpts (private repo only)", Modifier.weight(1f))
            }
            TextButton(onClick = { uriHandler.openUri("https://github.com/settings/personal-access-tokens/new") }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Create GitHub token")
            }
            TextButton(onClick = vm::forgetGitHubToken) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Remove saved token") }
        }
    }, confirmButton = { TextButton(onClick = { vm.saveGitHub(GitHubDestination(repository.trim(), includeInput), token.trim(), close) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
