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
import music.pinch.core.cascadePreRollMs
import music.pinch.core.cascadeChart
import music.pinch.songLibrary

class PlanarPathTest {
    @Test fun eachPrincipalOnsetLandsOnceOnItsOwnKey() {
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled)
            val path = PlanarPath(chart)
            val score = chart.cascadeChart()
            assertEquals(score.notes.map { it.atMs }, path.attacks.map { it.atMs })
            assertEquals(path.attacks.indices.toList(), path.attacks.map { it.panelIndex })
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
            assertEquals(path.frame(-20_000), path.frame(-99_999))
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

    @Test fun densePhrasesLeaveRoomForTheWholeBodyOfEachKey() {
        // A rolling marble can clear the face even if the deep skirts of two keys
        // on the same bank intersect behind it. Test the four separating axes.
        data class Body(val x: Float, val y: Float, val dx: Float, val dy: Float, val halfWidth: Float) {
            fun radiusOn(ax: Float, ay: Float) =
                halfWidth * abs(ax * dx + ay * dy) + .18f * abs(ay * dx - ax * dy)
            fun separated(other: Body, ax: Float, ay: Float) =
                abs(ax * (x - other.x) + ay * (y - other.y)) >= radiusOn(ax, ay) + other.radiusOn(ax, ay)
        }
        val roll = Chart("Sixteenths", 130, 3600, (0..20).map { i ->
            Note("r$i", if (i == 0) 0L else 460L + (i - 1) * 115L, 64 + i % 2, 85L, .9f)
        }, authoredLandings = true)
        assertCourseClear(roll, roll.title)
        val songs = listOf("stereo-love", "on-the-floor").map { id ->
            requireNotNull(songLibrary.single { it.id == id }.bundled)
        }
        for (chart in songs + roll) {
            val path = PlanarPath(chart)
            val bodies = path.panels.mapIndexed { i, panel ->
                val dx = 1f / hypot(1f, panel.slope)
                val dy = dx * panel.slope
                val rail = panel.railSamples.isNotEmpty()
                val offset = (if (panel.left) -1 else 1) * (if (rail) .23f else .22f)
                val face = MarbleRadius + .18f
                Body(path.attacks[i].x + offset * dx - face * dy,
                    panel.topY - MarbleRadius + offset * dy + face * dx, dx, dy, if (rail) .23f else .35f)
            }
            for ((i, a) in bodies.withIndex()) {
                for (j in i - 1 downTo 0) {
                    if (path.panels[i].topY - path.panels[j].topY > 2f) break
                    val b = bodies[j]
                    assertTrue(a.separated(b, a.dx, a.dy) || a.separated(b, -a.dy, a.dx) ||
                        a.separated(b, b.dx, b.dy) || a.separated(b, -b.dy, b.dx),
                        "${chart.title} key bodies overlap at ${path.attacks[i].atMs} and ${path.attacks[j].atMs} ms")
                }
            }
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
        for (ms in -chart.cascadeChart().cascadePreRollMs()..chart.durationMs step 5) {
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

    @Test fun passiveGuidesConserveRollingEnergyAndLeaveAlongTheLipTangent() {
        val contours = mutableSetOf<Int>()
        val sources = songLibrary.map { requireNotNull(it.bundled) } + listOf(-8, 0, 8).map { delta ->
            Chart("Rest", 120, 3700, listOf(Note("a", 0, 60, 1500, .8f),
                Note("b", 2500, 60 + delta, 200, .8f)))
        }
        for (chart in sources) {
            val path = PlanarPath(chart)
            for ((index, panel) in path.panels.withIndex()) {
                val rail = panel.railSamples
                if (rail.isEmpty()) continue
                val start = path.attacks[index].atMs
                val end = path.attacks[index + 1].atMs
                val direction = if (panel.left) 1 else -1
                val entry = path.frame(start)
                val speed0 = (path.frame(start + 4).marble - entry.marble).length() * 250f
                val segments = rail.zipWithNext { a, b -> b - a }
                assertEquals(panel.slope, segments.first().y / segments.first().x, .001f)
                val slopeChange = segments.last().y / abs(segments.last().x) - abs(panel.slope)
                contours += when { slopeChange < -.15 -> -1; slopeChange > .15 -> 1; else -> 0 }
                assertTrue(segments.all { it.x * direction > 0 })
                assertTrue(speed0 >= 2.2f, "slow entry at ${chart.title} / $start")
                var segment = 0
                var distanceBefore = 0f
                for (time in start + 12 until end step 9L) {
                    if (path.frame(time + 3).rollingPanel != index) break
                    val frame = path.frame(time)
                    val speed = (path.frame(time + 3).marble - path.frame(time - 3).marble).length() / .006f
                    val energy = speed0 * speed0 + (10 * CascadeGravity / 7) * (frame.marble.y - entry.marble.y)
                    assertEquals(energy.toFloat(), speed * speed, .36f, "energy at ${chart.title} $time")
                    while (segment < segments.lastIndex && (frame.marble.x - rail[segment + 1].x) * direction > 0) {
                        distanceBefore += segments[segment++].length()
                    }
                    val distance = distanceBefore + (frame.marble - rail[segment]).length()
                    assertEquals(distance, (frame.rollAngle - entry.rollAngle) * MarbleRadius * direction, .0003f)
                }
                for (i in 1 until segments.size) {
                    val a = segments[i - 1]
                    val b = segments[i]
                    val norm = (a + b).length()
                    val curvature = 2 * direction * (a.x * b.y - a.y * b.x) / (a.length() * b.length() * norm)
                    val v2 = speed0 * speed0 + (10 * CascadeGravity / 7) * (rail[i].y - rail.first().y)
                    assertTrue(CascadeGravity * abs(a.x + b.x) / norm - curvature * v2 >= -.01,
                        "the ball loses support on ${chart.title} $start/$i")
                }
                var lo = start
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
        assertEquals(setOf(-1, 0, 1), contours, "scoop, swell and diving exit")
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

    @Test fun afterABreathTheNextPhraseLeadsTheLayoutEvenBeforeItsFirstLeap() {
        val times = listOf(0L, 236L, 472L, 1272L, 1902L, 2138L, 2374L)
        // Same entry, breath and first two repeated notes; the next pitch excursion
        // gives the response its heading before the first leap is heard.
        fun path(pitches: List<Int>) = PlanarPath(Chart("Phrase", 127, 3200L,
            times.mapIndexed { i, t -> Note("n$i", t, pitches[i], 200L, .85f) }, authoredLandings = true))
        val up = listOf(60, 67, 60, 65, 65, 68, 70)
        val down = listOf(60, 67, 60, 65, 65, 62, 60)
        val rising = path(up)
        val falling = path(down)
        val transposed = path(up.map { it + 5 })
        assertEquals(rising.attacks.map { it.x }, transposed.attacks.map { it.x })
        assertEquals(rising.panels, transposed.panels)
        assertTrue(rising.attacks[4].x > rising.attacks[3].x)
        assertTrue(falling.attacks[4].x < falling.attacks[3].x)
    }

    @Test fun quickMelodicAnswersRecoilBetweenFacingBanksWhileAScaleCanContinue() {
        val pitches = listOf(62, 69, 62, 69, 62, 69, 62, 69, 67, 65, 64, 62)
        val times = listOf(1600L, 1836L, 2072L, 2308L, 2544L, 2780L, 3016L, 3252L,
            4052L, 4382L, 4712L, 5042L)
        val notes = times.mapIndexed { i, t -> Note(i.toString(), t, pitches[i], 170, .85f) }
        val chart = Chart("Call and answer", 126, 5600, notes, authoredLandings = true)
        val path = PlanarPath(chart)
        assertEquals(times, path.attacks.map { it.atMs })
        for (i in 2..6) {
            val panel = path.panels[i]
            val returnTo = path.attacks[i - 1].x
            assertTrue(panel.left != path.panels[i - 1].left)
            assertTrue(abs(panel.slope) > 1.2f, "turning face too flat at $i")
            assertTrue(abs(path.attacks[i + 1].x - returnTo) < .20f, "left the facing bank at $i")
            val t = times[i]
            val contact = path.frame(t).marble
            val inX = contact.x - path.frame(t - 3).marble.x
            val outX = path.frame(t + 3).marble.x - contact.x
            assertTrue(inX * outX < 0, "lateral velocity must reverse at contact")
        }
        assertTrue((8..10).zipWithNext().any { (a, b) -> path.panels[a].left == path.panels[b].left })
        assertCourseClear(chart, chart.title)
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

    @Test fun regularBeatAttacksUseIndividualFreeFlightsAndOnlyTheRestGetsRails() {
        val times = listOf(0L, 500L, 1000L, 1500L, 3500L, 4000L, 4500L)
        val notes = times.mapIndexed { i, t -> Note(i.toString(), t, 60 + i, 190, .9f) }
        val path = PlanarPath(Chart("Pulse", 120, 5000, notes))
        assertEquals(times, path.attacks.map { it.atMs })
        assertEquals(listOf(3), path.panels.indices.filter { path.panels[it].railSamples.isNotEmpty() })
        for (t in -1100L until 5000L step 7) {
            val f = path.frame(t)
            assertEquals(f.rollingPanel != null, path.onRail(t))
            if (t in 1501L until 3500L) assertEquals(3, f.attackIndex)
        }
    }

    @Test fun longSilentHoldHasAContinuousGuideWithoutExtraNotes() {
        val path = PlanarPath(Chart("Выдержанная нота", 120, 14_000, listOf(
            Note("a", 0, 64, 10_000, .8f), Note("b", 12_000, 67, 400, .8f)
        )))
        assertEquals(2, path.panels.size)
        assertTrue(path.panels.first().railSamples.isNotEmpty())
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
            val lead = chart.cascadePreRollMs()
            val start = path.frame(-lead)
            val target = path.frame(first).marble
            val rail = path.entryRail
            assertEquals(first + lead >= 640L, rail.isNotEmpty())
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
            for (time in -lead until first step 15) {
                val frame = path.frame(time)
                assertEquals(-1, frame.attackIndex)
                assertTrue(abs(frame.cameraX - frame.marble.x) < 1.55f)
                assertTrue(abs(frame.cameraX - target.x) < 1.55f)
                assertTrue(abs(frame.cameraY - frame.marble.y) < 2.1f)
                assertTrue(abs(frame.cameraY - target.y) < 2.1f)
            }
            assertEquals(0, path.frame(first).attackIndex)
            assertEquals(1, path.attacks.size)
        }
    }

    @Test fun theCountInApproachesAnImmediateDownbeatWithoutEarlyContact() {
        val chart = Chart("First beat", 88, 2400, listOf(Note("a", 0, 60, 200, .9f),
            Note("b", 620, 65, 200, .9f), Note("c", 1310, 63, 120, .8f)))
        val path = PlanarPath(chart)
        val lead = chart.cascadePreRollMs()
        assertEquals(1364L, lead)
        val contact = path.frame(0)
        assertEquals(0, contact.attackIndex)
        assertEquals(path.attacks.first().x, contact.marble.x)
        var previous = path.frame(-lead)
        for (t in -lead + 1 until 0) {
            val frame = path.frame(t)
            assertEquals(-1, frame.attackIndex)
            assertTrue((previous.marble - frame.marble).length() < .012f)
            previous = frame
        }
        assertTrue(contact.marble.y > previous.marble.y)
        assertTrue((previous.marble - contact.marble).length() < .012f)
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
        val single = PlanarPath(Chart("Один удар", 90, 4000, listOf(notes[1])))
        assertNull(single.frame(3800).rollingPanel)
        for (time in 500L..3800L step 10) assertEquals(single.frame(500).marble, single.frame(time).marble)
    }
}
