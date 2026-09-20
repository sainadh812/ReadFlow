package app.readflow.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.readflow.core.Transform
import app.readflow.playback.ReaderPlayback
import kotlinx.coroutines.CancellationException

@Composable internal fun OriginalPage(vm: ReaderViewModel, state: ReaderPlayback, modifier: Modifier, follow: Boolean, manual: () -> Unit) {
    val page = state.page
    val document = state.document ?: return
    val pageIndex = page?.index ?: state.viewPageIndex
    val pageKey = document.id to pageIndex
    var bitmap by remember(pageKey) { mutableStateOf<Bitmap?>(null) }
    var error by remember(pageKey) { mutableStateOf<String?>(null) }
    var zoom by remember(pageKey) { mutableFloatStateOf(1f) }
    var pan by remember(pageKey) { mutableStateOf(Offset.Zero) }
    var rotation by remember(pageKey) { mutableIntStateOf(0) }
    var fitWidth by remember(pageKey) { mutableStateOf(true) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pageKey, document.localPath) {
        try { bitmap = vm.app.documents.extractor.render(document.localPath, pageIndex) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message }
    }
    DisposableEffect(pageKey) { onDispose { bitmap?.recycle(); bitmap = null } }
    val width = page?.width?.takeIf { it > 0 } ?: bitmap?.width?.toFloat() ?: 1f
    val height = page?.height?.takeIf { it > 0 } ?: bitmap?.height?.toFloat() ?: 1f
    val swapped = rotation % 180 != 0
    val fit = (if (fitWidth) size.width / (if (swapped) height else width)
        else minOf(size.width / (if (swapped) height else width), size.height / (if (swapped) width else height))).coerceAtLeast(.001f)
    val scale = fit * zoom
    val offsetX = (size.width - (if (swapped) height else width) * fit) / 2 + pan.x
    val offsetY = ((size.height - (if (swapped) width else height) * fit) / 2).coerceAtLeast(0f) + pan.y
    val transform = when (rotation) {
        90 -> Transform(0f, scale, -scale, 0f, offsetX + height * scale, offsetY)
        180 -> Transform(-scale, 0f, 0f, -scale, offsetX + width * scale, offsetY + height * scale)
        270 -> Transform(0f, -scale, scale, 0f, offsetX, offsetY + width * scale)
        else -> Transform(scale, 0f, 0f, scale, offsetX, offsetY)
    }
    val currentTransform by rememberUpdatedState(transform)
    val currentBaseOffset by rememberUpdatedState(Offset(offsetX - pan.x, offsetY - pan.y))
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.activeWordId, follow) {
        if (follow) page?.words?.firstOrNull { it.id == state.activeWordId }?.boxes?.firstOrNull()?.let { box ->
            val point = transform.map((box.left + box.right) / 2, (box.top + box.bottom) / 2)
            if (point.second !in 40f..(size.height - 50f)) pan += Offset(0f, size.height / 2f - point.second)
        }
    }
    Box(modifier.padding(8.dp).clipToBounds()) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(pageKey) { detectTransformGestures { centroid, delta, factor, _ ->
                manual()
                val next = (zoom * factor).coerceIn(1f, 6f)
                val ratio = next / zoom
                pan = (pan + currentBaseOffset - centroid) * ratio + centroid - currentBaseOffset + delta
                zoom = next
            } }
            .pointerInput(pageKey) { detectTapGestures(onTap = { point ->
                val mapped = currentTransform.inverse().map(point.x, point.y)
                currentState.page?.words?.firstOrNull { word -> word.boxes.any { it.contains(mapped.first, mapped.second) } }?.let { vm.app.playback.select(it.id) }
            }) }) {
            val image = bitmap ?: return@Canvas
            val t = transform
            val matrix = Matrix().apply { setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f)) }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save(); native.concat(matrix)
                native.drawBitmap(image, null, RectF(0f, 0f, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                val words = currentState.page?.words.orEmpty()
                val active = words.firstOrNull { it.id == currentState.activeWordId }
                val sentence = active?.sentenceId
                val fill = Paint().apply { color = 0x304883DF }
                val activeFill = Paint().apply { color = 0x701554D1 }
                val stroke = Paint().apply { color = 0xFF1554D1.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 / scale }
                words.filter { it.sentenceId == sentence || it.id == currentState.selectedWordId }.forEach { word ->
                    word.boxes.forEach { box ->
                        val rect = RectF(box.left, box.top, box.right, box.bottom)
                        native.drawRect(rect, if (word.id in currentState.activeWordIds) activeFill else fill)
                        if (word.id in currentState.activeWordIds || word.id == currentState.selectedWordId) native.drawRect(rect, stroke)
                    }
                }
                native.restore()
            }
        }
        if (bitmap == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error == null) CircularProgressIndicator() else Text(error.orEmpty()) }
        Row(Modifier.align(Alignment.TopEnd)) {
            Tool(Icons.Default.RotateRight, "Rotate page view") { rotation = (rotation + 90) % 360; zoom = 1f; pan = Offset.Zero }
            Tool(Icons.Default.FitScreen, if (fitWidth) "Fit whole page" else "Fit page width") { fitWidth = !fitWidth; zoom = 1f; pan = Offset.Zero }
        }
    }
}
