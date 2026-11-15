package music.pinch.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.sign
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.cascadeChart
import music.pinch.core.echoTargetTimes
import music.pinch.songLibrary

class PlanarPathTest {
    @Test fun eachPrincipalOnsetLandsOnceOnItsOwnKey() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            val path = PlanarPath(chart)
            val score = chart.cascadeChart()
            assertEquals(score.notes.map { it.atMs }, path.attacks.map { it.atMs })
            assertEquals(path.attacks.indices.toList(), path.attacks.map { it.panelIndex })
            assertEquals(score.echoTargetTimes(), path.attacks.filter { it.isEcho }.map { it.atMs }.toSet())
            for ((i, attack) in path.attacks.withIndex()) {
                val panel = path.panels[attack.panelIndex]
                val frame = path.frame(attack.atMs)
                assertEquals(i, frame.attackIndex)
                assertEquals(0f, frame.marble.z)
                assertEquals(attack.x, frame.marble.x)
                assertEquals(panel.topY - MarbleRadius, frame.marble.y)
                assertEquals(score.notes[i].playable, attack.playable)
                path.attacks.getOrNull(i + 1)?.let { next -> assertEquals(panel.left, next.x > attack.x) }
                if (panel.railSamples.isNotEmpty()) assertEquals(frame.marble, panel.railSamples.first())
            }
            assertTrue(path.panels.zipWithNext().all { (a, b) -> a.topY < b.topY })
            assertEquals(path.frame(0), path.frame(-2))
            assertEquals(path.frame(chart.durationMs), path.frame(chart.durationMs + 1))
        }
    }

    // Cross section of the two metal rods in the GLES mesh. Both rods are equally far from z=0.
    private val rodZ = .112f
    private val rodRadius = .022f
    private fun rodCenters(samples: List<StagePoint>) = samples.mapIndexed { i, p ->
        val tangent = (samples[min(i + 1, samples.lastIndex)] - samples[max(0, i - 1)]).unit()
        val normal = StagePoint(-tangent.y, tangent.x, 0f) * sign(tangent.x)
        val distance = MarbleRadius + rodRadius
        p + normal * sqrt(distance * distance - rodZ * rodZ) + StagePoint(0f, 0f, rodZ)
    }

    private fun distanceToRod(ball: StagePoint, a: StagePoint, b: StagePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val t = (((ball.x - a.x) * dx + (ball.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val x = ball.x - a.x - t * dx
        val y = ball.y - a.y - t * dy
        return sqrt(x * x + y * y + rodZ * rodZ) - rodRadius
    }

    @Test fun everyBundledFlightAndRollClearsTheActualKeyAndTwinRodBodies() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            assertCourseClear(chart, song.title)
        }
    }

    @Test fun returnUnderADeepKeyClearsItsUnderside() {
        // Imported phrase: the 1111 ms contact turns back underneath the 506 ms key.
        val times = listOf(0L, 506L, 647L, 1111L, 1369L, 1706L, 2934L, 3592L, 4500L)
        val pitches = listOf(63, 61, 51, 51, 69, 52, 72, 57, 57)
        val notes = times.mapIndexed { i, t -> Note(i.toString(), t, pitches[i], 1, .7f) }
        val chart = Chart("Return", 110, 5000, notes)
        assertCourseClear(chart, "Return")
    }

    private fun assertCourseClear(chart: Chart, title: String) {
        val path = PlanarPath(chart)
        val rods = path.panels.map { rodCenters(it.railSamples) }
        for (ms in 0..chart.durationMs step 5) {
            val frame = path.frame(ms)
            val ball = frame.marble
            val active = path.attacks.getOrNull(frame.attackIndex)?.panelIndex ?: 0
            assertEquals(0f, ball.z)
            for (index in max(0, active - 3)..min(path.panels.lastIndex, active + 3)) {
                val p = path.panels[index]
                val contact = path.attacks[p.firstAttack].x
                val shortKey = p.railSamples.isNotEmpty()
                val halfWidth = if (shortKey) .23f else .35f
                val norm = hypot(1f, p.slope)
                val faceX = contact - MarbleRadius * p.slope / norm
                val faceY = p.topY - MarbleRadius + MarbleRadius / norm
                val dx = ball.x - faceX
                val dy = ball.y - faceY
                val along = (dx + p.slope * dy) / norm
                val depth = (dy - p.slope * dx) / norm
                val center = (if (p.left) -1 else 1) * (if (shortKey) halfWidth else .22f)
                val x = along.coerceIn(center - halfWidth, center + halfWidth)
                val y = depth.coerceIn(0f, .36f)
                assertTrue(hypot(x - along, y - depth) >= MarbleRadius - .0003f,
                    "$title at ${ms}ms crosses key $index")
                val rail = rods[index]
                for (j in 1 until rail.size) {
                    assertTrue(distanceToRod(ball, rail[j - 1], rail[j]) >= MarbleRadius - .0004f,
                        "$title at ${ms}ms crosses rod $index/$j")
                }
            }
        }
    }

    @Test fun lightsMarkOriginalNotesWhileTheBallRollsAndLeavesTheLipTangentially() {
        var troughs = 0
        for (song in songLibrary) {
            val path = PlanarPath(requireNotNull(song.bundled))
            val sourceNotes = requireNotNull(song.bundled).notes.toSet()
            val strikes = path.attacks.map { it.atMs }.toSet()
            assertTrue(path.ledCues.all { it in sourceNotes && it.atMs !in strikes })
            assertEquals(path.ledCues.map { it.atMs }, path.panels.flatMap { it.ledTimesMs })
            assertTrue(path.ledCues.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
            for ((index, panel) in path.panels.withIndex()) {
                val rail = panel.railSamples
                if (rail.isEmpty()) continue
                val start = path.attacks[panel.lastAttack].atMs
                val end = path.attacks[panel.lastAttack + 1].atMs
                val entry = path.frame(start)
                val speed0 = abs(path.frame(start + 1).marble.x - entry.marble.x) * 1000
                assertEquals(rail.first().y, rail[1].y)
                assertEquals(rail.last().y, rail[rail.lastIndex - 1].y)
                assertTrue(rail.last().y > rail.first().y)
                if (rail.maxOf { it.y } > rail.last().y + .04f) troughs++
                val direction = if (panel.left) 1 else -1
                // A guide can only push the sphere. Check that none of its bends would require adhesion.
                for (j in 1 until rail.lastIndex) {
                    val a = rail[j] - rail[j - 1]
                    val b = rail[j + 1] - rail[j]
                    val tangent = a + b
                    val curvature = 2 * direction * (a.x * b.y - a.y * b.x) /
                        (a.length() * b.length() * tangent.length())
                    val speedSquared = speed0 * speed0 + (10 * CascadeGravity / 7) * (rail[j].y - entry.marble.y)
                    val support = CascadeGravity * abs(tangent.x) / tangent.length() - curvature * speedSquared
                    assertTrue(support >= 0, "loss of contact at ${song.title} key $index sample $j")
                }
                for ((lamp, time) in panel.ledTimesMs.withIndex()) {
                    val f = path.frame(time)
                    assertEquals(index, f.rollingPanel)
                    assertEquals(lamp, f.litLedIndex)
                    assertEquals(lamp - 1, path.frame(time - 1).litLedIndex)
                    assertEquals(panel.ledCenters[lamp], f.marble)
                    val speed = (path.frame(time + 2).marble - path.frame(time - 2).marble).length() * 250
                    val energy = speed0 * speed0 + (10 * CascadeGravity / 7) * (f.marble.y - entry.marble.y)
                    assertEquals(energy.toFloat(), speed * speed, .24f, "rolling kinetic energy at $time")
                    var distance = 0f
                    for (j in 1 until rail.size) {
                        if ((f.marble.x - rail[j].x) * direction < 0) {
                            distance += (f.marble - rail[j - 1]).length()
                            break
                        }
                        distance += (rail[j] - rail[j - 1]).length()
                    }
                    assertEquals(distance, (f.rollAngle - entry.rollAngle) * MarbleRadius * direction, .0003f)
                }
                var lo = panel.ledTimesMs.lastOrNull() ?: start
                var hi = end
                while (hi - lo > 1) {
                    val mid = lo + (hi - lo) / 2
                    if (path.frame(mid).rollingPanel == index) lo = mid else hi = mid
                }
                val lip = path.frame(lo)
                val air = path.frame(hi)
                val before = path.frame(lo - 2)
                val later = path.frame(hi + 2)
                assertTrue((lip.marble - rail.last()).length() < .008f)
                assertEquals(lip.marble.x - before.marble.x, later.marble.x - air.marble.x, .0001f)
                assertEquals(lip.rollAngle - before.rollAngle, later.rollAngle - air.rollAngle, .0003f)
                assertEquals(lip.marble.y, air.marble.y, .00012f)
                val dt = (end - hi) / 4
                val a = path.frame(hi).marble
                val b = path.frame(hi + dt).marble
                val c = path.frame(hi + 2 * dt).marble
                assertEquals((CascadeGravity * dt * dt / 1e6).toFloat(), c.y - 2 * b.y + a.y, .00023f)
            }
        }
        assertTrue(troughs > 0, "at least one guide must rise toward its exit")
    }

    @Test fun fastStereoLovePhraseKeepsEachStrikeAndLimitsTravelPerFrame() {
        val chart = requireNotNull(songLibrary.first { it.id == "stereo-love" }.bundled)
        val path = PlanarPath(chart)
        val phrase = 42_000L..44_000L
        val strikes = path.attacks.filter { it.atMs in phrase }
        assertEquals(chart.cascadeChart().notes.filter { it.atMs in phrase }.map { it.atMs },
            strikes.map { it.atMs })
        assertTrue(path.panels.any { it.slope > .25f })
        assertTrue(path.panels.any { it.slope < -.25f })
        assertTrue(path.panels.zipWithNext().any { (a, b) -> a.left == b.left })
        assertTrue(path.panels.zipWithNext().any { (a, b) -> a.left != b.left })
        for ((from, to) in strikes.zipWithNext()) {
            val seconds = (to.atMs - from.atMs) / 1000.0
            assertTrue(abs(to.x - from.x) <= 5.01 * seconds)
        }
        for (ms in phrase) {
            val a = path.frame(ms)
            val b = path.frame(ms + 17)
            val dx = b.marble.x - a.marble.x
            val dy = (b.marble.y - b.cameraY) - (a.marble.y - a.cameraY)
            assertTrue(hypot(dx, dy) < MarbleRadius * .60f, "visible step at ${ms}ms")
        }
    }

    @Test fun gravityDrivesTheFlightsAndInteriorLandingsDissipateEnergy() {
        for (song in songLibrary) {
            val path = PlanarPath(requireNotNull(song.bundled))
            for (i in 1 until path.attacks.lastIndex) {
                val time = path.attacks[i].atMs
                val at = path.frame(time).marble
                // Compare equal, short observation windows on both sides of the same point.
                val arriving = (at - path.frame(time - 3).marble).length()
                val leaving = (path.frame(time + 3).marble - at).length()
                assertTrue(leaving <= arriving + .001f, "${song.title} gains energy at $time")
                if (path.panels[i].railSamples.isNotEmpty()) continue
                val dt = (path.attacks[i + 1].atMs - time) / 4
                val mid = path.frame(time + dt).marble
                val end = path.frame(time + 2 * dt).marble
                assertEquals(0f, end.x - 2 * mid.x + at.x, .0001f)
                assertEquals((CascadeGravity * dt * dt / 1e6).toFloat(), end.y - 2 * mid.y + at.y, .0002f)
            }
        }
    }

    @Test fun longSilentHoldHasAContinuousGuideWithoutExtraNotes() {
        val path = PlanarPath(Chart("Выдержанная нота", 120, 14_000, listOf(
            Note("a", 0, 64, 10_000, .8f), Note("b", 12_000, 67, 400, .8f)
        )))
        assertEquals(2, path.panels.size)
        assertTrue(path.panels.first().railSamples.isNotEmpty())
        assertTrue(path.ledCues.isEmpty())
        assertEquals(0, path.frame(6000).rollingPanel)
        assertTrue(path.frame(6100).marble != path.frame(6000).marble)
        assertEquals(path.attacks.last().x, path.frame(12_000).marble.x)
    }

    @Test fun ornamentsDoNotReboundAndLastKeyCatchesTheMarble() {
        val notes = listOf(
            Note("auto", 500, 60, 20, .8f, false), Note("gold", 500, 72, 20, .8f),
            Note("fast", 590, 62, 20, .8f), Note("next", 1000, 65, 200, .8f),
        )
        val path = PlanarPath(Chart("Тема", 120, 3800, notes))
        assertEquals(2, path.panels.size)
        assertEquals(listOf(0, 1), path.attacks.map { it.panelIndex })
        assertEquals(72, path.attacks.first().midi)
        assertEquals(-1, path.frame(200).attackIndex)
        val a = path.frame(500).marble
        val b = path.frame(545).marble
        assertTrue(abs(b.x - a.x) > .01f)
        assertTrue(path.attacks.none { it.atMs == 590L })
        assertTrue(path.ledCues.isEmpty())
        val single = PlanarPath(Chart("Один удар", 90, 4000, listOf(notes[1])))
        assertNull(single.frame(3800).rollingPanel)
        for (time in 500L..3800L step 10) assertEquals(single.frame(500).marble, single.frame(time).marble)
    }
}
