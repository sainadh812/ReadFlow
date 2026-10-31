package app.readflow.speech

import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BandLimitedResamplerTest {
    @Test fun preservesPreviousFilterAcrossSpeechSampleRatesAndEdges() {
        val random = Random(472)
        val input = FloatArray(997) { random.nextFloat() * 2 - 1 }
        for ((from, to) in listOf(24000 to 16000, 48000 to 16000, 44100 to 16000,
                22050 to 16000, 16000 to 24000, 16000 to 44100)) {
            assertArrayEquals("$from -> $to", reference(input, from, to), resample(input, from, to), 0.000001f)
        }
    }

    @Test fun attenuatesOutOfBandEnergyAndPreservesSpeechBand() {
        fun tone(frequency: Double) = FloatArray(24000) { sin(2 * PI * frequency * it / 24000).toFloat() }
        fun rms(input: FloatArray) = sqrt(input.sliceArray(200 until input.size - 200).sumOf { it.toDouble() * it } / (input.size - 400))
        val low = rms(resample(tone(1000.0), 24000, 16000))
        val high = rms(resample(tone(10000.0), 24000, 16000))
        assertEquals(sqrt(.5), low, .005)
        assertTrue("High frequencies must not alias into speech: $high", high < .01)
    }

    @Test fun preservesImpulseCoordinatesWithoutGroupDelay() {
        val input = FloatArray(2400).apply { this[1200] = 1f }
        val output = resample(input, 24000, 16000)
        assertEquals(1600, output.size)
        assertEquals(800, output.indices.maxBy { output[it] })
    }

    @Test fun preservesConstantLevelIncludingBoundaries() {
        val output = resample(FloatArray(100) { .25f }, 24000, 16000)
        assertTrue(output.all { abs(it - .25f) < .000001f })
    }

    @Test fun cachedFiltersHaveNoSignalStateAndAreSafeToShare() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val input = FloatArray(400) { sin(it.toDouble()).toFloat() }
            val expected = resample(input, 24000, 16000)
            val results = (0 until 16).map { pool.submit<FloatArray> { resample(input, 24000, 16000) } }
            results.forEach { assertArrayEquals(expected, it.get(), 0f) }
            assertTrue(resample(FloatArray(400), 24000, 16000).all { it == 0f })
        } finally { pool.shutdownNow() }
    }

    @Test fun sameRateReturnsIndependentCopyAndEmptyInputStaysEmpty() {
        val input = floatArrayOf(.5f, -.25f)
        val output = resample(input, 16000, 16000)
        assertArrayEquals(input, output, 0f)
        output[0] = 0f
        assertEquals(.5f, input[0], 0f)
        assertEquals(0, resample(FloatArray(0), 24000, 16000).size)
    }

    @Test fun rejectsInvalidSampleRates() {
        for ((from, to) in listOf(0 to 16000, 24000 to 0, -1 to 16000)) {
            try { resample(floatArrayOf(1f), from, to); fail("Accepted invalid rates") }
            catch (_: IllegalArgumentException) { }
        }
    }

    /** Independent copy of the previously shipped calculation, retained only as a regression oracle. */
    private fun reference(input: FloatArray, from: Int, to: Int): FloatArray {
        val ratio = from.toDouble() / to
        val cutoff = min(1.0, to.toDouble() / from) * .95
        return FloatArray((input.size / ratio).roundToInt()) { output ->
            val center = output * ratio
            var sum = 0.0; var weight = 0.0
            for (index in floor(center).toInt() - 24..floor(center).toInt() + 24) {
                if (index !in input.indices) continue
                val x = (center - index) * cutoff
                val sinc = if (abs(x) < 1e-8) 1.0 else sin(PI * x) / (PI * x)
                val window = .5 + .5 * cos(PI * (center - index) / 25)
                val coefficient = sinc * window * cutoff
                sum += input[index] * coefficient; weight += coefficient
            }
            (sum / weight).toFloat()
        }
    }
}
