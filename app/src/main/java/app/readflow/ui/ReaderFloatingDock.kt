package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Audio state only changes content inside reserved slots, never the reader's available height. */
@Composable internal fun ReaderFloatingDock(
    status: String,
    busy: Boolean,
    hasIssue: Boolean,
    originalLabel: String?,
    original: Boolean,
    changeView: (Boolean) -> Unit,
    showDetails: () -> Unit,
    showControls: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    performance: (@Composable () -> Unit)? = null,
    transport: @Composable () -> Unit,
) {
    val openControls by rememberUpdatedState(showControls)
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("reader-floating-card").pointerInput(Unit) {
                var travel = 0f
                var opened = false
                detectVerticalDragGestures(onDragStart = { travel = 0f; opened = false }, onVerticalDrag = { change, amount ->
                    travel += amount
                    if (!opened && travel < -28.dp.toPx()) { opened = true; openControls() }
                    change.consume()
                })
            },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                // A rail is always present, including when idle. It never adds another row.
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 4.dp)
                    .height(2.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))) {
                    if (busy) {
                        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxSize())
                    }
                }
                performance?.let { metrics ->
                    metrics()
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val modeWidth = (184.dp * fontScale).coerceAtMost(maxWidth)
                    // Adapt only to configuration, never the current status text or loading state.
                    val inline = originalLabel != null && maxWidth >= modeWidth + 88.dp * fontScale + 8.dp
                    if (inline) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderViewSelector(originalLabel!!, original, changeView, Modifier.width(modeWidth))
                        DockStatus(status, hasIssue, showDetails, Modifier.weight(1f))
                    } else Column {
                        if (originalLabel != null) ReaderViewSelector(originalLabel, original, changeView,
                            Modifier.width(modeWidth).align(Alignment.CenterHorizontally))
                        DockStatus(status, hasIssue, showDetails, Modifier.fillMaxWidth())
                    }
                }
                transport()
            }
        }
    }
}

@Composable private fun DockStatus(status: String, hasIssue: Boolean, showDetails: () -> Unit, modifier: Modifier) {
    Row(modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Reading details", onClick = showDetails)
        .semantics { contentDescription = "Reading status: $status" }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            minLines = 1, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            color = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (hasIssue) Icons.Default.ErrorOutline else Icons.Default.ChevronRight, null, Modifier.size(16.dp),
            tint = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReaderViewSelector(originalLabel: String, original: Boolean, changeView: (Boolean) -> Unit, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.selectableGroup().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("Text", originalLabel).forEachIndexed { index, label ->
                val selected = original == (index == 1)
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(7.dp),
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { changeView(index == 1) })
                        .semantics { contentDescription = "$label view" }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(if (index == 0) Icons.AutoMirrored.Filled.Subject else Icons.Default.Description,
                            null, Modifier.size(18.dp), tint = tint)
                        Spacer(Modifier.width(5.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
