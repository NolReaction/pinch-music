package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note

class MarblePathTest {
    private val notes = listOf(2100L, 2800L, 3950L, 4650L).mapIndexed { i, ms ->
        Note("key-$i", ms, 67 + i * 2, 300, .8f)
    }
    private val path = MarblePath(Chart("Проверка", 86, 6000, notes))

    @Test fun sphereTouchesTheTopFaceOnEveryChartAttack() {
        notes.forEachIndexed { i, note ->
            val f = path.frame(note.atMs)
            val key = path.keys[i].center
            assertEquals(key.x, f.marble.x, .0001f)
            assertEquals(key.z, f.marble.z, .0001f)
            assertEquals(key.y, f.marble.y + MarbleRadius, .0001f)
            assertEquals(0f, f.lift)
            assertEquals(i, f.anchor)
        }
    }

    @Test fun sphereTakesOffAndLandsAndRestsAtEnd() {
        val first = path.keys.first()
        val second = path.keys[1]
        assertTrue(path.frame(0).lift > 1f)
        val apex = path.frame((first.atMs + second.atMs) / 2)
        assertTrue(apex.lift > .8f)
        assertEquals(1, apex.next)
        assertTrue(path.frame(second.atMs - 1).lift < .015f)
        assertEquals(path.frame(5_000).marble, path.frame(6_000).marble)
    }
}
