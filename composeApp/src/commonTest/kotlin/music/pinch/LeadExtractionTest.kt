package music.pinch

import kotlin.math.*
import kotlin.test.*

class LeadExtractionTest {
    @Test fun preservesPitchPhraseAndEnforcesComfortablePacing() {
        val rate = 22050
        val expected = listOf(60, 64, 67, 67, 69, 65, 62, 60)
        val samples = FloatArray(expected.size * rate)
        for ((index, pitch) in expected.withIndex()) {
            val freq = 440 * 2.0.pow((pitch - 69) / 12.0)
            for (i in 0 until rate * 2 / 3) {
                val t = i.toDouble() / rate
                samples[index * rate + i] = (0.6 * exp(-2 * t) * (sin(2 * PI * freq * t) +
                    0.28 * sin(4 * PI * freq * t))).toFloat()
            }
        }
        val chart = extractLeadChart(samples, rate, "Фраза")
        assertEquals(expected, chart.notes.map { it.midi })
        assertTrue(chart.notes.zipWithNext().all { (a,b) -> b.atMs - a.atMs >= 579 })
        assertTrue(chart.notes.first().atMs >= 2000)
    }
    @Test fun refusesQuietAudioAndIsolatedDrumLikeClicks() {
        val clicks = FloatArray(11025 * 3) { if (it % 2800 == 0) 1f else 0f }
        assertFailsWith<IllegalArgumentException> { extractLeadChart(clicks, 11025, "Щелчки") }
        assertFailsWith<IllegalArgumentException> { extractLeadChart(FloatArray(22050), 11025, "Тишина") }
    }
}
