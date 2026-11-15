package music.pinch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            assertTrue(chart.notes.first().atMs >= 2_000)
            assertTrue(chart.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 440 })
        }
    }

    @Test fun stereoLoveSpansTheWholeSongOnOneMusicalGrid() {
        val chart = requireNotNull(stereoLoveSong.bundled)
        assertEquals("backing/stereo-love.m4a", stereoLoveSong.backingAsset)
        assertEquals(184_000L, chart.durationMs)
        assertTrue(chart.notes.size > 140)
        val lastPanelMs = chart.notes.last().atMs
        assertEquals("Кода", stereoLoveSectionAt(lastPanelMs))
        // The accompaniment plays the resolution after the player's last accent.
        val twoBarsMs = 8L * 60_000 / chart.bpm
        assertTrue(chart.durationMs - lastPanelMs in 1L..twoBarsMs)
        for (note in chart.notes) {
            val tick = note.atMs * chart.bpm / 15_000.0
            assertTrue(abs(tick - tick.roundToInt()) < 0.005, "Off-grid note at ${note.atMs} ms")
        }
        assertEquals("Вступление", stereoLoveSectionAt(0))
        assertEquals("Аккордеон", stereoLoveSectionAt(34_016))
        assertEquals("Куплет", stereoLoveSectionAt(55_000))
        assertEquals("Кода", stereoLoveSectionAt(chart.durationMs))
    }
}
