package music.pinch.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.cascadeChart
import music.pinch.core.cascadePreRollMs
import music.pinch.core.Note
import music.pinch.songLibrary

class MarblePathTest {
    private fun chart(times: List<Long>, end: Long) = Chart("Проверка", 127, end,
        times.mapIndexed { i, time -> Note("key-$i", time, 61 + i % 12, 1, .8f) })

    private fun near(a: StagePoint, b: StagePoint, tolerance: Float = .0003f) {
        assertEquals(a.x, b.x, tolerance)
        assertEquals(a.y, b.y, tolerance)
        assertEquals(a.z, b.z, tolerance)
    }

    @Test fun everyFullArrangementHasOneTangentContactAtEachWrittenLanding() {
        for (song in songLibrary.filter { it.backingAsset != null }) {
            val score = requireNotNull(song.bundled).cascadeChart()
            val path = MarblePath(score)
            assertEquals(score.notes.map { it.atMs }.distinct(), path.keys.map { it.atMs })
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
    }

    @Test fun instrumentAssignmentsFollowMusicalTimeAcrossTempos() {
        for (bpm in listOf(100, 200)) {
            val beat = 60_000L / bpm
            val onsets = listOf(0L, beat, beat*3/2, beat*2, beat*3, beat*7/2)
            val intro = 2_200L
            val score = Chart("Тема", bpm, intro+beat*4, onsets.mapIndexed { i, onset ->
                Note("hit-$i", intro+onset, 60+i, if (i == 3) beat*4/5 else beat/4, .9f)
            })
            val keys = MarblePath(score).keys
            assertEquals(listOf(PanelShape.Drum, PanelShape.Drum, PanelShape.WoodBar, PanelShape.Fork,
                PanelShape.Drum, PanelShape.WoodBar), keys.map { it.shape })
            for (drum in keys.filter { it.shape == PanelShape.Drum }) assertEquals(drum.halfLength, drum.halfWidth)
        }
    }

    @Test fun everyBundledArrangementIncludesFastStrikesBeatPadsAndSustains() {
        for (song in songLibrary) {
            val keys = MarblePath(requireNotNull(song.bundled)).keys
            assertEquals(PanelShape.entries.toSet(), keys.map { it.shape }.toSet(), song.title)
        }
    }

    @Test fun fullArrangementsLeadThroughClockwiseAndCounterclockwisePhrases() {
        for (song in songLibrary.filter { it.backingAsset != null }) {
            val chart = requireNotNull(song.bundled)
            val path = MarblePath(chart.cascadeChart())
            fun angle(point: StagePoint) = atan2(point.x / 2.12f, (point.z - .78f) / .94f)
            // Only short flights are unambiguous from their end points (under half a turn).
            val sweeps = path.spans.filter { it.durationMs in 1..650 }.mapNotNull {
                val delta = angle(it.ball(1f)) - angle(it.ball(0f))
                val rotation = atan2(sin(delta), cos(delta))
                if (abs(rotation) < .001f) null else rotation > 0f
            }
            assertTrue(sweeps.zipWithNext().count { (a, b) -> a != b } >= 2, song.id)
        }
    }

    @Test fun airborneSegmentsUseGravityAndConstantHorizontalVelocity() {
        val path = MarblePath(chart(listOf(0, 118, 354, 826), 1340L))
        for (run in path.spans.drop(1).dropLast(1).filter { it.durationMs > 0 }) {
            assertEquals(0f, run.railEnd)
            val a = run.ball(0f)
            val b = run.ball(.5f)
            val c = run.ball(1f)
            val halfSeconds = run.durationMs / 2000f
            assertEquals(0f, c.x - 2 * b.x + a.x, .00001f)
            assertEquals(0f, c.z - 2 * b.z + a.z, .00001f)
            assertEquals(OrbitGravity, (c.y - 2 * b.y + a.y) / (halfSeconds * halfSeconds), .003f)
            assertTrue(run.ball(.05f).y < a.y)
            assertTrue(run.ball(.95f).y < c.y)
        }
    }

    @Test fun railsOnlySpanLongRhythmicGapsAndTheFinalCatcherStaysPut() {
        // Three equally spaced beats stay airborne; the following two-second rest gets one rail.
        val path = MarblePath(chart(listOf(0, 650, 1300, 1950, 3950), 4500))
        assertTrue((0L..1950L step 25).none(path::onRail))
        assertTrue(path.onRail(2250))
        assertTrue(!path.onRail(3940))
        assertTrue(!path.onRail(3950))
        near(path.frame(4500).marble, path.frame(4050).marble)
        val last = path.keys.last()
        assertEquals(1f, (last.point(1f, 0f, 0f) - last.center).length(), .00001f)
        near(last.ballCenter - last.normal * panelCompression(55, null), path.frame(4005).marble)
    }

    @Test fun rollIsSupportedAndVelocitySurvivesReleaseFromTheRail() {
        val path = MarblePath(chart(listOf(0, 1700), 5000))
        val guides = path.spans.filter { it.railEnd > 0f }
        assertTrue(guides.isNotEmpty())
        for (run in guides) {
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

    @Test fun bundledCoursesHaveNoEarlyPanelIntersectionsDuringFlight() {
        for (song in songLibrary) {
            val score = requireNotNull(song.bundled)
            checkPanelClearance(MarblePath(score))
            checkPanelClearance(MarblePath(score.cascadeChart()))
        }
    }

    private fun checkPanelClearance(path: MarblePath) {
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

    @Test fun plateCompressionAndJudgementAreIndependentOfRenderRate() {
        assertEquals(0f, panelCompression(-1, 500L))
        assertEquals(0f, panelCompression(0, 500L))
        assertTrue(panelCompression(25, 500L) in .01f..panelCompression(55, 500L))
        assertTrue(panelCompression(120, 500L) in .01f..panelCompression(55, 500L))
        assertEquals(0f, panelCompression(250, 500L))
        assertEquals(0f, feedbackStrength(-1))
        assertEquals(1f, feedbackStrength(580))
        assertEquals(.5f, feedbackStrength(690), .00001f)
        assertEquals(0f, feedbackStrength(800))
    }

    @Test fun startupRollsFromRestOnTheVisibleGuideBeforeTheFirstAbsoluteNote() {
        for (first in listOf(0L, 460L, 700L, 2000L, 3500L, 9000L)) {
            val chart = chart(listOf(first, first + 550), first + 1100)
            val orbit = MarblePath(chart)
            val planar = PlanarPath(chart)
            val run = orbit.spans.first()
            val beginning = -chart.cascadePreRollMs()
            assertTrue(first - beginning >= 2400L)
            assertTrue(run.durationMs * run.railEnd >= 2000f)
            assertTrue((run.ball(run.railEnd) - run.ball(0f)).length() > 2f)
            assertTrue((planar.entryRail.last() - planar.entryRail.first()).length() > 2f)
            val lip = run.startMs + (run.durationMs * run.railEnd).toLong()
            assertTrue(orbit.frame(lip - 1).rollAngle > 5f)
            val start = orbit.frame(run.startMs)
            near(start.marble, run.ball(0f))
            near(start.marble, orbit.frame(run.startMs + 10).marble, .0001f)
            for (time in beginning until first step 7L) {
                val pose = orbit.frame(time)
                assertEquals(-1, pose.anchor)
                assertEquals(0, pose.next)
                near(run.ball(((time - run.startMs) / run.durationMs.toFloat()).coerceIn(0f, 1f)), pose.marble)
            }
            near(orbit.keys.first().ballCenter, run.ball(1f))
            near(run.ball(1f), orbit.frame(first).marble)
            near(orbit.frame(first - 1).marble, orbit.frame(first).marble, .008f)
            assertEquals(0, orbit.frame(first).anchor)
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
        assertEquals(0, path.frame(0).anchor)
        assertTrue(path.frame(-540L).marble.y < path.frame(-100L).marble.y)
        assertTrue(path.frame(-100L).marble.y < path.frame(0).marble.y)
        assertEquals(-1, path.frame(-1).anchor)
        near(path.frame(0L).marble, path.frame(-1L).marble, tolerance = .008f)
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
