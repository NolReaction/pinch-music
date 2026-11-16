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
    private fun downbeat(bar: Int) = 4 + bar * 16
    private fun notesInBar(bar: Int) = chart.notes.filter { tick(it.atMs) in downbeat(bar) until downbeat(bar + 1) }

    @Test fun everyMelodicAttackHasOneLandingAndUsesTheMasterGrid() {
        assertEquals("backing/on-the-floor.m4a", onTheFloorSong.backingAsset)
        assertEquals(130, chart.bpm)
        assertEquals(time(downbeat(124)) + 800, chart.durationMs)
        assertEquals(chart.durationMs, onTheFloorSong.bundledDurationMs)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertTrue(chart.notes.all { it.playable })
        assertEquals(chart.notes.size, chart.notes.distinctBy { it.atMs }.size)
        for (note in chart.notes) {
            assertEquals(time(tick(note.atMs)), note.atMs)
        }
        assertEquals(0L, chart.notes.first().atMs)
        assertEquals(time(downbeat(123) + 8), chart.notes.last().atMs)
    }

    @Test fun theOpeningPickupLeadsIntoTheDownbeatAndTheAnswerHoldsItsLastPitch() {
        val pickup = chart.notes.first()
        val hook = notesInBar(0)
        assertEquals(58, pickup.midi)
        assertEquals(70, hook.first().midi)
        assertEquals(time(4), hook.first().atMs)
        assertTrue(chart.accompaniment.none { it.atMs < hook.first().atMs })
        assertEquals(listOf(0, 6, 8, 10, 12), hook.map { tick(it.atMs) - downbeat(0) })
        val eighths = notesInBar(1)
        assertEquals((0..14 step 2).toList(), eighths.map { tick(it.atMs) - downbeat(1) })
        val cadence = notesInBar(2)
        assertEquals(listOf(0, 3, 4), cadence.map { tick(it.atMs) - downbeat(2) })
        assertEquals(listOf(58, 56, 58), cadence.map { it.midi })
        assertTrue(cadence.last().durationMs > time(11))
        assertTrue(chart.hasGuideAfter(chart.notes.indexOf(cadence.last())))
    }

    @Test fun theSungChorusAndInstrumentalRepriseShareTheHook() {
        fun phrase(bar: Int) = (bar until bar + 3).flatMap(::notesInBar).map {
            tick(it.atMs) - downbeat(bar) to it.midi
        }
        for (bar in listOf(4, 44, 54, 82, 92, 110, 114, 118)) assertEquals(phrase(0), phrase(bar))
        assertTrue(notesInBar(24).map { it.midi } != notesInBar(44).map { it.midi })
        val soundChanges = listOf(0, 8, 24, 44, 54, 100, 108, 110).map { floorLeadVoiceAt(time(downbeat(it))) }
        assertEquals(listOf(FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Vocal,
            FloorLeadVoice.Vocal, FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Brass,
            FloorLeadVoice.Reed), soundChanges)

        val restBar = 103
        assertTrue(notesInBar(restBar).isEmpty())
        assertTrue(chart.accompaniment.any { tick(it.atMs) in downbeat(restBar) until downbeat(restBar + 1) })
        val lastNoteBeforeBreak = chart.notes.indexOfLast { it.atMs < time(downbeat(restBar)) }
        assertTrue(chart.hasGuideAfter(lastNoteBeforeBreak))
    }

    @Test fun bandVoicesRemainSeparateFromThePlayableMelody() {
        val band = chart.accompaniment
        assertEquals(BackingVoice.entries.toSet(), band.map { it.voice }.toSet())
        assertEquals(band.size, band.distinctBy { Triple(it.voice, it.atMs, it.midi) }.size)
        assertEquals(setOf(42, 46, 70), band.filter { it.voice == BackingVoice.Hat }.map { it.midi }.toSet())
        assertEquals(setOf(37, 39), band.filter { it.voice == BackingVoice.Snare }.map { it.midi }.toSet())
        val chorus = band.filter { it.atMs in time(downbeat(44)) until time(downbeat(52)) }
        val breakdown = band.filter { it.atMs in time(downbeat(100)) until time(downbeat(108)) }
        assertTrue(chorus.count { it.voice == BackingVoice.Kick } > breakdown.count { it.voice == BackingVoice.Kick })
    }
}
