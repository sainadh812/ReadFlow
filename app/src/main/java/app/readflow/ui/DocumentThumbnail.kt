package app.readflow.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readflow.data.DocumentEntity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A decorative local preview; the enclosing document card supplies its accessible title. */
@Composable fun DocumentThumbnail(document: DocumentEntity, modifier: Modifier = Modifier) {
    val key = remember(document.hash, document.localPath, document.mime) {
        ThumbnailKey(document.hash, document.localPath, document.mime.lowercase(Locale.ROOT))
    }
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = if (key.renderable) DocumentThumbnails.load(key) else null
    }
    val colors = MaterialTheme.colorScheme
    val variant = (document.hash.hashCode() and Int.MAX_VALUE) % 3
    val paper = when (variant) { 0 -> colors.primaryContainer; 1 -> colors.secondaryContainer; else -> colors.surfaceContainerHigh }
    val ink = when (variant) { 0 -> colors.onPrimaryContainer; 1 -> colors.onSecondaryContainer; else -> colors.onSurface }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(paper).clearAndSetSemantics { }) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            val compact = maxWidth < 100.dp || maxHeight < 100.dp
            val titleTopPadding = if (maxHeight < 160.dp) 8.dp else 24.dp
            Canvas(Modifier.fillMaxSize()) {
                drawRect(ink.copy(alpha = .09f), size = Size(size.width * .06f, size.height))
                drawCircle(ink.copy(alpha = .10f), radius = size.width * .42f,
                    center = Offset(size.width * .93f, size.height * .15f))
                val fold = Path().apply {
                    moveTo(size.width * .35f, size.height)
                    cubicTo(size.width * .67f, size.height * .57f, size.width * .82f, size.height * .86f, size.width, size.height * .56f)
                    lineTo(size.width, size.height); close()
                }
                drawPath(fold, ink.copy(alpha = .08f))
                drawLine(ink.copy(alpha = .20f), Offset(size.width * .15f, size.height * .18f),
                    Offset(size.width * .43f, size.height * .18f), strokeWidth = 1.dp.toPx())
            }
            if (compact) Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(document.title.trim().ifBlank { "Untitled" }.first().uppercaseChar().toString(), color = ink,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 36.sp,
                        fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Clip)
            } else Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document.title.ifBlank { "Untitled" },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = titleTopPadding), color = ink,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 23.sp,
                        lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(when { key.pdf -> "PDF"; key.mime.startsWith("image/") -> "IMAGE"; else -> "ARTICLE" },
                    color = ink.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

private data class ThumbnailKey(val hash: String, val path: String, val mime: String) {
    val pdf get() = mime == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)
    val renderable get() = pdf || mime.startsWith("image/")
}

private data class CachedThumbnail(val bitmap: Bitmap?)

private object DocumentThumbnails {
    // Up to about ten full-size previews, plus a bounded number of failure entries.
    // Active UI references can outlive cache eviction; never recycle published images.
    private val cache = ThumbnailMemoryCache<ThumbnailKey, CachedThumbnail>(8L * 1024 * 1024, 48)
    private val renderLock = Mutex()

    suspend fun load(key: ThumbnailKey): Bitmap? {
        cache.get(key)?.let { return it.bitmap }
        return withContext(Dispatchers.IO) {
            // Rechecking under one cancellable lock deduplicates simultaneous cards
            // for a document and limits native rendering to one in-flight allocation.
            renderLock.withLock {
                currentCoroutineContext().ensureActive()
                cache.get(key)?.let { return@withLock it.bitmap }
                val result = try { CachedThumbnail(render(key)) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { CachedThumbnail(null) }
                cache.put(key, result, result.bitmap?.allocationByteCount ?: 0)
                result.bitmap
            }
        }
    }

    private suspend fun render(key: ThumbnailKey): Bitmap {
        var owned: Bitmap? = null
        try {
            val bitmap = if (key.pdf) {
                openPdf(key.path).use { renderer ->
                    check(renderer.pageCount > 0)
                    renderer.openPage(0).use { page ->
                        val size = thumbnailSize(page.width, page.height)
                        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { image ->
                            owned = image
                            image.eraseColor(AndroidColor.WHITE)
                            page.render(image, null, Matrix().apply {
                                setScale(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
                            }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(key.path))) { decoder, info, _ ->
                    val size = thumbnailSize(info.size.width, info.size.height)
                    decoder.setTargetSize(size.width, size.height)
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.also { owned = it }
            }
            // Native decoding is synchronous. If the requesting card left the screen
            // during decoding, free only this unpublished image and release all handles.
            currentCoroutineContext().ensureActive()
            owned = null
            return bitmap
        } finally { owned?.recycle() }
    }

    private fun openPdf(path: String): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) }
        catch (error: Throwable) { descriptor.close(); throw error }
    }
}
