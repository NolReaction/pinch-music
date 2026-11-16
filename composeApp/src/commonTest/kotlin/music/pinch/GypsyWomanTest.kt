package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart

class GypsyWomanTest {
    private val chart = requireNotNull(gypsyWomanSong.bundled)
    private fun bar(number: Int) = chart.notes.filter { it.atMs / 2000 == number.toLong() }
    private fun chord(atMs: Long) = chart.notes.filter { it.atMs == atMs }.map { it.midi }.toSet() +
        chart.accompaniment.filter { it.atMs == atMs && it.voice == BackingVoice.Keys }.map { it.midi }

    @Test fun theMasterAndTheCourseUseEveryForegroundAttackExactlyOnce() {
        assertEquals(120, chart.bpm)
        assertEquals(228_000L, chart.durationMs)
        assertEquals(5, gypsyWomanSong.bundledRevision)
        assertEquals(chart.durationMs, gypsyWomanSong.bundledDurationMs)
        assertEquals("backing/gypsy-woman.m4a", gypsyWomanSong.backingAsset)
        assertTrue(chart.authoredLandings)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertEquals(550, chart.notes.size)
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.all { it.playable && it.atMs % 125 == 0L })
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        assertEquals(226_000L, chart.notes.last().atMs)
        val keys = chart.accompaniment.filter { it.voice == BackingVoice.Keys }.map { it.atMs to it.midi }.toSet()
        assertTrue(chart.notes.none { it.atMs to it.midi in keys })
    }

    @Test fun theOpeningAndNineSecondRegionKeepTheSameOrganRiff() {
        for (number in 0..7) {
            val line = bar(number)
            assertEquals(listOf(0L, 500L, 875L, 1250L, 1625L), line.map { it.atMs % 2000 })
            assertTrue(line.all { gypsyTextureAt(it.atMs) == GypsyTexture.Organ })
        }
        assertEquals(listOf(64, 64, 62, 62, 62), bar(0).map { it.midi })
        assertEquals(bar(0).map { it.midi }, bar(4).map { it.midi })
        assertEquals(setOf(50, 53, 57, 60, 64), chord(0))
        assertEquals(setOf(50, 53, 57, 60, 62), chord(875))
        assertTrue(chart.accompaniment.filter { it.atMs < 8000L }.all { it.voice == BackingVoice.Keys })
    }

    @Test fun dominantAndTurnaroundHaveNoPermanentFlatNineOrRegisterJump() {
        val dominant = chord(3 * 2000L + 875)
        assertEquals(setOf(48, 55, 58, 64), dominant)
        assertFalse(dominant.any { it % 12 == 1 }, "C# was a tutorial improvisation, not a fixed C7 tone")
        assertEquals(setOf(52, 55, 59, 62), chord(7 * 2000L))
        assertEquals(setOf(51, 55, 58, 62), chord(7 * 2000L + 875))
        assertTrue(bar(7).all { it.midi == 62 })
        assertTrue(chart.notes.all { it.midi in 62..65 })
    }

    @Test fun eightBarSectionsHaveIntentionalInstrumentationAndSpace() {
        assertEquals(GypsyTexture.ElectricPiano, gypsyTextureAt(80_000))
        assertEquals(GypsyTexture.Mallet, gypsyTextureAt(128_000))
        assertEquals(GypsyTexture.Organ, gypsyTextureAt(144_000))
        for (number in 64..71) assertEquals(listOf(0L, 875L, 1625L), bar(number).map { it.atMs % 2000 })
        val kickBars = chart.accompaniment.filter { it.voice == BackingVoice.Kick }.groupBy { (it.atMs / 2000).toInt() }
        assertFalse(7 in kickBars)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), kickBars.getValue(8).map { it.atMs % 2000 })
        assertFalse(40 in kickBars)
        assertFalse(64 in kickBars)
        assertEquals(4, kickBars.getValue(72).size)
        assertFalse(110 in kickBars)
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Pad && gypsyColourAt(it.atMs) == GypsyColour.Plucked })
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Pad && gypsyColourAt(it.atMs) == GypsyColour.Mallet })
        assertTrue(bar(111).first().velocity < bar(104).first().velocity * .3f)
        assertEquals(setOf(50, 53, 57, 60, 64), chord(226_000))
        assertEquals(1, bar(113).size)
        assertEquals(1450L, bar(113).single().durationMs)
    }
}
