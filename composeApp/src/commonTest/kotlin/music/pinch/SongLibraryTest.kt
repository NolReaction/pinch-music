package music.pinch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.cascadeChart

class SongLibraryTest {
    @Test fun everySongKeepsItsOwnTempoAndSingleFingerTargets() {
        val bpms = mapOf("on-the-floor" to 130, "stereo-love" to 127, "gypsy-woman" to 120,
            "levels" to 126, "stay" to 170, "turn-down-for-what" to 100, "yeah" to 105,
            "the-final-countdown" to 118)
        assertEquals(bpms.keys, songLibrary.map { it.id }.toSet())
        assertEquals(bpms.size, songLibrary.size)
        songLibrary.forEach { song ->
            val chart = requireNotNull(song.bundled)
            assertEquals(song.title, chart.title)
            assertEquals(bpms[song.id], chart.bpm)
            val targets = chart.notes.filter { it.playable }
            assertTrue(targets.first().atMs >= 2_000)
            assertTrue(targets.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 440 })
        }
    }

    @Test fun stereoLoveSpansTheWholeSongOnOneMusicalGrid() {
        val chart = requireNotNull(stereoLoveSong.bundled)
        assertEquals("backing/stereo-love.m4a", stereoLoveSong.backingAsset)
        assertEquals(184_000L, chart.durationMs)
        assertEquals(508, chart.notes.size)
        assertEquals(159, chart.notes.count { it.playable })
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> b.atMs > a.atMs })
        val lastManualMs = chart.notes.last { it.playable }.atMs
        val resolutionMs = chart.notes.last().atMs
        assertTrue(resolutionMs > lastManualMs)
        assertEquals("Кода", stereoLoveSectionAt(resolutionMs))
        val twoBarsMs = 8L * 60_000 / chart.bpm
        assertTrue(chart.durationMs - resolutionMs in 1L..twoBarsMs)
        for (note in chart.notes) {
            val tick = note.atMs * chart.bpm / 15_000.0
            assertTrue(abs(tick - tick.roundToInt()) < 0.005, "Off-grid note at ${note.atMs} ms")
        }
        assertEquals("Вступление", stereoLoveSectionAt(0))
        assertEquals("Аккордеон", stereoLoveSectionAt(34_016))
        assertEquals("Куплет", stereoLoveSectionAt(55_000))
        assertEquals("Кода", stereoLoveSectionAt(chart.durationMs))
    }

    @Test fun savedChartsRememberAutoContactsAndLoadEarlierSingleFingerCharts() {
        val stereo = requireNotNull(stereoLoveSong.bundled)
        val saved = decodeChart(encodeChart(stereo), stereo.title)
        assertEquals(stereo.notes.map { it.atMs to it.playable }, saved.notes.map { it.atMs to it.playable })
        val oldJson = """{"version":1,"bpm":120,"durationMs":4000,"notes":[[2000,64,150,0.8]]}"""
        val old = decodeChart(oldJson, "Импорт")
        assertTrue(old.notes.single().playable)
        assertEquals(oldJson, encodeChart(old))
    }

    @Test fun cascadeReducesOrnamentsAndPreservesAuthoredStereoLoveTargetsAtTheirActualOnsets() {
        val source = requireNotNull(stereoLoveSong.bundled)
        val route = source.cascadeChart()
        assertEquals(source.durationMs, route.durationMs)
        assertEquals(source.bpm, route.bpm)
        assertEquals(source.notes.filter { it.playable }, route.notes.filter { it.playable })
        assertTrue(route.notes.size <= source.notes.size / 2)
        assertTrue(route.notes.any { !it.playable })
        assertTrue(route.notes.all { it in source.notes })
        assertTrue(route.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 400 })
        assertEquals(source.notes.last(), route.notes.last())
        assertTrue(route.cascadeChart() === route)
    }
}
