package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongLibraryTest {
    @Test fun everyBundledSongHasAnOfflineSingleFingerChart() {
        assertEquals(8, songLibrary.size)
        assertEquals(songLibrary.size, songLibrary.map { it.id }.distinct().size)
        songLibrary.forEach { song ->
            val chart = requireNotNull(song.bundled)
            assertEquals(song.title, chart.title)
            assertTrue(chart.durationMs <= 60_000)
            assertTrue(chart.notes.first().atMs >= 2_000)
            assertTrue(chart.notes.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= 580 })
        }
    }
}
