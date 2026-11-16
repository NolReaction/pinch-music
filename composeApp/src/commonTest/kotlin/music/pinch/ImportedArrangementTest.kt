package music.pinch

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

class ImportedArrangementTest {
    private fun phrase(times: List<Long>, pitches: List<Int> = times.indices.map { 60 + it % 5 }): Chart =
        Chart("Imported phrase", 120, times.last() + 800, times.mapIndexed { index, time ->
            Note("source-$index", time, pitches[index], minOf(180, (times.getOrNull(index + 1) ?: (time + 180)) - time), .8f)
        })

    @Test fun fitsSmallTimingErrorsWithoutChangingTheSourceOrLosingPlayableAttacks() {
        val jitter = listOf(0L, 12, -9, 6, -4, 15, -11, 3, 0, 8, -6, 2)
        val original = phrase(jitter.mapIndexed { index, offset -> 250L + index * 250 + offset })
        val before = original.copy(notes = original.notes.toList())
        val adapted = adaptImportedChart(original)
        assertEquals(before, original)
        assertEquals(original.durationMs, adapted.chart.durationMs)
        assertEquals(original.notes.map { it.midi }, adapted.chart.notes.map { it.midi })
        assertTrue(adapted.rhythmConfidence > .85f)
        assertTrue(adapted.quantizedNotes >= 8)
        assertTrue(adapted.chart.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs == 250L })
        assertTrue(adapted.chart.notes.count { it.playable } >= 8)
        assertTrue(adapted.chart.accompaniment.any { it.voice == BackingVoice.Bass })
        assertTrue(adapted.chart.accompaniment.any { it.voice == BackingVoice.Kick })
    }

    @Test fun keepsTripletPhrasingAndDoesNotAddStraightOffbeatHats() {
        val source = phrase((0 until 15).map { 300 + (it * 500.0 / 3).roundToLong() })
        val adapted = adaptImportedChart(source)
        assertTrue(adapted.rhythmConfidence > .95f)
        assertTrue(adapted.chart.notes.zip(source.notes).all { (a, b) -> abs(a.atMs - b.atMs) <= 2 })
        val hats = adapted.chart.accompaniment.filter { it.voice == BackingVoice.Hat }
        assertTrue(hats.isNotEmpty())
        assertTrue(hats.all { hat ->
            val position = (hat.atMs - 300) % 500
            position <= 2 || position in 331..335
        })
    }

    @Test fun resolvesAHalfTimeDetectionOnlyWhenTheMelodyClearlyFitsTheFasterPulse() {
        val jitter = listOf(0L, 9, -8, 4, -5)
        val source = phrase((0 until 20).map { 200L + it * 250 + jitter[it % jitter.size] }).copy(bpm = 60)
        val adapted = adaptImportedChart(source)
        assertEquals(120, adapted.chart.bpm)
        assertTrue(adapted.rhythmConfidence > .9f)
        assertTrue(adapted.chart.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs == 250L })
        assertTrue(adapted.chart.accompaniment.any { it.voice == BackingVoice.Kick })
        assertEquals(60, source.bpm)
    }

    @Test fun equalTempoFitsKeepASlowMelodyAtItsOriginalPulse() {
        val source = phrase((0 until 10).map { 200L + it * 1000 }).copy(bpm = 60)
        val adapted = adaptImportedChart(source)
        assertTrue(adapted.rhythmConfidence > .99f)
        assertEquals(60, adapted.chart.bpm)
        assertEquals(source.notes.map { it.atMs }, adapted.chart.notes.map { it.atMs })
    }

    @Test fun leavesAnUncertainRubatoPhraseExpressiveAndDoesNotInventADrumLoop() {
        val source = phrase(listOf(120, 431, 818, 1049, 1470, 1773, 2161, 2422, 2776, 3191, 3509, 3873))
        val adapted = adaptImportedChart(source)
        assertTrue(adapted.rhythmConfidence < .74f)
        assertEquals(source.notes.map { it.atMs }, adapted.chart.notes.map { it.atMs })
        assertEquals(0, adapted.quantizedNotes)
        assertTrue(adapted.chart.accompaniment.none {
            it.voice == BackingVoice.Kick || it.voice == BackingVoice.Snare || it.voice == BackingVoice.Hat
        })
    }

    @Test fun repairsOnlyBriefWeakOctaveIslandsAndPreservesIntentionalLeaps() {
        val source = phrase(listOf(0, 250, 500, 650, 900, 1150, 1400, 1650),
            listOf(60, 62, 76, 64, 65, 77, 65, 64))
        val notes = source.notes.toMutableList()
        notes[2] = notes[2].copy(velocity = .45f, playable = false, durationMs = 110)
        notes[5] = notes[5].copy(velocity = .95f)
        val adapted = adaptImportedChart(source.copy(notes = notes))
        assertEquals(64, adapted.chart.notes[2].midi)
        assertEquals(77, adapted.chart.notes[5].midi)
    }

    @Test fun accompanimentRespectsLongSilencesAndStaysInsideTheRecording() {
        val times = (0 until 8).map { 100L + it * 250 } + (0 until 8).map { 5100L + it * 250 }
        val adapted = adaptImportedChart(phrase(times)).chart
        assertTrue(adapted.accompaniment.none { it.atMs in 2300..4700 })
        assertTrue(adapted.accompaniment.all { it.atMs + it.durationMs <= adapted.durationMs })
        assertTrue(adapted.notes.zipWithNext().all { (a, b) -> a.atMs + a.durationMs <= b.atMs })
        val bass = adapted.accompaniment.filter { it.voice == BackingVoice.Bass }
        assertTrue(bass.all { low -> adapted.notes.any { it.atMs == low.atMs && it.midi % 12 == low.midi % 12 } })
    }
}
