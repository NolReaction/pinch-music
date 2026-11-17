package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart

class GypsyWomanTest {
    private val chart = requireNotNull(gypsyWomanSong.bundled)
    private fun bar(number: Int) = chart.notes.filter { it.atMs / 2000 == number.toLong() }
    private fun phrase(number: Int) = bar(number).map { Triple((it.atMs % 2000 / 125).toInt(), it.midi, (it.durationMs + 18) / 125) }
    private fun keys(atMs: Long) = chart.accompaniment
        .filter { it.atMs == atMs && it.voice == BackingVoice.Keys }.map { it.midi }.toSet()

    @Test fun theMasterAndTheCourseUseEveryForegroundAttackExactlyOnce() {
        assertEquals(120, chart.bpm)
        assertEquals(204_000L, chart.durationMs)
        assertEquals(6, gypsyWomanSong.bundledRevision)
        assertEquals(chart.durationMs, gypsyWomanSong.bundledDurationMs)
        assertEquals("backing/gypsy-woman.m4a", gypsyWomanSong.backingAsset)
        assertTrue(chart.authoredLandings)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertEquals(405, chart.notes.size)
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.all { it.playable && it.atMs % 125 == 0L })
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        assertEquals(202_000L, chart.notes.last().atMs)
        val backingAttacks = chart.accompaniment.filter { it.voice == BackingVoice.Keys }.map { it.atMs to it.midi }.toSet()
        assertTrue(chart.notes.none { it.atMs to it.midi in backingAttacks })
    }

    @Test fun melodyEntersAtEightSecondsAndPreservesTheDemonstratedPhrasesAndRests() {
        assertEquals(listOf(0L, 500L, 875L, 1250L, 1625L), bar(0).map { it.atMs % 2000 })
        assertEquals(GypsyTexture.Organ, gypsyTextureAt(0))
        assertEquals(GypsyTexture.ElectricPiano, gypsyTextureAt(8000))
        assertEquals(listOf(Triple(0, 62, 4L), Triple(4, 60, 4L), Triple(12, 65, 4L)), phrase(4))
        assertEquals(listOf(Triple(0, 62, 4L), Triple(4, 60, 4L)), phrase(5))
        assertEquals(listOf(Triple(2, 64, 2L), Triple(4, 65, 2L), Triple(8, 65, 1L),
            Triple(10, 65, 2L), Triple(12, 64, 2L), Triple(14, 65, 2L)), phrase(6))
        assertEquals(listOf(Triple(0, 64, 5L), Triple(8, 64, 1L), Triple(10, 64, 2L)), phrase(7))
        assertTrue(bar(6).first().atMs - (bar(5).last().atMs + bar(5).last().durationMs) >= 1200,
            "The written rest must not be filled with five playable chord stabs")
        assertFalse(phrase(4) == phrase(0))
    }

    @Test fun everyRefrainKeepsTheChromaticPickupAndOctaveAnswerOverTheRightHarmony() {
        val hook = listOf(Triple(0, 62, 4L), Triple(4, 56, 2L), Triple(6, 57, 2L),
            Triple(8, 50, 4L), Triple(12, 56, 2L), Triple(14, 57, 2L))
        for (start in listOf(20, 44, 84)) {
            assertEquals(hook, phrase(start))
            assertEquals(listOf(56, 57), bar(start - 1).takeLast(2).map { it.midi })
            assertEquals(setOf(50, 53, 57, 60, 64), keys(start * 2000L))
            // Source prechorus: Dm, Dm, Am/Bb, Em/Eb, then the refrain resolves to Dm.
            assertEquals(setOf(50, 53, 57, 60, 64), keys((start - 4) * 2000L))
            assertEquals(setOf(45, 55, 60, 64), keys((start - 2) * 2000L))
            assertEquals(setOf(52, 55, 59, 62), keys((start - 1) * 2000L))
            assertEquals(setOf(51, 55, 58, 62), keys((start - 1) * 2000L + 875))
            assertEquals(2, bar(start + 6).size, "The hook closes with a less dense answer")
            assertEquals(4, bar(start + 7).size)
        }
    }

    @Test fun versesDevelopTheMelodyInsteadOfRepeatingOneChordTopPattern() {
        val verse = (4..19).map(::phrase)
        assertTrue(verse.distinct().size >= 10)
        val rhythms = (4..99).map { number -> bar(number).map { it.atMs % 2000 to it.durationMs } }.distinct()
        assertTrue(rhythms.size >= 20)
        assertEquals(setOf(50, 56, 57, 60, 62, 64, 65), chart.notes.map { it.midi }.toSet())
        assertNotEquals((8..13).map(::phrase), (32..37).map(::phrase))
        assertNotEquals((8..13).map(::phrase), (72..77).map(::phrase))
        for (start in listOf(4, 28, 68)) {
            assertEquals(phrase(4), phrase(start + 12))
            assertEquals(phrase(5), phrase(start + 13))
            assertEquals(listOf(65, 64, 65, 65), bar(start + 14).map { it.midi })
            assertTrue(bar(start + 15).all { gypsyTextureAt(it.atMs) == GypsyTexture.ElectricPiano })
        }
    }

    @Test fun interludeBreakdownAndOutroHaveDifferentDensityAndNaturalEndings() {
        assertEquals(GypsyTexture.Organ, gypsyTextureAt(104_000))
        assertEquals(GypsyTexture.Mallet, gypsyTextureAt(120_000))
        assertEquals(GypsyTexture.ElectricPiano, gypsyTextureAt(136_000))
        assertEquals(5, bar(52).size)
        assertEquals(2, bar(60).size)
        assertEquals(1, bar(61).size)
        val kickBars = chart.accompaniment.filter { it.voice == BackingVoice.Kick }.groupBy { (it.atMs / 2000).toInt() }
        assertFalse(7 in kickBars)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), kickBars.getValue(8).map { it.atMs % 2000 })
        assertFalse(60 in kickBars)
        assertFalse(67 in kickBars)
        assertEquals(4, kickBars.getValue(68).size)
        assertFalse(96 in kickBars)
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Pad && gypsyColourAt(it.atMs) == GypsyColour.Plucked })
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Pad && gypsyColourAt(it.atMs) == GypsyColour.Mallet })
        assertTrue(bar(99).first().velocity < bar(92).first().velocity * .4f)
        assertEquals(1, bar(101).size)
        assertEquals(1450L, bar(101).single().durationMs)
    }
}
