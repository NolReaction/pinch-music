package music.pinch

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart
import music.pinch.core.hasGuideAfter

class OnTheFloorTest {
    private val chart = requireNotNull(onTheFloorSong.bundled)
    private fun time(tick: Int) = (tick * 15_000.0 / chart.bpm).roundToLong()
    private fun tick(atMs: Long) = (atMs * chart.bpm / 15_000.0).roundToInt()
    private fun notesInBar(bar: Int) = chart.notes.filter { tick(it.atMs) / 16 == bar }

    @Test fun everyMelodicAttackHasOneLandingAndUsesTheMasterGrid() {
        assertEquals("backing/on-the-floor.m4a", onTheFloorSong.backingAsset)
        assertEquals(130, chart.bpm)
        assertEquals(time(124 * 16) + 800, chart.durationMs)
        assertEquals(chart.durationMs, onTheFloorSong.bundledDurationMs)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertTrue(chart.notes.all { it.playable })
        assertEquals(chart.notes.size, chart.notes.distinctBy { it.atMs }.size)
        for (note in chart.notes) {
            assertEquals(time(tick(note.atMs)), note.atMs)
        }
        assertEquals(0L, chart.notes.first().atMs)
        assertEquals(time(123 * 16 + 8), chart.notes.last().atMs)
    }

    @Test fun theHookReturnsAfterTheVerseAndTheBreakContainsOnlyAccompaniment() {
        val hook = notesInBar(0)
        assertEquals(hook.map { it.midi }, notesInBar(48).map { it.midi })
        assertEquals(hook.map { tick(it.atMs) }, notesInBar(88).map { tick(it.atMs) - 88 * 16 })
        assertEquals(listOf(58, 70, 68, 66, 65, 63), hook.map { it.midi })
        assertTrue(notesInBar(16).map { it.midi } != notesInBar(40).map { it.midi })
        val soundChanges = listOf(0, 8, 16, 48, 96, 104, 112).map { floorLeadVoiceAt(time(it * 16)) }
        assertEquals(listOf(FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Vocal,
            FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Brass, FloorLeadVoice.Reed), soundChanges)

        val restBar = 99
        assertTrue(notesInBar(restBar).isEmpty())
        assertTrue(chart.accompaniment.any { tick(it.atMs) / 16 == restBar })
        val lastNoteBeforeBreak = chart.notes.indexOfLast { it.atMs < time(restBar * 16) }
        assertTrue(chart.hasGuideAfter(lastNoteBeforeBreak))
    }

    @Test fun bandVoicesRemainSeparateFromThePlayableMelody() {
        val band = chart.accompaniment
        assertEquals(BackingVoice.entries.toSet(), band.map { it.voice }.toSet())
        assertEquals(band.size, band.distinctBy { Triple(it.voice, it.atMs, it.midi) }.size)
        assertEquals(setOf(42, 46, 70), band.filter { it.voice == BackingVoice.Hat }.map { it.midi }.toSet())
        assertEquals(setOf(37, 39), band.filter { it.voice == BackingVoice.Snare }.map { it.midi }.toSet())
        val chorus = band.filter { it.atMs in time(40 * 16) until time(48 * 16) }
        val breakdown = band.filter { it.atMs in time(96 * 16) until time(104 * 16) }
        assertTrue(chorus.count { it.voice == BackingVoice.Kick } > breakdown.count { it.voice == BackingVoice.Kick })
    }
}
