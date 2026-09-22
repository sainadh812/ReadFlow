package app.readflow.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.readflow.core.Transform
import app.readflow.diagnostics.documentIssueInput
import app.readflow.playback.ReaderPlayback
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

@Composable internal fun OriginalPage(vm: ReaderViewModel, state: ReaderPlayback, modifier: Modifier, follow: Boolean,
    rotation: Int, fitWidth: Boolean, swipePages: Boolean, rightAdvances: Boolean, manual: () -> Unit) {
    val page = state.page
    val document = state.document ?: return
    val pageIndex = page?.index ?: state.viewPageIndex
    val pageKey = document.id to pageIndex
    var bitmap by remember(pageKey) { mutableStateOf<Bitmap?>(null) }
    var error by remember(pageKey) { mutableStateOf<String?>(null) }
    var zoom by remember(pageKey, rotation, fitWidth) { mutableFloatStateOf(1f) }
    var pan by remember(pageKey, rotation, fitWidth) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pageKey, document.localPath) {
        try { bitmap = vm.app.documents.extractor.render(document.localPath, pageIndex) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            error = e.message ?: "Page rendering failed"
            vm.captureIssue("RENDER_PAGE", e, documentIssueInput(document, page, pageIndex = pageIndex))
        }
    }
    DisposableEffect(pageKey) { onDispose { bitmap?.recycle(); bitmap = null } }
    val width = page?.width?.takeIf { it > 0 } ?: bitmap?.width?.toFloat() ?: 1f
    val height = page?.height?.takeIf { it > 0 } ?: bitmap?.height?.toFloat() ?: 1f
    val swapped = rotation % 180 != 0
    val viewport = PageViewport(if (swapped) height else width, if (swapped) width else height,
        size.width.toFloat(), size.height.toFloat(), fitWidth)
    val scale = viewport.fit * zoom
    val boundedPan = viewport.constrain(pan, zoom)
    val offsetX = viewport.base.x + boundedPan.x
    val offsetY = viewport.base.y + boundedPan.y
    val transform = when (rotation) {
        90 -> Transform(0f, scale, -scale, 0f, offsetX + height * scale, offsetY)
        180 -> Transform(-scale, 0f, 0f, -scale, offsetX + width * scale, offsetY + height * scale)
        270 -> Transform(0f, -scale, scale, 0f, offsetX, offsetY + width * scale)
        else -> Transform(scale, 0f, 0f, scale, offsetX, offsetY)
    }
    val currentTransform by rememberUpdatedState(transform)
    val currentViewport by rememberUpdatedState(viewport)
    val onManual by rememberUpdatedState(manual)
    LaunchedEffect(viewport) { pan = viewport.constrain(pan, zoom) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.activeWordId, follow, size, rotation, fitWidth) {
        if (follow) page?.words?.firstOrNull { it.id == state.activeWordId }?.boxes?.firstOrNull()?.let { box ->
            val point = transform.map((box.left + box.right) / 2, (box.top + box.bottom) / 2)
            if (point.second !in 40f..(size.height - 50f)) pan = viewport.constrain(boundedPan + Offset(0f, size.height / 2f - point.second), zoom)
        }
    }
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(pageKey, rotation, fitWidth, swipePages, rightAdvances, size) {
                val threshold = maxOf(64.dp.toPx(), minOf(size.width * .18f, 120.dp.toPx()))
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var travel = Offset.Zero
                    var accumulatedZoom = 1f
                    var dragging = false
                    var multiTouch = false
                    var cancelled = false
                    val initialZoom = zoom
                    do {
                        val event = awaitPointerEvent()
                        cancelled = event.changes.any { it.isConsumed }
                        if (!cancelled) {
                            multiTouch = multiTouch || event.changes.count { it.pressed || it.previousPressed } > 1
                            val delta = event.calculatePan()
                            val factor = event.calculateZoom()
                            travel += delta
                            accumulatedZoom *= factor
                            if (!dragging) {
                                val zoomMotion = abs(1 - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                                dragging = travel.getDistance() > viewConfiguration.touchSlop || zoomMotion > viewConfiguration.touchSlop
                                if (dragging) onManual()
                            }
                            if (dragging && event.changes.any { it.pressed && it.previousPressed }) {
                                val next = (zoom * factor).coerceIn(1f, 6f)
                                pan = currentViewport.zoomPan(pan, zoom, next, event.calculateCentroid(useCurrent = false), delta)
                                zoom = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!cancelled && event.changes.any { it.pressed })
                    if (!cancelled && dragging) {
                        val delta = swipePageDelta(travel, threshold, multiTouch, maxOf(initialZoom, zoom), swipePages, rightAdvances)
                        if (delta != 0) vm.page(pageIndex + delta)
                    }
                }
            }
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
    }
}
