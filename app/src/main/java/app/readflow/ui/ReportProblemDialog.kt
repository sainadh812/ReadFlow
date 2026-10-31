package app.readflow.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.readflow.diagnostics.IssueReport
import app.readflow.diagnostics.TechnicalDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ReportProblemDialog(
    vm: ReaderViewModel,
    issue: IssueReport? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var description by rememberSaveable(issue?.id) { mutableStateOf("") }
    var includeTechnical by rememberSaveable(issue?.id) { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val technicalReport by produceState<String?>(null, issue?.id) {
        value = null
        loadFailed = false
        try {
            val reports = if (issue != null) listOf(issue) else vm.app.issues.snapshot()
            value = withContext(Dispatchers.Default) { TechnicalDiagnostics.summary(reports) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { loadFailed = true }
    }
    val body = ReportProblemDetails.body(description, technicalReport, includeTechnical)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Feedback, null) },
        title = { Text("Report a problem") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Describe what happened without including private reading text. Review the report below, then send it from your email app.")
                OutlinedTextField(description, { description = it.take(ReportProblemDetails.DESCRIPTION_LIMIT); feedback = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("What happened? (optional)") },
                    minLines = 3, maxLines = 6,
                    supportingText = { Text("${description.length}/${ReportProblemDetails.DESCRIPTION_LIMIT} characters") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeTechnical, { includeTechnical = it; feedback = null }, enabled = technicalReport != null)
                    Text(if (issue == null) "Include technical details from up to 5 recent issues" else "Include technical details from this issue",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Technical details exclude document text, document names, URLs, audio and raw error messages. Your description is included as written. No files are attached.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (technicalReport == null) Text(if (loadFailed) "Technical details could not be loaded. You can still send your description."
                    else "Preparing the technical preview…", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Report preview", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To: ${ReportProblemDetails.SUPPORT_EMAIL}\nSubject: ${ReportProblemDetails.SUBJECT}", style = MaterialTheme.typography.bodySmall)
                        Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard == null) feedback = "Clipboard unavailable. Select and copy the report preview above."
                        else {
                            clipboard.setPrimaryClip(ClipData.newPlainText(ReportProblemDetails.SUBJECT,
                                "To: ${ReportProblemDetails.SUPPORT_EMAIL}\nSubject: ${ReportProblemDetails.SUBJECT}\n\n$body"))
                            feedback = "Report copied. Paste it into an email to ${ReportProblemDetails.SUPPORT_EMAIL}."
                        }
                    } catch (_: SecurityException) { feedback = "Clipboard access was blocked. Select and copy the report preview above." }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy report")
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text("Opening email or copying the report does not send it. Your email provider handles any message you choose to send.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = {
            val uri = Uri.parse("mailto:${ReportProblemDetails.SUPPORT_EMAIL}?subject=${Uri.encode(ReportProblemDetails.SUBJECT)}&body=${Uri.encode(body)}")
            try {
                context.startActivity(Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(ReportProblemDetails.SUPPORT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, ReportProblemDetails.SUBJECT)
                    putExtra(Intent.EXTRA_TEXT, body)
                })
                feedback = "Email draft opened. Review and send it in your email app."
            } catch (_: ActivityNotFoundException) {
                feedback = "No email app could open this draft. Copy the report and send it to ${ReportProblemDetails.SUPPORT_EMAIL}."
            } catch (_: SecurityException) {
                feedback = "Android blocked opening email. Copy the report and send it to ${ReportProblemDetails.SUPPORT_EMAIL}."
            }
        }) { Text("Send by email") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
