package app.readflow.ui

import kotlin.math.floor
import kotlin.math.min

internal data class ThumbnailSize(val width: Int, val height: Int)

/** Bound both dimensions, including long screenshots and unusually shaped PDF pages. */
internal fun thumbnailSize(width: Int, height: Int, maxWidth: Int = 384, maxHeight: Int = 544): ThumbnailSize {
    require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
    val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
    return ThumbnailSize(floor(width * scale).toInt().coerceAtLeast(1), floor(height * scale).toInt().coerceAtLeast(1))
}

/** Eviction drops only references: a visible Compose image may still own the value. */
internal class ThumbnailMemoryCache<K, V>(private val maxBytes: Long, private val maxEntries: Int) {
    private data class Item<V>(val value: V, val bytes: Long)
    private val items = LinkedHashMap<K, Item<V>>(16, .75f, true)
    private var bytes = 0L

    init { require(maxBytes > 0 && maxEntries > 0) }

    @Synchronized fun get(key: K): V? = items[key]?.value

    @Synchronized fun put(key: K, value: V, allocationBytes: Int) {
        require(allocationBytes >= 0)
        items.remove(key)?.let { bytes -= it.bytes }
        val weight = allocationBytes.toLong().coerceAtLeast(1)
        if (weight > maxBytes) return
        items[key] = Item(value, weight)
        bytes += weight
        val eldest = items.entries.iterator()
        while (bytes > maxBytes || items.size > maxEntries) {
            bytes -= eldest.next().value.bytes
            eldest.remove()
        }
    }

    val byteCount: Long @Synchronized get() = bytes
    val entryCount: Int @Synchronized get() = items.size
}
