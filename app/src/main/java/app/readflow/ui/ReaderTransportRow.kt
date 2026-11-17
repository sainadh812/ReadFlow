package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.readflow.playback.ReaderPlayback
import java.util.Locale

/** Stable controls in the dock and sheet; loading never adds a row. */
@Composable internal fun ReaderTransportRow(
    vm: ReaderViewModel,
    state: ReaderPlayback,
    notifications: () -> Unit,
    speed: Float,
    changeSpeed: () -> Unit,
    expanded: Boolean,
    toggleControls: () -> Unit,
    original: Boolean? = null,
    toggleOriginal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selectedWord = state.page?.words?.firstOrNull { it.id == state.selectedWordId }
    val selected = selectedWord != null && !state.wantsToPlay && !state.preparing
    ReaderTransportControls(state, speed, changeSpeed, expanded, toggleControls,
        onPrevious = { vm.app.playback.sentence(-1) },
        onPrimary = {
            if (!state.wantsToPlay && !state.preparing) notifications()
            if (selected) vm.app.playback.start(wordId = selectedWord?.id) else vm.app.playback.toggle()
        },
        onNext = {
            if (state.blockedSentence != null) { notifications(); vm.app.playback.skipBlockedSentence() }
            else vm.app.playback.sentence(1)
        }, original = original, toggleOriginal = toggleOriginal, modifier = modifier)
}

@Composable internal fun ReaderTransportControls(
    state: ReaderPlayback,
    speed: Float,
    changeSpeed: () -> Unit,
    expanded: Boolean,
    toggleControls: () -> Unit,
    onPrevious: () -> Unit,
    onPrimary: () -> Unit,
    onNext: () -> Unit,
    original: Boolean? = null,
    toggleOriginal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selected = state.page?.words?.any { it.id == state.selectedWordId } == true && !state.wantsToPlay && !state.preparing
    val preparing = state.fillingBuffer || (state.preparing && state.currentChunk == null)
    val readable = state.page?.words?.isNotEmpty() == true
    // Five 48dp targets and the 58dp primary action fit a 320dp phone without overlap.
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .padding(horizontal = if (original != null) 0.dp else 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            ReaderTransportSpeed(speed, changeSpeed)
        }
        Box(Modifier.weight(1f).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPrevious, enabled = readable, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous sentence", Modifier.size(28.dp))
            }
        }
        Box(Modifier.width(58.dp).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(onClick = onPrimary, enabled = readable || state.preparing || state.wantsToPlay,
                modifier = Modifier.size(58.dp).testTag("reader-primary-action").semantics {
                    contentDescription = when {
                        preparing -> "Cancel preparation"
                        state.wantsToPlay -> "Pause"
                        selected -> "Read from selected word"
                        else -> "Play"
                    }
                }) {
                Box(contentAlignment = Alignment.Center) {
                    if (preparing) CircularProgressIndicator(Modifier.size(48.dp).testTag("reader-preparation-spinner"),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .65f), strokeWidth = 2.dp)
                    Icon(when {
                        preparing -> Icons.Default.Close
                        state.wantsToPlay -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    }, null, Modifier.size(32.dp))
                }
            }
        }
        Box(Modifier.weight(1f).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onNext, enabled = readable || state.blockedSentence != null, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, if (state.blockedSentence != null) "Skip blocked sentence" else "Next sentence", Modifier.size(28.dp))
            }
        }
        if (original != null) Box(Modifier.weight(1f).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = toggleOriginal, modifier = Modifier.size(48.dp).testTag("reader-view-toggle").semantics {
                contentDescription = if (original) "Show reader text" else "Show original page"
                stateDescription = if (original) "Original view" else "Text view"
            }) {
                Icon(Icons.Default.TextFields, null, Modifier.size(16.dp),
                    tint = if (original) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
        Box(Modifier.weight(1f).heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = toggleControls, modifier = Modifier.size(48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) {
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    if (expanded) "Close reader controls" else "Open reader controls", Modifier.size(28.dp))
            }
        }
    }
}

@Composable private fun ReaderTransportSpeed(speed: Float, onClick: () -> Unit) {
    val label = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelWidth = measurer.measure(AnnotatedString(label), style = style, maxLines = 1).size.width
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val textFits = with(density) { labelWidth <= (maxWidth - 8.dp).toPx() }
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            contentDescription = "Playback speed, $speed times"
        }, contentPadding = PaddingValues(4.dp)) {
            if (textFits) Text(label, style = style, maxLines = 1, softWrap = false)
            else Icon(Icons.Default.Speed, null, Modifier.size(24.dp))
        }
    }
}
