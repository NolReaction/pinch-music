package music.pinch.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.echoTargetTimes
import music.pinch.songLibrary

class PlanarPathTest {
    @Test fun everyBundledOnsetTouchesItsHorizontalShelfInOnePlane() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            val path = PlanarPath(chart)
            val chords = chart.notes.groupBy { it.atMs }
            assertEquals(chords.keys.toList(), path.attacks.map { it.atMs })
            assertEquals(chart.echoTargetTimes(), path.attacks.filter { it.isEcho }.map { it.atMs }.toSet())
            for ((i, attack) in path.attacks.withIndex()) {
                val panel = path.panels[attack.panelIndex]
                val frame = path.frame(attack.atMs)
                assertEquals(i, frame.attackIndex)
                assertEquals(0f, frame.marble.z)
                assertEquals(attack.x, frame.marble.x)
                assertEquals(panel.topY - MarbleRadius, frame.marble.y)
                assertEquals(chords.getValue(attack.atMs).any { it.playable }, attack.playable)
                assertTrue(attack.x in min(panel.outerX, panel.tipX)..max(panel.outerX, panel.tipX))
                assertTrue(panel.left == (panel.outerX < 0f) && panel.left == (panel.tipX < 0f))
            }
            assertTrue(path.panels.zipWithNext().all { (a, b) -> a.left != b.left && a.topY < b.topY })
            assertEquals(path.frame(0), path.frame(-2))
            assertEquals(path.frame(chart.durationMs), path.frame(chart.durationMs + 1))
        }
    }

    @Test fun theBallClearsSolidShelfBodiesDuringEveryFlight() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            val path = PlanarPath(chart)
            val times = listOf(0L) + path.attacks.map { it.atMs } + chart.durationMs
            for ((start, end) in times.zipWithNext()) {
                for (slice in 0..40) {
                    val ms = start + (end - start) * slice / 40
                    val f = path.frame(ms)
                    assertEquals(0f, f.marble.z)
                    val active = path.attacks.getOrNull(f.attackIndex)?.panelIndex ?: 0
                    for (index in max(0, active - 2)..min(path.panels.lastIndex, active + 2)) {
                        val panel = path.panels[index]
                        val x = f.marble.x.coerceIn(min(panel.tipX, panel.outerX), max(panel.tipX, panel.outerX))
                        val y = f.marble.y.coerceIn(panel.topY, panel.topY + .18f)
                        val clearance = hypot(x - f.marble.x, y - f.marble.y)
                        assertTrue(clearance >= MarbleRadius - .0002f, "${song.title} at ${ms}ms clips shelf $index")
                    }
                }
            }
        }
    }

    @Test fun everyAudibleLampIsCrossedAtItsSoundTimeWithContinuousRelease() {
        for (song in songLibrary) {
            val path = PlanarPath(requireNotNull(song.bundled))
            assertEquals(path.ledCues.map { it.atMs }, path.panels.flatMap { it.ledTimesMs })
            assertTrue(path.ledCues.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
            for ((index, panel) in path.panels.withIndex()) {
                for ((lamp, time) in panel.ledTimesMs.withIndex()) {
                    val f = path.frame(time)
                    assertEquals(index, f.rollingPanel)
                    assertEquals(lamp, f.litLedIndex)
                    assertEquals(lamp - 1, path.frame(time - 1).litLedIndex)
                    assertEquals(panel.ledXs[lamp], f.marble.x, .00001f)
                    assertEquals(panel.topY - MarbleRadius, f.marble.y)
                }
                if (panel.ledTimesMs.isEmpty()) continue
                var lo = panel.ledTimesMs.last()
                var hi = path.attacks[panel.lastAttack + 1].atMs
                while (hi - lo > 1) {
                    val mid = lo + (hi - lo) / 2
                    if (path.frame(mid).rollingPanel == index) lo = mid else hi = mid
                }
                val previous = path.frame(lo - 1).marble
                val lip = path.frame(lo).marble
                val air = path.frame(hi).marble
                assertEquals(panel.tipX, lip.x, .00001f)
                assertEquals(lip.x - previous.x, air.x - lip.x, .00012f)
                assertTrue(abs(air.y - lip.y) < .0001f)
            }
        }
    }

    @Test fun rapidRepeatsUseOnePadAndChordsShareThePlayableStrike() {
        val notes = listOf(
            Note("auto", 500, 60, 20, .8f, false), Note("gold", 500, 72, 20, .8f),
            Note("fast", 590, 62, 20, .8f), Note("next", 1000, 65, 200, .8f),
        )
        val path = PlanarPath(Chart("Тема", 120, 3800, notes))
        assertEquals(2, path.panels.size)
        assertEquals(listOf(0, 0, 1), path.attacks.map { it.panelIndex })
        assertEquals(72, path.attacks.first().midi)
        assertEquals(-1, path.frame(200).attackIndex)
        val a = path.frame(500).marble
        val b = path.frame(545).marble
        assertEquals(a.x, b.x)
        assertTrue(b.y < a.y)
        assertTrue(path.ledCues.isEmpty())
        val single = PlanarPath(Chart("Один удар", 90, 4000, listOf(notes[1])))
        assertNull(single.frame(3800).rollingPanel)
        assertEquals(single.frame(500).marble, single.frame(3800).marble)
    }
}
