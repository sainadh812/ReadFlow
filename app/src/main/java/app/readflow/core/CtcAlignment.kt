package app.readflow.core

import kotlin.math.exp
import kotlin.math.ln

class AlignmentConfidenceException(val tokenIndex: Int, val token: String, val sourceWordIds: List<String>,
    val confidence: Float, val threshold: Float,
) : IllegalArgumentException("Word alignment confidence is too low; word synchronization unavailable")

/** Known-transcript CTC Viterbi alignment. No transcript decoding or word-duration estimates. */
class CtcAlignment {
    companion object {
        const val VERSION = "wav2vec2-729c1a6-ctc-v1"
        val VOCAB = listOf("<pad>", "<s>", "</s>", "<unk>", "|", "E", "T", "A", "O", "N", "I", "H", "S", "R", "D", "L", "U", "M", "W", "C", "F", "G", "Y", "P", "B", "V", "K", "'", "X", "J", "Q", "Z")
    }
    fun align(logits: Array<FloatArray>, text: SpeechText, sampleRate: Int, sampleCount: Int): List<WordTiming> {
        require(logits.isNotEmpty() && logits.size <= 2000) { "Alignment audio must be under 40 seconds" }
        val target = mutableListOf<Int>()
        val owners = mutableListOf<Int>()
        text.tokens.forEachIndexed { index, token ->
            if (index > 0) { target += 4; owners += -1 }
            token.text.forEach { c ->
                val id = VOCAB.indexOf(c.toString())
                require(id >= 5) { "Unsupported alignment character: $c" }
                target += id; owners += index
            }
        }
        require(target.isNotEmpty() && target.size <= 1000)
        val states = target.size * 2 + 1
        val labels = IntArray(states) { if (it % 2 == 0) 0 else target[it / 2] }
        val path = Array(logits.size) { ByteArray(states) }
        val logp = logits.map { row ->
            require(row.size == VOCAB.size)
            val max = row.max()
            val denom = max + ln(row.sumOf { exp((it - max).toDouble()) }).toFloat()
            FloatArray(row.size) { row[it] - denom }
        }
        var previous = FloatArray(states) { Float.NEGATIVE_INFINITY }
        previous[0] = 0f
        for (t in logits.indices) {
            val current = FloatArray(states) { Float.NEGATIVE_INFINITY }
            for (s in 0 until minOf(states, 2 * t + 2)) {
                var best = previous[s]; var step = 0
                if (s > 0 && previous[s - 1] > best) { best = previous[s - 1]; step = 1 }
                if (s > 1 && labels[s] != 0 && labels[s] != labels[s - 2] && previous[s - 2] > best) { best = previous[s - 2]; step = 2 }
                current[s] = best + logp[t][labels[s]]
                path[t][s] = step.toByte()
            }
            previous = current
        }
        var state = if (previous.last() > previous[states - 2]) states - 1 else states - 2
        require(previous[state].isFinite()) { "Transcript cannot be aligned to this audio" }
        val starts = IntArray(text.tokens.size) { Int.MAX_VALUE }
        val ends = IntArray(text.tokens.size) { -1 }
        val confidence = FloatArray(text.tokens.size)
        val count = IntArray(text.tokens.size)
        for (t in logits.lastIndex downTo 0) {
            if (state % 2 == 1) {
                val owner = owners[state / 2]
                if (owner >= 0) {
                    starts[owner] = minOf(starts[owner], t); ends[owner] = maxOf(ends[owner], t)
                    confidence[owner] += exp(logp[t][labels[state]]); count[owner]++
                }
            }
            state -= path[t][state].toInt()
        }
        require(count.all { it > 0 }) { "Alignment has missing words" }
        val timings = linkedMapOf<String, WordTiming>()
        text.tokens.forEachIndexed { index, token ->
            val quality = confidence[index] / count[index]
            require(quality.isFinite()) { "Word alignment produced a non-finite confidence" }
            if (quality < .12f) throw AlignmentConfidenceException(index, token.text, token.sourceIds, quality, .12f)
            // Wav2Vec2 convolution stride 320, receptive field 400 at 16 kHz.
            val start = starts[index] * 320L * sampleRate / 16000
            val end = ((ends[index] * 320L + 400) * sampleRate / 16000).coerceAtMost(sampleCount.toLong())
            token.sourceIds.forEach { id ->
                val old = timings[id]
                timings[id] = WordTiming(id, minOf(old?.startSample ?: start, start), maxOf(old?.endSample ?: end, end), minOf(old?.confidence ?: quality, quality))
            }
        }
        return timings.values.sortedBy { it.startSample }.mapIndexed { index, timing ->
            val next = timings.values.elementAtOrNull(index + 1)
            if (next != null && next.startSample > timing.startSample) timing.copy(endSample = minOf(timing.endSample, next.startSample)) else timing
        }
    }
}
