package app.readflow.playback

data class PrepareAheadProgress(
    val startPage: Int,
    val endPageInclusive: Int,
    val currentPage: Int = startPage,
    val completedPages: Int = 0,
    val preparedChunks: Int = 0,
    val reusedChunks: Int = 0,
    val audioMs: Long = 0,
    val bytes: Long = 0,
    val running: Boolean = true,
    val status: String = "Preparing pages",
) {
    val totalPages: Int get() = endPageInclusive - startPage + 1
}

/** Counts unique cached chunks, so a page range never evicts its own earlier audio to fit. */
internal class PreparedRangeBudget(private val limit: Long) {
    init { require(limit >= 0) }
    private val entries = linkedMapOf<String, Long>()
    var bytes: Long = 0
        private set
    val protectedKeys: Set<String> get() = entries.keys.toSet()
    fun canAdd(key: String, size: Long): Boolean {
        require(size >= 0)
        val already = entries[key] ?: 0
        return size <= limit && bytes - already <= limit - size
    }
    fun add(key: String, size: Long) {
        check(canAdd(key, size)) { "Prepared pages exceed the audio cache limit" }
        bytes = bytes - (entries[key] ?: 0) + size
        entries[key] = size
    }
}
