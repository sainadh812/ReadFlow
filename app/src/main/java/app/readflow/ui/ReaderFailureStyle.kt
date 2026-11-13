package app.readflow.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.readflow.core.SourceWord

internal val ReaderFailureInk = Color(0xFF9F101D)
internal val ReaderFailureBackground = Color(0xFFFFE3E4)

/** Coordinates are in source-page space; call after drawing playback/selection highlights. */
internal fun Canvas.drawFailedSentenceMarks(words: List<SourceWord>, scale: Float, lineWidthPx: Float) {
    if (words.isEmpty()) return
    val fill = Paint().apply { color = ReaderFailureInk.copy(alpha = .18f).toArgb() }
    val underline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderFailureInk.toArgb()
        strokeWidth = lineWidthPx / scale.coerceAtLeast(.001f)
        strokeCap = Paint.Cap.ROUND
    }
    // A pale red backing keeps the same red underline visible on dark scans as on white PDFs.
    val backing = Paint(underline).apply {
        color = ReaderFailureBackground.toArgb()
        strokeWidth = underline.strokeWidth * 2f
    }
    words.forEach { word ->
        word.boxes.forEach { box ->
            if (box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.width > 0f && box.height > 0f) {
                drawRect(box.left, box.top, box.right, box.bottom, fill)
                drawLine(box.left, box.bottom, box.right, box.bottom, backing)
                drawLine(box.left, box.bottom, box.right, box.bottom, underline)
            }
        }
    }
}
