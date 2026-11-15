package app.readflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** Only the current page's viewport is retained, even while its canvas is absent. */
@Stable
internal class ReaderViewportState(zoom: Float = 1f, pan: Offset = Offset.Zero) {
    var zoom by mutableFloatStateOf(zoom.takeIf { it.isFinite() }?.coerceIn(1f, 6f) ?: 1f)
    var pan by mutableStateOf(pan.takeIf { it.x.isFinite() && it.y.isFinite() } ?: Offset.Zero)

    companion object {
        val Saver = listSaver<ReaderViewportState, Float>(
            save = { listOf(it.zoom, it.pan.x, it.pan.y) },
            restore = { values ->
                values.takeIf { it.size == 3 }?.let { ReaderViewportState(it[0], Offset(it[1], it[2])) }
            },
        )
    }
}

@Composable
internal fun rememberReaderViewportState(documentId: String?, pageIndex: Int, rotation: Int, fitWidth: Boolean): ReaderViewportState =
    rememberSaveable(documentId, pageIndex, rotation, fitWidth, saver = ReaderViewportState.Saver) { ReaderViewportState() }
