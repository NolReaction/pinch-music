package music.pinch.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note

class MarblePathTest {
    private fun chart(times: List<Long>, end: Long) = Chart("Проверка", 127, end,
        times.mapIndexed { i, time -> Note("panel-$i", time, 61 + i % 12, 60, .8f) })
    private val score = chart(List(12) { 1800L + it * 945L }, 13_500L)
    private val path = MarblePath(score)

    @Test fun sphereTouchesTheTopFaceOnEveryChartAttack() {
        score.notes.forEachIndexed { i, note ->
            val frame = path.frame(note.atMs)
            val key = path.keys[i].center
            assertEquals(key.x, frame.marble.x, .0001f)
            assertEquals(key.z, frame.marble.z, .0001f)
            assertEquals(key.y, frame.marble.y + MarbleRadius, .0001f)
            assertEquals(0f, frame.lift)
            assertEquals(i, frame.anchor)
            if (i + 1 < path.keys.size) assertEquals(i + 1, frame.next)
        }
    }

    @Test fun rollingSegmentsFollowTheActualRailBetweenPanels() {
        for ((i, run) in path.runs.withIndex()) {
            if (run.type !in listOf(RunType.Roll, RunType.Bank)) continue
            for (j in 1..9) {
                val time = run.startMs + run.durationMs * j / 10
                val frame = path.frame(time)
                val t = (time - run.startMs) / run.durationMs.toFloat()
                val rail = run.point(t)
                assertEquals(i, frame.anchor)
                assertEquals(rail.y, frame.marble.y + MarbleRadius, .0001f)
                assertEquals(rail.x, frame.marble.x, .0001f)
                assertEquals(rail.z, frame.marble.z, .0001f)
                assertEquals(0f, frame.lift)
            }
        }
        assertEquals(RunType.entries.toSet(), path.runs.map { it.type }.toSet())
        assertEquals(PanelType.entries.toSet(), path.keys.map { it.panel }.toSet())
    }

    @Test fun descentAcceleratesAfterTheRailLipAndTheSpringRebounds() {
        val drop = path.runs.first { it.type == RunType.Drop }
        assertTrue(drop.point(.9f).y - drop.point(.8f).y > 2f * (drop.point(.2f).y - drop.point(.1f).y))
        assertEquals(0f, drop.lift(drop.railEnd))
        assertTrue(drop.lift(.75f) > 0f)
        val spring = path.runs.first { it.type == RunType.Spring }
        assertTrue(spring.point(.08f).y < spring.point(0f).y)
        assertTrue(spring.lift(.5f) > .3f)
        assertEquals(0f, spring.lift(1f))
    }

    @Test fun cameraKeepsMovingThroughIntroOutroAndLongRests() {
        val score = chart(listOf(2_000, 12_000, 12_473, 12_710), 15_000)
        val path = MarblePath(score)
        val samples = (0L..15_000L step 100).map { path.frame(it) }
        for ((a, b) in samples.zipWithNext()) {
            assertEquals(.192f, b.target.y - a.target.y, .001f)
            assertTrue(abs(a.marble.x - b.marble.x) + abs(a.marble.y - b.marble.y) > .001f)
        }
        assertEquals(null, samples.last().next)
        assertEquals(samples.last(), path.frame(19_000))
        assertEquals(samples.first(), path.frame(-500))
    }
}
