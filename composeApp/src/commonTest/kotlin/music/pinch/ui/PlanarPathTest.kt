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
                assertEquals(panel.topY - MarbleRadius, frame.marble.y, .00005f)
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
        val start = rodCenters(path.entryRail)
        for (ms in 0..chart.durationMs step 5) {
            val frame = path.frame(ms)
            val ball = frame.marble
            val active = path.attacks.getOrNull(frame.attackIndex)?.panelIndex ?: 0
            assertEquals(0f, ball.z)
            if (active < 2) for (j in 1 until start.size) {
                assertTrue(distanceToRod(ball, start[j - 1], start[j]) >= MarbleRadius - .0004f,
                    "$title at ${ms}ms crosses starter rod $j")
            }
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
        val inclines = mutableSetOf<Int>()
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
                val speed0 = (path.frame(start + 4).marble - entry.marble).length() * 250f
                val direction = if (panel.left) 1 else -1
                val pitch = (rail.last().y - rail.first().y) / (rail.last().x - rail.first().x)
                assertEquals(panel.slope, pitch, .0002f, "guide tangent matches the entry key")
                inclines += (abs(pitch) * 100).toInt()
                assertTrue(rail.zipWithNext().all { (a, b) -> b.y > a.y })
                assertTrue(speed0 >= 2.2f, "slow entry at ${song.title} / $start")
                for ((lamp, time) in panel.ledTimesMs.withIndex()) {
                    val f = path.frame(time)
                    assertEquals(index, f.rollingPanel)
                    assertEquals(lamp, f.litLedIndex)
                    assertEquals(lamp - 1, path.frame(time - 1).litLedIndex)
                    assertEquals(panel.ledCenters[lamp], f.marble)
                    val speed = (path.frame(time + 2).marble - path.frame(time - 2).marble).length() * 250
                    val energy = speed0 * speed0 + (10 * CascadeGravity / 7) * (f.marble.y - entry.marble.y)
                    assertTrue(speed >= speed0 - .04f && speed * speed <= energy + .30f, "rolling energy at $time")
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
                assertEquals(lip.marble.y - before.marble.y, later.marble.y - air.marble.y, .00016f)
                val dt = (end - hi) / 4
                val a = path.frame(hi).marble
                val b = path.frame(hi + dt).marble
                val c = path.frame(hi + 2 * dt).marble
                assertEquals((CascadeGravity * dt * dt / 1e6).toFloat(), c.y - 2 * b.y + a.y, .00023f)
            }
        }
        assertTrue(inclines.size >= 4, "melodic intervals must produce different grades")
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
            val dx = (b.marble.x - b.cameraX) - (a.marble.x - a.cameraX)
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
        for (t in 0L..11_200L step 100) {
            val current = path.frame(t)
            val next = path.frame(t + 100)
            assertTrue((next.marble - current.marble).length() > .22f, "waiting at $t")
            assertTrue(next.marble.y > current.marble.y)
            assertTrue(abs(current.cameraX - current.marble.x) < .9f)
        }
        assertEquals(path.attacks.last().x, path.frame(12_000).marble.x)
    }

    @Test fun theStarterSupportsTheBallAndFramesTheFirstLandingFromTheStart() {
        for (first in listOf(0L, 100L, 639L, 640L, 700L, 2200L, 2800L, 9000L)) {
            val chart = Chart("Intro", 128, first + 1200, listOf(Note("first", first, 55, 200, .8f)))
            val path = PlanarPath(chart)
            val start = path.frame(0)
            val target = path.frame(first).marble
            val rail = path.entryRail
            assertEquals(first >= 640L, rail.isNotEmpty())
            if (rail.isNotEmpty()) {
                assertEquals(rail.first(), start.marble)
                assertTrue(rail.last().y > rail.first().y)
                val rod = rodCenters(rail)
                // The ball sits on both steel runners when the gate holds it.
                assertEquals(MarbleRadius, distanceToRod(start.marble, rod[0], rod[1]), .0002f)
                if (first >= 3000) assertEquals(start.marble, path.frame(first - 2600).marble)
                val lip = path.frame(first - 320)
                assertEquals(rail.last().x, lip.marble.x, .0001f)
                assertEquals(rail.last().y, lip.marble.y, .0001f)
                val before = (lip.marble - path.frame(first - 322).marble) * 500f
                val after = (path.frame(first - 318).marble - lip.marble) * 500f
                assertTrue((before - after).length() < .03f)
            }
            for (time in 0L until first step 15) {
                val frame = path.frame(time)
                assertEquals(-1, frame.attackIndex)
                assertTrue(abs(frame.cameraX - frame.marble.x) < 1.55f)
                assertTrue(abs(frame.cameraX - target.x) < 1.55f)
                assertTrue(abs(frame.cameraY - frame.marble.y) < 2.1f)
                assertTrue(abs(frame.cameraY - target.y) < 2.1f)
            }
            assertEquals(0, path.frame(first).attackIndex)
            assertEquals(1, path.attacks.size)
            assertTrue(path.ledCues.isEmpty())
        }
    }

    @Test fun theCameraKeepsAllMovingLandingsInFrame() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            val path = PlanarPath(chart)
            for (time in path.attacks.first().atMs..chart.durationMs step 17) {
                val pose = path.frame(time)
                assertTrue(abs(pose.cameraX - pose.marble.x) < 1.20f, "${song.title} X at $time")
                assertTrue(abs(pose.cameraY - pose.marble.y) < 1.70f, "${song.title} Y at $time")
                val speedChange = abs(pose.cameraTravel - path.frame(time + 17).cameraTravel)
                assertTrue(speedChange < 1f, "${song.title} camera velocity at $time")
            }
        }
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
