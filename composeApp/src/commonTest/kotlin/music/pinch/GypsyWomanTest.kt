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

    @Test fun theMasterAndTheCourseUseEveryScoredAttackExactlyOnce() {
        assertEquals(120, chart.bpm)
        assertEquals(228_000L, chart.durationMs)
        assertEquals(chart.durationMs, gypsyWomanSong.bundledDurationMs)
        assertEquals("backing/gypsy-woman.m4a", gypsyWomanSong.backingAsset)
        assertTrue(chart.authoredLandings)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.all { it.playable && it.atMs % 125 == 0L })
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
        assertTrue(chart.notes.last().atMs >= 226_000L)
        val keys = chart.accompaniment.filter { it.voice == BackingVoice.Keys }.map { it.atMs to it.midi }.toSet()
        assertTrue(chart.notes.none { it.atMs to it.midi in keys })
    }

    @Test fun openingPlaysTheSyncopatedChordsAndTheirTopVoiceOnlyOnce() {
        for (i in 0..3) {
            val line = bar(i)
            assertEquals(listOf(0L, 500L, 875L, 1250L, 1625L), line.map { it.atMs % 2000 })
            assertTrue(line.all { gypsyOrganLeadAt(it.atMs) })
        }
        val first = bar(0)
        assertEquals(listOf(64, 64, 65, 65, 65), first.map { it.midi })
        assertTrue(first.first().durationMs > first.last().durationMs)
        assertTrue(chart.accompaniment.filter { it.atMs < 8000L }.all { it.voice == BackingVoice.Keys })
        assertEquals(setOf(45, 55, 60), chart.accompaniment.filter { it.atMs == 0L }.map { it.midi }.toSet())
    }

    @Test fun theRefrainAnswersTheUpperDWithALowDAndTheVerseLetsERing() {
        for (number in listOf(18, 23, 38, 45, 60, 65, 88, 105)) {
            val refrain = bar(number)
            assertEquals(listOf(57, 57, 62, 57, 57, 50), refrain.map { it.midi })
            assertEquals(listOf(0L, 250L, 500L, 1000L, 1250L, 1500L), refrain.map { it.atMs % 2000 })
            assertTrue(refrain[2].durationMs > refrain[1].durationMs * 2)
        }
        val heldE = bar(26).single()
        assertEquals(64, heldE.midi)
        assertEquals(250L, heldE.atMs % 2000)
        assertTrue(heldE.durationMs >= 1450L)
        assertTrue(bar(27).all { gypsyOrganLeadAt(it.atMs) })
        assertTrue(bar(4).first().atMs > 8750L)
    }

    @Test fun theDrumDropoutAndOrganReturnKeepTheirOwnParts() {
        val kickBars = chart.accompaniment.filter { it.voice == BackingVoice.Kick }.groupBy { (it.atMs / 2000).toInt() }
        assertFalse(17 in kickBars)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), kickBars.getValue(18).map { it.atMs % 2000 })
        assertFalse(40 in kickBars)
        assertEquals(4, kickBars.getValue(44).size)
        assertTrue(bar(18).all { !gypsyOrganLeadAt(it.atMs) })
        assertTrue(bar(50).all { gypsyOrganLeadAt(it.atMs) })
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Pad })
    }
}
