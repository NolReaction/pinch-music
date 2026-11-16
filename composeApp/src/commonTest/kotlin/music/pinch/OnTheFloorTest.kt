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
        for (bar in listOf(4, 44, 82)) assertEquals(phrase(0), phrase(bar))
        for (bar in listOf(54, 58, 92, 96, 110, 114, 118)) {
            assertEquals(phrase(0), phrase(bar).filter { it.first < 38 })
            val overlap = notesInBar(bar + 2)
            assertEquals(time(8), overlap.single { tick(it.atMs) == downbeat(bar + 2) + 4 }.durationMs)
            val upperVoice = overlap.drop(3)
            assertEquals(listOf(6, 8, 10, 12, 14), upperVoice.map { tick(it.atMs) - downbeat(bar + 2) })
            assertTrue(upperVoice.all { it.midi == 63 && floorLeadVoiceAt(it.atMs) == FloorLeadVoice.Vocal })
        }
        assertTrue(notesInBar(24).map { it.midi } != notesInBar(44).map { it.midi })
        val soundChanges = listOf(0, 8, 24, 44, 54, 100, 108, 110).map { floorLeadVoiceAt(time(downbeat(it))) }
        assertEquals(listOf(FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Vocal,
            FloorLeadVoice.Vocal, FloorLeadVoice.Reed, FloorLeadVoice.Pluck, FloorLeadVoice.Pluck,
            FloorLeadVoice.Reed), soundChanges)

        val restBar = 103
        assertTrue(notesInBar(restBar).isEmpty())
        assertTrue(chart.accompaniment.any { tick(it.atMs) in downbeat(restBar) until downbeat(restBar + 1) })
        val lastNoteBeforeBreak = chart.notes.indexOfLast { it.atMs < time(downbeat(restBar)) }
        assertTrue(chart.hasGuideAfter(lastNoteBeforeBreak))
    }

    @Test fun spokenPassageFollowsTheOctavesAndLeavesTheRestOnBeatThree() {
        val firstTwoBars = (8..9).map(::notesInBar)
        assertEquals(listOf(51, 63), firstTwoBars.map { it.first().midi })
        for ((index, notes) in firstTwoBars.withIndex()) {
            assertEquals(listOf(0, 3, 6, 8, 11, 14), notes.map { tick(it.atMs) - downbeat(8 + index) })
            assertTrue(notes.all { it.midi == notes.first().midi })
        }
        val answer = notesInBar(10)
        assertEquals(listOf(0 to 63, 3 to 63, 6 to 63, 12 to 75, 14 to 73),
            answer.map { tick(it.atMs) - downbeat(10) to it.midi })
        assertTrue(answer[2].atMs + answer[2].durationMs <= time(downbeat(10) + 8))
        val spoken = chart.accompaniment.filter { it.atMs in time(downbeat(10)) until time(downbeat(24)) }
        assertTrue(spoken.none { it.voice == BackingVoice.Keys || it.voice == BackingVoice.Pad })
        val bass = spoken.filter { it.voice == BackingVoice.Bass }.associateBy { it.atMs }
        for (note in (10 until 24).flatMap(::notesInBar)) assertEquals(note.midi - 24, bass[note.atMs]?.midi)
    }

    @Test fun verseBeginsWithThePickupAndKeepsTheRestAfterTheResponse() {
        for (bar in listOf(24, 62)) {
            val entry = notesInBar(bar)
            assertEquals(listOf(2, 4, 6, 8, 10, 12, 14), entry.map { tick(it.atMs) - downbeat(bar) })
            assertEquals(listOf(61, 63, 63, 63, 63, 63, 63), entry.map { it.midi })
            val reply = notesInBar(bar + 1)
            assertEquals(listOf(0, 3, 6, 8), reply.map { tick(it.atMs) - downbeat(bar + 1) })
            assertTrue(reply[1].durationMs > time(2))
            assertTrue(reply.last().atMs + reply.last().durationMs <= time(downbeat(bar + 1) + 12))
        }
    }

    @Test fun bandVoicesRemainSeparateFromThePlayableMelody() {
        val band = chart.accompaniment
        assertEquals(BackingVoice.entries.toSet(), band.map { it.voice }.toSet())
        assertEquals(band.size, band.distinctBy { Triple(it.voice, it.atMs, it.midi) }.size)
        assertEquals(setOf(42, 46, 70), band.filter { it.voice == BackingVoice.Hat }.map { it.midi }.toSet())
        assertEquals(setOf(37, 39), band.filter { it.voice == BackingVoice.Snare }.map { it.midi }.toSet())
        val chorus = band.filter { it.atMs in time(downbeat(44)) until time(downbeat(52)) }
        val dance = band.filter { it.atMs in time(downbeat(54)) until time(downbeat(62)) }
        assertTrue(chorus.none { it.voice == BackingVoice.Kick })
        assertTrue(chorus.any { it.voice == BackingVoice.Keys })
        assertTrue(dance.count { it.voice == BackingVoice.Kick } >= 30)
    }
}
