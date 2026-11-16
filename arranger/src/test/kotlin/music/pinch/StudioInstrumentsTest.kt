package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class StudioInstrumentsTest {
    private val instruments = listOf(::studioElectricPiano, ::studioMallet, ::studioPluckedString)

    @Test fun materialVoicesHaveFiniteBoundedOutputAndQuietEndpointsAtBothRates() {
        for (instrument in instruments) {
            for (rate in listOf(32_000, 44_100)) {
                for (midi in listOf(36, 60, 76, 96)) {
                    val sample = instrument(midi, 900, .85f, rate)
                    assertTrue(sample.all { it.isFinite() })
                    assertTrue(sample.maxOf { abs(it) } < 1.1f, "${instrument.name} / $rate / $midi peak")
                    assertTrue(abs(sample.first()) < .025f)
                    assertTrue(sample.last() == 0f)
                    assertTrue(rms(sample, 20, 100, rate) > .02)
                }
            }
        }
    }

    @Test fun woodStringsAndTinesKeepDistinctDecayAndOvertoneProfiles() {
        val rate = 32_000
        val wood = studioMallet(60, 1200, .8f, rate)
        val tine = studioElectricPiano(60, 1200, .8f, rate)
        val string = studioPluckedString(60, 1200, .8f, rate)
        fun decay(sample: FloatArray) = rms(sample, 650, 800, rate) / rms(sample, 50, 200, rate)
        assertTrue(decay(wood) < decay(tine) * .6, "The wood strike must not behave like a sustained keyboard")
        val hz = 440.0 * 2.0.pow((60 - 69) / 12.0)
        fun second(sample: FloatArray) = partial(sample, hz * 2, rate) / partial(sample, hz, rate)
        assertTrue(second(string) > second(tine) * 2, "The plucked string needs its own harmonic body")
        assertTrue(partial(wood, hz * 4.01, rate) > partial(wood, hz * 2, rate) * 3,
            "A tuned bar carries its flexural mode instead of a generic saw spectrum")
    }

    @Test fun highNotesDoNotFoldUnboundedPartialsIntoTheAudibleRange() {
        for (instrument in instruments) {
            val sample = instrument(120, 180, .9f, 32_000)
            val hz = 440.0 * 2.0.pow((120 - 69) / 12.0)
            assertTrue(partial(sample, hz, 32_000) > partial(sample, 1000.0, 32_000) * 100)
        }
    }

    private fun rms(samples: FloatArray, fromMs: Int, toMs: Int, rate: Int): Double {
        val from = fromMs * rate / 1000
        val to = toMs * rate / 1000
        return sqrt((from until to).sumOf { samples[it].toDouble().pow(2) } / (to - from))
    }

    private fun partial(samples: FloatArray, hz: Double, rate: Int): Double {
        val from = rate / 100
        val to = rate * 9 / 100
        var real = 0.0
        var imaginary = 0.0
        for (i in from until to) {
            val window = .5 - .5 * cos(2 * PI * (i - from) / (to - from - 1))
            val angle = 2 * PI * hz * i / rate
            real += samples[i] * window * cos(angle)
            imaginary += samples[i] * window * sin(angle)
        }
        return sqrt(real * real + imaginary * imaginary)
    }
}
