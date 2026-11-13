package app.readflow.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One captured viewport per turn; navigation is dispatched only after a completed gesture. */
@Stable internal class PageTurnState(private val scope: CoroutineScope,
    private val animationsEnabled: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() },
    private val motion: suspend (Float, Float, Int, (Float) -> Unit) -> Unit = { from, to, duration, frame ->
        animate(from, to, animationSpec = androidx.compose.animation.core.tween(duration, easing = FastOutSlowInEasing)) { value, _ -> frame(value) }
    }) {
    var progress by mutableFloatStateOf(0f); private set
    var snapshot by mutableStateOf<ImageBitmap?>(null); private set
    var active by mutableStateOf(false); private set
    var committed by mutableStateOf(false); private set
    var towardLeft by mutableStateOf(true); private set
    var targetPageIndex by mutableIntStateOf(0); private set
    var style by mutableStateOf("curl"); private set
    var incomingReady by mutableStateOf(false); private set
    var currentPageIndex by mutableIntStateOf(0); private set
    internal val originalPreviews = mutableStateMapOf<Int, Bitmap>()
    internal var capture: (suspend () -> ImageBitmap?)? = null
    private var key: Any? = null
    private var index = 0
    private var count = 0
    private var enabled = true
    private var rightAdvances = false
    private var turn: (Int) -> Unit = {}
    private var captureJob: Job? = null
    private var animation: Job? = null
    private var readyTimeout: Job? = null
    private var gesture = 0
    private var width = 1f
    private var size = IntSize.Zero
    private var sourceIndex = 0
    private var finishing by mutableStateOf(false)
    internal val blocksTouches get() = committed || finishing

    private val animateTurns get() = style != "off" && animationsEnabled()

    internal fun update(pageKey: Any, pageIndex: Int, pageCount: Int, style: String, enabled: Boolean,
        rightAdvances: Boolean, onTurn: (Int) -> Unit) {
        if (key != null && key != pageKey) {
            val documentChanged = key is Pair<*, *> && pageKey is Pair<*, *> && (key as Pair<*, *>).first != pageKey.first
            if (documentChanged) originalPreviews.clear()
            if (documentChanged || !committed || pageIndex != targetPageIndex) reset()
            // Expected navigation retains the immutable outgoing bitmap until the new page draws.
        }
        if (this.style != style || this.enabled != enabled) reset()
        key = pageKey; index = pageIndex; currentPageIndex = pageIndex; count = pageCount; this.style = style
        this.enabled = enabled; this.rightAdvances = rightAdvances; turn = onTurn
    }

    internal fun resize(value: IntSize) {
        if (size != IntSize.Zero && size != value) reset()
        size = value; width = value.width.toFloat().coerceAtLeast(1f)
    }

    /** The accumulated one-finger travel, after horizontal direction has won touch slop. */
    fun drag(travel: Offset) {
        if (!enabled || committed || finishing || !animateTurns) return
        val delta = if ((travel.x > 0f) == rightAdvances) 1 else -1
        val target = pageTurnTarget(index, count, delta)
        if (target == null) { if (active) cancel(); return }
        if (!active) begin(target, travel.x < 0f)
        if (targetPageIndex != target) { targetPageIndex = target; towardLeft = travel.x < 0f }
        // The corner travels twice the crease distance: a real sheet stays beneath the finger.
        progress = if (style == "slide") (abs(travel.x) / width).coerceIn(0f, .94f)
            else (abs(travel.x) / (2f * width)).coerceIn(0f, .48f)
    }

    fun release(delta: Int) {
        if (committed || finishing) return
        val target = pageTurnTarget(index, count, delta)
        if (!enabled || target == null) { cancel(); return }
        if (!animateTurns) { reset(); turn(target - index); return }
        if (!active) begin(target, if (rightAdvances) delta < 0 else delta > 0)
        targetPageIndex = target
        towardLeft = if (rightAdvances) delta < 0 else delta > 0
        // Do not let a very quick release race the asynchronous graphics-layer readback.
        animation?.cancel()
        animation = scope.launch {
            captureJob?.join()
            if (!active || committed) return@launch
            committed = true
            val requested = targetPageIndex - sourceIndex
            turn(requested)
            readyTimeout = scope.launch {
                // An unavailable/slow OCR page must never trap reader gestures or controls.
                delay(8_000)
                if (committed && !incomingReady) reset()
            }
            if (incomingReady) finish() else animateProgress(maxOf(progress, .34f), 180)
        }
    }

    /** For adjacent page menu actions; distant jumps continue to use normal navigation. */
    fun requestTurn(delta: Int) = release(delta)

    fun cancel() {
        if (!active || committed || finishing) return
        animation?.cancel()
        finishing = true
        animation = scope.launch {
            if (animateTurns) animateProgress(0f, 160)
            reset()
        }
    }

    private fun begin(target: Int, left: Boolean) {
        animation?.cancel(); readyTimeout?.cancel()
        active = true; committed = false; incomingReady = false; finishing = false
        sourceIndex = index; targetPageIndex = target; towardLeft = left
        val token = ++gesture
        captureJob = scope.launch {
            try {
                val image = capture?.invoke()
                if (active && gesture == token) snapshot = image
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A failed capture falls back to immediate page navigation. */ }
        }
    }

    internal fun contentReady(ready: Boolean) {
        if (!committed || index != targetPageIndex) return
        incomingReady = ready
        if (ready && !finishing) finish()
    }

    private fun finish() {
        if (finishing || !active) return
        finishing = true
        readyTimeout?.cancel(); animation?.cancel()
        animation = scope.launch {
            if (snapshot != null && animateTurns) animateProgress(1f, 300)
            reset()
        }
    }

    private suspend fun animateProgress(target: Float, duration: Int) {
        motion(progress, target, duration) { progress = it }
    }

    internal fun reset() {
        gesture++
        captureJob?.cancel(); captureJob = null
        animation?.cancel(); animation = null
        readyTimeout?.cancel(); readyTimeout = null
        active = false; committed = false; incomingReady = false; finishing = false
        progress = 0f; snapshot = null
    }
}

@Composable internal fun rememberPageTurnState(pageKey: Any, pageIndex: Int, pageCount: Int,
    style: String, enabled: Boolean, rightAdvances: Boolean, onTurn: (Int) -> Unit): PageTurnState {
    val scope = rememberCoroutineScope()
    val state = remember { PageTurnState(scope) }
    SideEffect { state.update(pageKey, pageIndex, pageCount, style, enabled, rightAdvances, onTurn) }
    DisposableEffect(state) { onDispose { state.reset(); state.originalPreviews.clear() } }
    return state
}

/** Header/dock sit outside this host. Only the visible paper is captured and bent. */
@Composable internal fun PageTurnViewport(state: PageTurnState, contentReady: Boolean, modifier: Modifier = Modifier,
    paperColor: Color = LocalReaderPaper.current.paper, preview: @Composable (Int) -> Unit = {}, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var recorded by remember { mutableStateOf(false) }
    SideEffect { state.capture = { if (recorded) layer.toImageBitmap() else null } }
    LaunchedEffect(contentReady, state.committed, state.targetPageIndex, state.currentPageIndex) { state.contentReady(contentReady) }
    DisposableEffect(state, layer) { onDispose { state.capture = null } }
    val frozen = state.active && state.snapshot != null
    Box(modifier.clipToBounds().background(paperColor).onSizeChanged(state::resize)) {
        if (frozen && !state.incomingReady) Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { preview(state.targetPageIndex) }
        Box(Modifier.fillMaxSize().drawWithContent {
            // Recording is cheap while idle; readback happens only once at the start of a turn.
            layer.record {
                // Text lists have a transparent background; the captured sheet must be opaque.
                drawRect(paperColor)
                this@drawWithContent.drawContent()
            }
            recorded = true
            if (!frozen || state.incomingReady) drawLayer(layer)
        }) { content() }
        if (frozen) Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}.then(if (state.blocksTouches) Modifier.pointerInput(Unit) {
            // Existing drags keep their original hit path. New touches cannot select words
            // on the hidden live page while a released sheet is finishing its turn.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        } else Modifier)) {
            val image = state.snapshot ?: return@Canvas
            if (state.style == "slide") {
                val offset = size.width * state.progress * if (state.towardLeft) -1f else 1f
                translate(left = offset) { drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
            } else drawPageFold(image, state.progress, state.towardLeft, paperColor)
        }
    }
}

private fun polygon(points: List<Offset>) = Path().apply {
    if (points.isNotEmpty()) {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
}

private fun DrawScope.drawPageFold(image: ImageBitmap, progress: Float, towardLeft: Boolean, paper: Color) {
    val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
    if (progress <= .001f) { drawImage(image, dstSize = destination); return }
    if (progress >= .999f) return
    val fold = pageFold(size.width, size.height, progress, towardLeft)
    val front = polygon(fold.front)
    val back = polygon(fold.back)
    val length = sqrt(fold.normal.x * fold.normal.x + fold.normal.y * fold.normal.y)
    val unit = fold.normal / length
    val center = fold.crease.firstOrNull() ?: Offset.Zero
    val band = (size.width * .09f).coerceIn(18f, 52f)
    // A broad contact shadow and a narrow highlighted ridge suggest a flexible paper cylinder.
    for (step in 8 downTo 1) {
        translate(left = unit.x * step * 1.3f, top = unit.y * step * 1.3f) {
            drawPath(back, Color.Black.copy(alpha = .012f))
        }
    }
    clipPath(front) { drawImage(image, dstSize = destination) }
    drawPath(back, paper)
    clipPath(back) {
        val nx = fold.normal.x; val ny = fold.normal.y; val square = length * length
        val reflection = Matrix().apply { setValues(floatArrayOf(
            1f - 2f * nx * nx / square, -2f * nx * ny / square, 2f * fold.intercept * nx / square,
            -2f * nx * ny / square, 1f - 2f * ny * ny / square, 2f * fold.intercept * ny / square,
            0f, 0f, 1f)) }
        drawContext.canvas.save()
        drawContext.canvas.nativeCanvas.concat(reflection)
        drawImage(image, dstSize = destination, alpha = .07f)
        drawContext.canvas.restore()
        drawRect(Brush.linearGradient(
            0f to Color.Black.copy(alpha = .24f), .3f to Color.Black.copy(alpha = .07f),
            .62f to Color.White.copy(alpha = .23f), 1f to Color.Transparent,
            start = center, end = center - unit * band))
    }
    if (fold.crease.size >= 2) drawLine(Color.Black.copy(alpha = .12f), fold.crease[0], fold.crease[1], 1f)
}
