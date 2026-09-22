package app.readflow.core

/** Audio remains usable when the acoustic model cannot validate word boundaries. */
data class PlaybackAlignment(
    val timings: List<WordTiming>,
    val rejected: AlignmentConfidenceException? = null,
)

suspend fun alignForPlayback(aligner: SpeechAligner, audio: PcmAudio, speech: SpeechText): PlaybackAlignment =
    try {
        PlaybackAlignment(aligner.align(audio, speech))
    } catch (error: AlignmentConfidenceException) {
        // Keep the confidence gate. Never manufacture boundaries from text lengths or audio duration.
        PlaybackAlignment(emptyList(), error)
    }

fun playbackStartMs(audio: AlignedAudio, requestedWordId: String?): Long {
    val boundary = if (requestedWordId == null) audio.timings.firstOrNull()
        else audio.timings.firstOrNull { it.wordId == requestedWordId }
    if (boundary != null) return (boundary.startSample * 1000 + audio.sampleRate - 1) / audio.sampleRate
    // Unsynchronized audio is only seekable at its actual beginning. The planner must synthesize
    // from the selected source word, so a missing boundary cannot jump to an earlier sentence.
    check(audio.timings.isEmpty() && (requestedWordId == null || requestedWordId in audio.speech.tokens.first().sourceIds)) {
        "This selection has no validated spoken boundary. Select a spoken word."
    }
    return 0
}

fun playbackResumeWord(audio: AlignedAudio, activeWordId: String?): String? =
    activeWordId ?: if (audio.timings.isEmpty()) audio.speech.tokens.firstOrNull()?.sourceIds?.firstOrNull() else null
