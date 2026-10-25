package app.readflow.playback

/** Audio already synthesized and aligned, starting with the player's current item. */
data class PreparedAudioSegment(
    val sentenceId: String,
    val durationMs: Long,
    val completesSentence: Boolean,
) {
    init {
        require(sentenceId.isNotBlank())
        require(durationMs >= 0)
    }
}

data class AudioReserve(
    val futureSentenceCount: Int,
    val queuedChunkCount: Int,
    /** Time the prepared audio will actually last at the selected playback speed. */
    val remainingListeningMs: Long,
) {
    init {
        require(futureSentenceCount >= 0)
        require(queuedChunkCount >= 0)
        require(remainingListeningMs >= 0)
    }
}

/** Invalid player speed values must not turn the reserve into infinity or a negative number. */
fun listeningTimeMs(mediaMs: Long, speed: Float): Long {
    if (mediaMs <= 0) return 0
    val actualSpeed = if (speed.isFinite() && speed > 0f) speed.toDouble() else 1.0
    return (mediaMs.toDouble() / actualSpeed).toLong()
}

fun audioReserve(
    segments: List<PreparedAudioSegment>,
    currentPositionMs: Long,
    speed: Float,
): AudioReserve {
    if (segments.isEmpty()) return AudioReserve(0, 0, 0)
    var remainingMediaMs = 0L
    for ((index, segment) in segments.withIndex()) {
        val duration = if (index == 0) {
            segment.durationMs - currentPositionMs.coerceIn(0, segment.durationMs)
        } else segment.durationMs
        remainingMediaMs = if (Long.MAX_VALUE - remainingMediaMs < duration) Long.MAX_VALUE
            else remainingMediaMs + duration
    }
    val currentSentenceId = segments.first().sentenceId
    val completeFutureSentences = segments.asSequence()
        .filter { it.completesSentence && it.sentenceId != currentSentenceId }
        .map { it.sentenceId }
        .distinct()
        .count()
    return AudioReserve(completeFutureSentences, segments.size, listeningTimeMs(remainingMediaMs, speed))
}

/**
 * Maintain four complete sentences ahead and a reserve measured in listening time.
 * Limits permit an unusually long sentence to start without preparing an unbounded queue.
 * All time arguments use the same monotonic clock, never wall-clock timestamps.
 */
class AudioReservePolicy {
    val targetFutureSentences = 4
    val targetListeningMs = 30_000L
    val maximumListeningMs = 120_000L
    val maximumQueuedChunks = 64
    val maximumStartupWaitMs = 8_000L
    private val constrainedMaximumListeningMs = 60_000L
    private val constrainedMaximumQueuedChunks = 24

    fun shouldPrepareMore(reserve: AudioReserve, constrained: Boolean = false): Boolean =
        !atLimit(reserve, constrained) && !atTarget(reserve)

    /** Also used after starvation, so recovery builds a reserve instead of restarting every chunk. */
    fun canStart(
        reserve: AudioReserve,
        firstReadyAtMs: Long?,
        nowMs: Long,
        inputComplete: Boolean = false,
        constrained: Boolean = false,
    ): Boolean {
        if (reserve.queuedChunkCount == 0 || reserve.remainingListeningMs == 0L) return false
        if (inputComplete || atTarget(reserve) || atLimit(reserve, constrained)) return true
        if (firstReadyAtMs == null || firstReadyAtMs < 0 || nowMs < firstReadyAtMs) return false
        // Slow synthesis must not keep playable audio waiting indefinitely at startup/recovery.
        return nowMs - firstReadyAtMs >= maximumStartupWaitMs
    }

    private fun atTarget(reserve: AudioReserve): Boolean =
        reserve.futureSentenceCount >= targetFutureSentences && reserve.remainingListeningMs >= targetListeningMs

    private fun atLimit(reserve: AudioReserve, constrained: Boolean): Boolean =
        reserve.remainingListeningMs >= (if (constrained) constrainedMaximumListeningMs else maximumListeningMs) ||
            reserve.queuedChunkCount >= (if (constrained) constrainedMaximumQueuedChunks else maximumQueuedChunks)
}

/**
 * Android's running-low range is 10..<20; 20..<40 only means the UI is hidden.
 * Levels 40 and above request background resource trimming. Use ranges so future
 * intermediate levels retain their category, without depending on Android in unit tests.
 */
fun isMemoryPressureLevel(level: Int): Boolean = level in 10 until 20 || level >= 40

/** Memory pressure expires; ordinary backgrounding should not call [notePressure]. */
class MemoryPressureWindow(private val durationMs: Long = 60_000L) {
    private var lastPressureAtMs: Long? = null

    init { require(durationMs > 0) }

    fun notePressure(nowMs: Long) {
        require(nowMs >= 0)
        lastPressureAtMs = nowMs
    }

    fun isActive(nowMs: Long): Boolean {
        val last = lastPressureAtMs ?: return false
        return nowMs >= last && nowMs - last < durationMs
    }
}
