package music.pinch

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart
import music.pinch.core.hasGuideAfter

class LevelsTest {
    private val chart = requireNotNull(levelsSong.bundled)
    private fun time(beat: Double) = (beat * 60_000.0 / chart.bpm).roundToLong()
    private fun between(from: Double, until: Double) = chart.notes.filter { it.atMs in time(from) until time(until) }

    @Test fun theCompleteRecordingAndItsPlayableMapShareOneTimeline() {
        assertEquals(126, chart.bpm)
        assertEquals("backing/levels.m4a", levelsSong.backingAsset)
        assertEquals(time(416.0) + 1800, chart.durationMs)
        assertEquals(chart.durationMs, levelsSong.bundledDurationMs)
        assertTrue(chart.durationMs in 199_000L..201_000L)
        assertTrue(chart.notes.size > 600)
        assertEquals(0L, chart.notes.first().atMs)
        assertEquals(time(416.0), chart.notes.last().atMs)
        assertTrue(chart.notes.all { it.playable && it.durationMs > 0 && it.atMs + it.durationMs < chart.durationMs })
        assertEquals(chart.notes.size, chart.notes.distinctBy { it.atMs }.size)
        assertEquals(chart.notes, chart.cascadeChart().notes)
    }

    @Test fun theSyncopatedHookRepeatsBothCadencesWithoutFillingItsRests() {
        val phrase = between(32.0, 48.0)
        assertEquals(listOf(66,64,64,64,64,64,64,63,63,64,64,73,71,68,
            66,64,64,64,64,64,64,61,61,59,59,73,71,68), phrase.map { it.midi })
        val offsets = listOf(0.0,.5,1.0,2.0,2.5,3.0,3.5,4.0,4.5,5.0,5.5,6.5,7.0,7.5,
            8.0,8.5,9.0,10.0,10.5,11.0,11.5,12.0,12.5,13.0,13.5,14.5,15.0,15.5)
        assertEquals(offsets.map { time(32 + it) }, phrase.map { it.atMs })
        for (bar in (8 until 40 step 4) + (68 until 104 step 4)) {
            val at = bar * 4.0
            val next = between(at, at + 16)
            assertEquals(phrase.map { it.midi }, next.map { it.midi })
            assertEquals(offsets.map { time(at + it) }, next.map { it.atMs })
        }
    }

    @Test fun theFourChordRootsChangeEachHalfBar() {
        val bass = chart.accompaniment.filter { it.voice == BackingVoice.Bass }
        assertEquals(listOf(37,40,35,33), listOf(32.5,34.5,36.5,38.5).map { beat ->
            bass.single { it.atMs == time(beat) }.midi
        })
        val playable = chart.notes.map { it.atMs to it.midi }.toSet()
        assertFalse(chart.accompaniment.any {
            it.voice == BackingVoice.Keys && (it.atMs to it.midi) in playable
        })
        assertEquals(chart.accompaniment.size,
            chart.accompaniment.distinctBy { Triple(it.atMs, it.midi, it.voice) }.size)
    }

    @Test fun theGospelBreakRetainsLongNotesRestsAndTheDifferentSecondEnding() {
        val voice = between(176.0, 240.0)
        assertEquals(86, voice.size)
        assertEquals(listOf(80,78,76,73,71,68,66,64), voice.take(8).map { it.midi })
        assertEquals(64, voice.minOf { it.midi })
        assertEquals(85, voice.maxOf { it.midi })
        assertTrue(voice.all { levelsSoftLeadAt(it.atMs) })
        assertFalse(levelsSoftLeadAt(time(32.0)))
        assertFalse(levelsSoftLeadAt(time(272.0)))
        val suspended = chart.notes.indexOfFirst { it.atMs == time(16.0) }
        assertTrue(chart.hasGuideAfter(suspended))
        assertTrue(chart.notes[suspended].durationMs > time(15.0))
        val kicks = chart.accompaniment.filter { it.voice == BackingVoice.Kick }
        assertFalse(kicks.any { it.atMs in time(160.0) until time(240.0) })
        assertTrue(kicks.count { it.atMs in time(272.0) until time(288.0) } >= 16)
    }
}
