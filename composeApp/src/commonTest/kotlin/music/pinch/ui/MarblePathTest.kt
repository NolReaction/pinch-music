package music.pinch.ui

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.stereoLoveSong

class MarblePathTest {
    private fun chart(times: List<Long>, end: Long) = Chart("Проверка", 127, end,
        times.mapIndexed { i, time -> Note("key-$i", time, 61 + i % 12, 1, .8f) })

    private fun near(a: StagePoint, b: StagePoint, tolerance: Float = .0003f) {
        assertEquals(a.x, b.x, tolerance)
        assertEquals(a.y, b.y, tolerance)
        assertEquals(a.z, b.z, tolerance)
    }

    @Test fun everyStereoLoveAttackHasOneTangentContactAtItsAudioTime() {
        val score = requireNotNull(stereoLoveSong.bundled)
        val path = MarblePath(score)
        assertEquals(score.notes.map { it.atMs }.distinct(), path.keys.map { it.atMs })
        assertEquals(score.notes.count { it.playable }, path.keys.count { it.playable })
        for ((i, key) in path.keys.withIndex()) {
            val frame = path.frame(key.atMs)
            near(key.center + key.normal * MarbleRadius, frame.marble)
            near(path.spans[i].ball(1f), frame.marble)
            near(path.spans[i + 1].ball(0f), frame.marble)
            assertEquals(1f, key.normal.length(), .00001f)
            assertEquals(i, frame.anchor)
            assertEquals(0f, frame.lift)
        }
    }

    @Test fun airborneSegmentsUseGravityAndConstantHorizontalVelocity() {
        val path = MarblePath(chart(listOf(0, 118, 354, 826), 1340L))
        for (run in path.spans.filter { it.durationMs > 0 }) {
            assertEquals(0f, run.railEnd)
            val a = run.ball(0f)
            val b = run.ball(.5f)
            val c = run.ball(1f)
            val halfSeconds = run.durationMs / 2000f
            assertEquals(0f, c.x - 2 * b.x + a.x, .00001f)
            assertEquals(0f, c.z - 2 * b.z + a.z, .00001f)
            assertEquals(64f, (c.y - 2 * b.y + a.y) / (halfSeconds * halfSeconds), .003f)
            assertTrue(run.ball(.05f).y < a.y)
            assertTrue(run.ball(.95f).y < c.y)
        }
    }

    @Test fun rollIsSupportedAndVelocitySurvivesReleaseFromTheRail() {
        val path = MarblePath(chart(listOf(0, 1500), 5000))
        for (run in path.spans.filter { it.railEnd > 0f }) {
            val lip = run.railEnd
            for (i in 0..10) {
                val t = i * lip / 10
                near(run.point(t) + run.railNormal(t) * MarbleRadius, run.ball(t))
                if (i > 0) {
                    val earlier = (i - 1) * lip / 10
                    val chord = (run.ball(t) - run.ball(earlier)).length()
                    val rollDistance = (run.spin(t) - run.spin(earlier)) * MarbleRadius
                    assertEquals(chord, rollDistance, .03f + .04f * chord)
                }
            }
            val h = .001f
            val velocityIn = (run.ball(lip) - run.ball(lip - h)) * (1000f / (h * run.durationMs))
            val velocityOut = (run.ball(lip + h) - run.ball(lip)) * (1000f / (h * run.durationMs))
            near(velocityIn, velocityOut, .14f)
            assertEquals(0f, run.lift(lip))
            assertTrue(run.lift((lip + 1f) * .5f) > .1f)
        }
    }

    @Test fun stereoLoveHasNoEarlyBarIntersectionsDuringFlight() {
        val path = MarblePath(requireNotNull(stereoLoveSong.bundled))
        // Each oriented box encloses the bar's bevels and has a slightly thicker
        // underside than the rendered part. Include the departure and landing bars.
        val boxes = path.keys.map { key ->
            val xAxis = key.point(1f, 0f, 0f) - key.center
            val zAxis = key.point(0f, 0f, 1f) - key.center
            fun clearance(ball: StagePoint): Float {
                val d = ball - key.center
                fun dot(axis: StagePoint) = d.x * axis.x + d.y * axis.y + d.z * axis.z
                val x = (abs(dot(xAxis)) - key.halfLength).coerceAtLeast(0f)
                val z = (abs(dot(zAxis)) - key.halfWidth).coerceAtLeast(0f)
                val height = dot(key.normal)
                val y = height - height.coerceIn(-.18f, 0f)
                return sqrt(x * x + y * y + z * z)
            }
            key to ::clearance
        }
        for (span in path.spans) {
            for (sample in 1 until 40) {
                val ball = span.ball(sample / 40f)
                for ((key, clearance) in boxes) {
                    if (abs(ball.y - key.center.y) > 1.2f) continue
                    val distance = clearance(ball)
                    assertTrue(distance >= MarbleRadius - .001f,
                        "Sphere clips bar at ${key.atMs} ms during interval ${span.startMs}..${span.endMs}")
                }
            }
        }
    }

    @Test fun chordAtZeroKeepsItsManualAccentAndSeekingDoesNotDependOnFrameOrder() {
        val chord = Chart("Chord", 127, 9000, listOf(
            Note("automatic", 0, 60, 200, .8f, playable = false),
            Note("scored", 0, 72, 240, .9f),
            Note("far", 8300, 66, 100, .6f),
        ))
        val path = MarblePath(chord)
        assertEquals(2, path.keys.size)
        assertEquals(72, path.keys.first().midi)
        assertTrue(path.keys.first().playable)
        assertEquals(0, path.frame(0).anchor)
        assertEquals(path.frame(0), path.frame(-1))
        val halfway = path.frame(4150)
        val end = path.frame(9000)
        assertEquals(null, end.next)
        assertEquals(end, path.frame(9001))
        assertEquals(halfway, path.frame(4150))
        val one = MarblePath(chart(listOf(235), 800L))
        assertEquals(-1, one.frame(0).anchor)
        assertEquals(0, one.frame(0).next)
        assertEquals(0, one.frame(235).anchor)
    }
}
