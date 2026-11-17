package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart

class TurnDownForWhatTest {
    private val chart = requireNotNull(turnDownForWhatSong.bundled)
    private fun bar(index: Int) = chart.notes.filter { it.atMs / 2400 == index.toLong() }

    @Test fun fullInstrumentalEditHasOneLandingPerWrittenLeadAttack() {
        assertEquals(100, chart.bpm)
        assertEquals(153_600L, chart.durationMs)
        assertEquals(4, turnDownForWhatSong.bundledRevision)
        assertEquals("backing/turn-down-for-what.m4a", turnDownForWhatSong.backingAsset)
        assertEquals(chart.durationMs, turnDownForWhatSong.bundledDurationMs)
        assertTrue(chart.authoredLandings && chart.notes.all { it.playable })
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertEquals(0L, chart.notes.first().atMs)
        assertEquals(151_200L, chart.notes.last().atMs)
        assertTrue(chart.notes.size > 300)
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 150 })
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> a.atMs + a.durationMs <= b.atMs })
    }

    @Test fun sourceHookRetainsItsSemitoneRhythmAndOctaveResponses() {
        val onsets = listOf(0L, 450L, 900L, 1200L, 1500L, 1800L, 2100L)
        for (first in listOf(8, 40)) {
            assertEquals(onsets, bar(first).map { it.atMs % 2400 })
            assertEquals(listOf(64, 64, 65, 64, 64, 65, 62), bar(first).map { it.midi })
            assertEquals(listOf(52, 52, 53, 52, 52, 52, 53), bar(first + 1).map { it.midi })
            assertEquals(listOf(52, 52, 53, 52, 52, 53, 50), bar(first + 2).map { it.midi })
            assertEquals(listOf(0, 1, 2, 3, 6, 8, 10, 11, 12, 14),
                bar(first + 4).map { (it.atMs % 2400 / 150).toInt() })
        }
        assertTrue(chart.notes.none { it.midi % 12 == 6 }, "The demonstrated hook uses F natural, not F sharp")
    }

    @Test fun callsBreakAndBuildChangeRhythmWithoutLosingTheMotif() {
        assertEquals(TurnDownPart.Intro, turnDownPartAt(0))
        assertEquals(TurnDownPart.Call, turnDownPartAt(9_600))
        assertEquals(TurnDownPart.Drop, turnDownPartAt(19_200))
        assertEquals(TurnDownPart.Break, turnDownPartAt(57_600))
        assertEquals(TurnDownPart.Build, turnDownPartAt(76_800))
        assertEquals(TurnDownPart.Drop, turnDownPartAt(96_000))
        assertEquals(TurnDownPart.Outro, turnDownPartAt(134_400))
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12),
            bar(4).map { (it.atMs % 2400 / 150).toInt() })
        assertEquals(listOf(1500L, 1800L, 2100L, 2250L), bar(7).map { it.atMs % 2400 })
        assertTrue(bar(7).all { it.midi == 67 })
        val rhythms = (0 until 64).map { index -> bar(index).map { it.atMs % 2400 } }.toSet()
        assertTrue(rhythms.size >= 10, "The entire course must not collapse into the seven-note drop loop")
        val drop = chart.notes.count { it.atMs in 19_200 until 38_400 }
        val sparse = chart.notes.count { it.atMs in 57_600 until 76_800 }
        assertTrue(sparse < drop * .6)
        assertTrue(chart.accompaniment.none { it.voice == BackingVoice.Kick && it.atMs in 57_600 until 76_800 })
        assertTrue(chart.accompaniment.filter { it.voice == BackingVoice.Bass }.all { it.midi == 40 })
    }

    @Test fun finePercussionRemainsBackingAndOutroEndsWithSpace() {
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Hat && it.atMs % 150 != 0L })
        assertTrue(chart.notes.all { it.atMs % 150 == 0L })
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Snare && it.atMs in 86_400 until 96_000 })
        assertEquals(1500L, chart.notes.last().durationMs)
        assertTrue(chart.notes.last().velocity < .3f)
        assertTrue(chart.accompaniment.none { it.voice == BackingVoice.Kick && it.atMs >= 148_800 })
        assertTrue(chart.notes.last().atMs + chart.notes.last().durationMs < chart.durationMs - 500)
    }
}
