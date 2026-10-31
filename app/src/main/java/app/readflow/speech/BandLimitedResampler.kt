package app.readflow.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The same centered 49-tap windowed-sinc filter used by the original aligner, with
 * reusable coefficients for each rational sample phase. At 24 kHz -> 16 kHz only
 * two phases are needed instead of calculating 49 sin/cos pairs per output sample.
 * No filter delay is introduced; original PCM sample coordinates remain valid.
 */
internal class BandLimitedResampler(private val from: Int, private val to: Int) {
    init { require(from > 0 && to > 0) { "Sample rates must be positive" } }
    private val divisor = gcd(from, to)
    private val step = from / divisor
    private val phases = to / divisor
    private val cutoff = min(1.0, to.toDouble() / from) * .95
    // Bound cache memory for unusual coprime sample rates. Ordinary speech rates
    // need at most 160 phases; the fallback still computes the identical filter.
    private val kernels = if (phases <= 2048) Array(phases) { phase -> kernel(phase.toDouble() / phases) } else null

    fun process(input: FloatArray): FloatArray {
        if (from == to) return input.copyOf()
        val size = (input.size.toDouble() * to / from).roundToInt()
        return FloatArray(size) { output ->
            val position = output.toLong() * step
            val center = (position / phases).toInt()
            val phase = (position % phases).toInt()
            val coefficients = kernels?.get(phase) ?: kernel(phase.toDouble() / phases)
            var sum = 0.0
            var weight = 0.0
            for (tap in coefficients.indices) {
                val index = center + tap - HALF_WIDTH
                if (index !in input.indices) continue
                val coefficient = coefficients[tap]
                sum += input[index] * coefficient
                weight += coefficient
            }
            (sum / weight).toFloat()
        }
    }

    private fun kernel(phase: Double): DoubleArray = DoubleArray(HALF_WIDTH * 2 + 1) { tap ->
        val distance = phase - (tap - HALF_WIDTH)
        val x = distance * cutoff
        val sinc = if (abs(x) < 1e-8) 1.0 else sin(PI * x) / (PI * x)
        val window = .5 + .5 * cos(PI * distance / (HALF_WIDTH + 1))
        sinc * window * cutoff
    }

    companion object {
        private const val HALF_WIDTH = 24
        private fun gcd(first: Int, second: Int): Int {
            var a = first; var b = second
            while (b != 0) { val remainder = a % b; a = b; b = remainder }
            return a
        }
    }
}

private object ResamplerCache {
    private val kernels = object : LinkedHashMap<Pair<Int, Int>, BandLimitedResampler>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, BandLimitedResampler>?): Boolean = size > 4
    }
    @Synchronized fun get(from: Int, to: Int): BandLimitedResampler =
        kernels.getOrPut(from to to) { BandLimitedResampler(from, to) }
}

/** Band-limited conversion used only for acoustic alignment; playback PCM is unchanged. */
fun resample(input: FloatArray, from: Int, to: Int): FloatArray = ResamplerCache.get(from, to).process(input)
