package music.pinch

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.cascadeChart

class StayTest {
    private val chart = requireNotNull(staySong.bundled)
    private fun tick(atMs: Long) = (atMs * 170 / 15_000.0).roundToInt()
    private fun time(tick: Int) = (tick * 15_000.0 / 170).roundToLong()
    private fun bar(number: Int) = chart.notes.filter { tick(it.atMs) / 16 == number }
    private fun melody(number: Int) = bar(number).map { tick(it.atMs) % 16 to it.midi }

    @Test fun completeScoreAndMasterShareEveryPlayableAttack() {
        assertEquals(170, chart.bpm)
        assertEquals(135_529L, chart.durationMs)
        assertEquals(5, staySong.bundledRevision)
        assertEquals("backing/stay.m4a", staySong.backingAsset)
        assertEquals(chart.durationMs, staySong.bundledDurationMs)
        assertTrue(chart.authoredLandings)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.all { it.playable })
        assertTrue(chart.notes.zipWithNext().all { (a, b) ->
            b.atMs - a.atMs >= 176 && a.atMs + a.durationMs <= b.atMs
        })
        assertTrue(chart.notes.last().atMs + chart.notes.last().durationMs < chart.durationMs - 450)
    }

    @Test fun chorusKeepsTheDemonstratedPickupRepeatedResponseAndHeldClosingAnswer() {
        val call = listOf(0 to 60, 2 to 61, 4 to 61, 6 to 68, 12 to 60, 14 to 60)
        val reply = listOf(0 to 60, 2 to 60, 4 to 60, 6 to 60, 8 to 60, 10 to 61, 12 to 61, 14 to 60)
        for (start in listOf(8, 48, 72)) {
            assertEquals(call, melody(start))
            assertEquals(reply, melody(start + 1))
            assertEquals(listOf(0 to 63, 2 to 72, 8 to 68), melody(start + 7))
            assertTrue(bar(start + 7).last().durationMs > 650)
        }
        assertEquals(listOf(0 to 66, 2 to 60, 4 to 61, 6 to 68, 12 to 61), melody(0))
        assertEquals(listOf(4 to 60, 6 to 61, 8 to 60, 10 to 56, 12 to 58, 14 to 56), melody(3))
    }

    @Test fun harmonyAndTheMainBackbeatAgreeWithThePublicDemo() {
        for (start in listOf(8, 24, 48, 72)) {
            for ((phase, bass) in listOf(42, 44, 46, 41).withIndex()) {
                val at = time((start + phase) * 16)
                assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Bass && it.atMs == at && it.midi == bass })
            }
        }
        val snare = chart.accompaniment.filter { it.voice == BackingVoice.Snare && it.midi == 38 && tick(it.atMs) / 16 == 8 }
        assertEquals(listOf(4, 12), snare.map { tick(it.atMs) % 16 })
        val kick = chart.accompaniment.filter { it.voice == BackingVoice.Kick && tick(it.atMs) / 16 == 8 }
        assertEquals(listOf(0, 8), kick.map { tick(it.atMs) % 16 })
    }

    @Test fun verseAndBreakOfferRealPhrasesAndBreathingRoom() {
        assertEquals(listOf(2 to 65, 4 to 65, 6 to 65, 8 to 65, 10 to 61, 12 to 61, 14 to 61), melody(24))
        assertEquals(listOf(0 to 61, 2 to 63, 4 to 63), melody(25))
        assertTrue((24..39).map(::melody).distinct().size >= 13)
        val barRhythms = (0 until 96).map { number -> bar(number).map { tick(it.atMs) % 16 } }
        assertTrue(barRhythms.distinct().size >= 20)
        assertTrue((64..67).sumOf { bar(it).size } < (8..11).sumOf { bar(it).size } / 2)
        assertFalse(chart.accompaniment.any { it.voice == BackingVoice.Kick && tick(it.atMs) / 16 in 64..67 })
        assertTrue(bar(26).first().atMs - (bar(25).last().atMs + bar(25).last().durationMs) > 800)
    }

    @Test fun endingResolvesAndFadesWithoutAddingUnplayableNotes() {
        assertEquals(listOf(0 to 61), melody(95))
        assertTrue(bar(95).single().velocity < bar(88).first().velocity * .45f)
        assertTrue(chart.accompaniment.any { it.voice == BackingVoice.Bass && it.midi == 37 && tick(it.atMs) / 16 == 95 })
        assertFalse(chart.accompaniment.any { it.voice == BackingVoice.Kick && tick(it.atMs) / 16 >= 92 })
        assertEquals(StayPart.Intro, stayPartAt(0))
        assertEquals(StayPart.Chorus, stayPartAt(time(8 * 16)))
        assertEquals(StayPart.Breakdown, stayPartAt(time(64 * 16)))
        assertEquals(StayPart.Finale, stayPartAt(time(72 * 16)))
    }
}
