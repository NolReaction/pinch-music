package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note

class StudioSoundTest {
    private val probe = Note("probe", 0, 65, 700, .9f)
    private val chart = Chart("Sound check", 120, 2000, listOf(probe))

    @Test fun gypsyRiffKeepsItsRoleAndChangesMaterialOnlyAtWrittenSections() {
        val song = requireNotNull(gypsyWomanSong.bundled)
        assertEquals(DanceLeadVoice.OrganStab, danceLeadVoice(DanceStyle.GypsyWoman, 0))
        assertEquals(DanceLeadVoice.OrganStab, danceLeadVoice(DanceStyle.GypsyWoman, 8_875))
        assertEquals(DanceLeadVoice.OrganStab, danceLeadVoice(DanceStyle.GypsyWoman, 36_000))
        assertEquals(DanceLeadVoice.ElectricPiano, danceLeadVoice(DanceStyle.GypsyWoman, 80_000))
        assertEquals(DanceLeadVoice.Mallet, danceLeadVoice(DanceStyle.GypsyWoman, 128_000))
        assertEquals(DanceLeadVoice.OrganStab, danceLeadVoice(DanceStyle.GypsyWoman, 144_000))
        assertTrue(song.notes.none { danceLeadVoice(DanceStyle.GypsyWoman, it.atMs) == DanceLeadVoice.Piano })

        val studio = DanceStudio(chart, DanceStyle.GypsyWoman)
        val organ = studio.leadSamples(probe.copy(atMs = 8_875))
        // Tonewheel body retains the sustain and audible drawbars, without metallic piano overtones.
        assertTrue(rms(organ, 300, 500) > rms(organ, 40, 140) * .55)
        val hz = 440.0 * 2.0.pow((probe.midi - 69) / 12.0)
        val third = partial(organ, hz * 3, 100, 300) / partial(organ, hz, 100, 300)
        assertTrue(third in .025.. .18)
    }

    @Test fun synthesizedVoicesKeepTheirAttackAndReleaseClickFree() {
        for (style in DanceStyle.entries) {
            val studio = DanceStudio(chart, style)
            for (at in listOf(0L, 8_875L, 36_000L, 80_000L, 96_000L, 128_000L)) {
                for (midi in listOf(45, 60, 74, 84)) {
                    val samples = studio.leadSamples(probe.copy(atMs = at, midi = midi))
                    assertTrue(samples.all { it.isFinite() })
                    assertTrue(abs(samples.first()) < .025f, "$style / $midi attack")
                    assertTrue(samples.last() == 0f, "$style / $midi release")
                    assertTrue(samples.maxOf { abs(it) } < 1.3f)
                    assertTrue(rms(samples, 40, 140) > .03, "$style / $midi audible body")
                    val gate = 700 * 32
                    assertTrue(abs(samples[gate] - samples[gate - 1]) < .35f, "$style / $midi note-off")
                }
            }
        }
    }

    @Test fun pianoDynamicsChangeBrightnessWithoutHarshUpperPartials() {
        assertEquals(DanceLeadVoice.PluckedString, danceLeadVoice(DanceStyle.Levels, 0))
        assertEquals(DanceLeadVoice.Synth, danceLeadVoice(DanceStyle.Levels, 8000))
        assertEquals(DanceLeadVoice.Piano, danceLeadVoice(DanceStyle.Levels, 96_000))
        val soft = studioPiano(65, 700, .35f)
        val firm = studioPiano(65, 700, .95f)
        val hz = 440.0 * 2.0.pow((65 - 69) / 12.0)
        val softBrightness = partial(soft, hz * 4, 25, 125) / partial(soft, hz, 25, 125)
        val firmBrightness = partial(firm, hz * 4, 25, 125) / partial(firm, hz, 25, 125)
        assertTrue(firmBrightness > softBrightness)
        assertTrue(firmBrightness < .20)
        assertTrue(rms(firm, 720, 770) > 0, "The damper retains a natural release")
        assertTrue(firm.last() == 0f)
    }

    private fun rms(samples: FloatArray, fromMs: Int, toMs: Int): Double {
        val from = fromMs * 32
        val to = toMs * 32
        return sqrt((from until to).sumOf { samples[it].toDouble().pow(2) } / (to - from))
    }

    private fun partial(samples: FloatArray, hz: Double, fromMs: Int, toMs: Int): Double {
        val from = fromMs * 32
        val to = toMs * 32
        var real = 0.0
        var imaginary = 0.0
        for (i in from until to) {
            val window = .5 - .5 * cos(2 * PI * (i - from) / (to - from - 1))
            val angle = 2 * PI * hz * i / 32_000
            real += samples[i] * window * cos(angle)
            imaginary += samples[i] * window * sin(angle)
        }
        return sqrt(real * real + imaginary * imaginary)
    }
}
