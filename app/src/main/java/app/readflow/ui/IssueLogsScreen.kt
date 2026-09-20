package app.readflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.diagnostics.IssueReport
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
    var exportConsent by remember { mutableStateOf(false) }
    var deleteConsent by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = vm.pendingIssueExport; vm.pendingIssueExport = null
        if (uri != null && id != null) vm.exportIssue(uri, id)
    }
    fun back() { if (selected != null) vm.selectedIssue.value = null else vm.screen.value = "settings" }
    BackHandler { back() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(if (selected == null) "Issue logs" else "Issue details") },
            navigationIcon = { Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { back() } },
            actions = {
                if (selected != null) Tool(Icons.Default.SaveAlt, "Export issue log") { exportConsent = true }
                if (reports.isNotEmpty()) Tool(Icons.Default.DeleteOutline, if (selected == null) "Delete all issue logs" else "Delete issue log") { deleteConsent = true }
            })
        Text("Private logs may contain document text, source URLs and generated speech.",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    if (exportConsent && selected != null) AlertDialog(onDismissRequest = { exportConsent = false }, title = { Text("Export private issue log?") },
        text = { Text("The ZIP includes captured input, error details and any retained speech audio. Review it before sharing. Nothing is uploaded automatically.") },
        confirmButton = { TextButton(onClick = {
            val id = selected?.id ?: return@TextButton
            vm.pendingIssueExport = id; exportConsent = false; picker.launch("ReadFlow-issue-${id.take(8)}.zip")
        }) { Text("Choose destination") } }, dismissButton = { TextButton(onClick = { exportConsent = false }) { Text("Cancel") } })
    if (deleteConsent) AlertDialog(onDismissRequest = { deleteConsent = false }, title = { Text(if (selected == null) "Delete all issue logs?" else "Delete this issue log?") },
        text = { Text("Previously exported copies are not deleted.") },
        confirmButton = { TextButton(onClick = { val id = selected?.id; deleteConsent = false; if (id == null) vm.clearIssues() else vm.deleteIssue(id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteConsent = false }) { Text("Cancel") } })
}
