package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.math.hypot
import music.pinch.PanelFeedback
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.cascadeChart
import music.pinch.core.cascadePreRollMs
import music.pinch.songLibrary

class CascadeGeometryTest {
    @Test fun eachPlannedContactLightsOneKeyTangentToTheMarble() {
        val chart = requireNotNull(songLibrary.single { it.id == "stereo-love" }.bundled)
        val path = PlanarPath(chart)
        val course = path.gpuCourse()
        val frame = FloatArray(CascadeFrameSize)
        for ((index, attack) in path.attacks.withIndex()) {
            val panel = attack.panelIndex + if (path.entryRail.isEmpty()) 0 else 1
            val style = if (attack.playable) 1f else 0f
            if (attack.atMs > 0L) {
                path.writeGpuFrame(attack.atMs - 1, frame)
                assertEquals(panel.toFloat(), frame[9])
            }
            path.writeGpuFrame(attack.atMs, frame)
            val pose = path.frame(attack.atMs)
            assertEquals(pose.cameraX, frame[10])
            assertEquals(pose.cameraTravel, frame[12])
            val key = panel * CascadeKeyStride
            val dx = frame[0] - course.keys[key]
            val dy = frame[1] - course.keys[key + 1]
            val slope = course.keys[key + 4]
            assertTrue(dy < 0)
            assertEquals(MarbleRadius, hypot(dx, dy), .0001f)
            assertEquals(0f, dx + slope * dy, .0001f, "sphere radius is normal to key at ${attack.atMs} ms")
            assertEquals(0f, frame[2])
            assertEquals(panel.toFloat(), frame[5])
            assertEquals(1f, frame[6])
            assertEquals(style, course.keys[key + 3])
            if (index == path.attacks.lastIndex) assertEquals(-1f, frame[9])
        }
        assertEquals(path.panels.indices.toList(), path.attacks.map { it.panelIndex })
    }

    @Test fun silentLeadInHasRailsAndNeverActivatesAMusicalKey() {
        val first = 2200L
        val path = PlanarPath(Chart("intro", 120, 4000, listOf(Note("solo", first, 60, 1000, 1f))))
        val course = path.gpuCourse()
        assertEquals(2 * CascadeKeyStride, course.keys.size)
        assertEquals(-1f, course.keys[3])
        assertTrue(course.rails.isNotEmpty())
        assertTrue((course.rails.indices step 3).all { course.rails[it] == 0f })
        val frame = FloatArray(CascadeFrameSize)
        for (t in listOf(0L, first / 2, first - 1)) {
            path.writeGpuFrame(t, frame)
            assertEquals(-1f, frame[5])
            assertEquals(0f, frame[6])
            assertEquals(1f, frame[9])
        }
        path.writeGpuFrame(first, frame)
        assertEquals(1f, frame[5])
        assertEquals(1f, frame[6])
    }

    @Test fun correctedOrLateScoreColoursItsOwnKeyAndNeverRetriggersItsTravel() {
        val first = 1000L
        val second = 1600L
        val path = PlanarPath(Chart("score", 120, 3000,
            listOf(Note("one", first, 60, 200, 1f), Note("two", second, 64, 200, 1f))))
        val frame = FloatArray(CascadeFrameSize)
        path.writeGpuFrame(first, frame)
        val firstKey = frame[5]
        assertEquals(0f, frame[7])
        path.writeGpuFrame(first + 40L, frame)
        assertTrue(frame[7] > .07f)
        val miss = PanelFeedback(first, first + 200L, false)
        val early = PanelFeedback(second, second - 70L, true)
        path.writeGpuFrame(second - 50, frame, listOf(miss, early))
        assertEquals(firstKey + 1, frame[17])
        assertEquals(1f, frame[18])
        assertEquals(firstKey, frame[19])
        assertEquals(-1f, frame[20])
        assertEquals(0f, frame[7])

        val corrected = PanelFeedback(first, second + 60L, true)
        path.writeGpuFrame(second + 220L, frame, listOf(early, corrected))
        assertEquals(firstKey, frame[17])
        assertEquals(1f, frame[18])
        assertEquals(panelCompression(220L, null), frame[7])
        val frozen = frame.copyOf()
        path.writeGpuFrame(second + 220L, frame, listOf(early, corrected))
        assertTrue(frozen.contentEquals(frame))
        path.writeGpuFrame(second + 220L, frame) // Preview has no results.
        for (slot in 17 until 25 step 2) assertEquals(-1f, frame[slot])
    }

    @Test fun cueFollowsTheJudgesMidpointAndSparksRequireALiveConfirmedContact() {
        val path = PlanarPath(Chart("pair", 127, 2100, listOf(
            Note("one", 1000, 65, 50, 1f), Note("two", 1236, 67, 50, 1f)), authoredLandings = true))
        val frame = FloatArray(CascadeFrameSize)
        path.writeGpuFrame(1000, frame, showWindow = true)
        val key = frame[5]
        assertEquals(key, frame[25])
        assertEquals(0f, frame[26])
        path.writeGpuFrame(804, frame, showWindow = true)
        assertEquals(-1f, frame[25])
        path.writeGpuFrame(805, frame, showWindow = true)
        assertEquals(key, frame[25])
        assertEquals(-1f, frame[26])
        path.writeGpuFrame(1431, frame, showWindow = true)
        assertEquals(key + 1f, frame[25])
        assertEquals(1f, frame[26])
        path.writeGpuFrame(1432, frame, showWindow = true)
        assertEquals(-1f, frame[25])
        path.writeGpuFrame(1118, frame, showWindow = true)
        assertEquals(key, frame[25])
        path.writeGpuFrame(1119, frame, showWindow = true)
        assertEquals(key + 1f, frame[25])
        val hit = PanelFeedback(1000, 1080, true)
        val miss = PanelFeedback(1236, 1440, false)
        path.writeGpuFrame(1100, frame, listOf(hit), showWindow = true)
        assertEquals(-1f, frame[25])
        assertEquals(20f / 360f, frame[27])
        path.writeGpuFrame(1100, frame, listOf(hit))
        assertEquals(-1f, frame[25])
        assertEquals(-1f, frame[27])
        // A scored key stays spent across pause, even if the song resumes inside its window.
        val spent = hit.copy(effectsVisible = false)
        path.writeGpuFrame(1100, frame, listOf(spent), showWindow = true)
        assertEquals(-1f, frame[25])
        assertEquals(-1f, frame[17])
        assertEquals(0f, frame[18])
        assertEquals(-1f, frame[27])
        path.writeGpuFrame(1119, frame, listOf(spent), showWindow = true)
        assertEquals(key + 1f, frame[25])
        path.writeGpuFrame(1440, frame, listOf(hit, miss), showWindow = true)
        assertEquals(-1f, frame[27])
        assertEquals(-1f, frame[28])
    }

    @Test fun eachKeyHasOneContiguousPhysicalContactInBothCourses() {
        fun dot(a: StagePoint, b: StagePoint) = a.x * b.x + a.y * b.y + a.z * b.z
        for (song in songLibrary) {
            val chart = requireNotNull(song.bundled).cascadeChart()
            val path = PlanarPath(chart)
            val orbit = MarblePath(chart)
            for ((i, hit) in path.attacks.withIndex()) {
                val panel = path.panels[i]
                val key = orbit.keys[i]
                val norm = hypot(1f, panel.slope)
                val axis = StagePoint(1f / norm, panel.slope / norm, 0f)
                val normal = StagePoint(axis.y, -axis.x, 0f)
                val struck = StagePoint(hit.x, panel.topY - MarbleRadius, 0f)
                val face = struck - normal * MarbleRadius
                val guide = panel.railSamples.isNotEmpty()
                val width = if (guide) .23f else .35f
                val middle = (if (panel.left) -1 else 1) * (if (guide) width else .22f)
                val xAxis = key.point(1f, 0f, 0f) - key.center
                val zAxis = key.point(0f, 0f, 1f) - key.center
                val gap = path.attacks.getOrNull(i + 1)?.let { it.atMs - hit.atMs }
                val from = path.attacks.getOrNull(i - 2)?.atMs ?: -chart.cascadePreRollMs()
                val to = path.attacks.getOrNull(i + 2)?.atMs ?: chart.durationMs
                var wasPlanar = false
                var wasOrbit = false
                var planarVisits = 0
                var orbitVisits = 0
                // Align the sweep with the exact attack, even for a 115 ms passage.
                for (t in hit.atMs - (hit.atMs - from) / 4 * 4..to step 4L) {
                    val travel = panelCompression(t - hit.atMs, gap)
                    val ball = path.frame(t).marble - face
                    val touchingPlanar = abs(dot(ball, axis) - middle) <= width + .001f &&
                        abs(dot(ball, normal) + (if (gap == null) 0f else travel) - MarbleRadius) < .004f
                    val delta = orbit.frame(t).marble - key.center
                    val touchingOrbit = abs(dot(delta, xAxis)) <= key.halfLength + .001f &&
                        abs(dot(delta, zAxis)) <= key.halfWidth + .001f &&
                        abs(dot(delta, key.normal) + travel - MarbleRadius) < .004f
                    if (touchingPlanar && !wasPlanar) planarVisits++
                    if (touchingOrbit && !wasOrbit) orbitVisits++
                    wasPlanar = touchingPlanar
                    wasOrbit = touchingOrbit
                    if (t == hit.atMs) assertTrue(touchingOrbit && touchingPlanar, "${song.id} missing ${hit.atMs}")
                }
                assertEquals(1, planarVisits, "${song.id} cascade ${hit.atMs}")
                assertEquals(1, orbitVisits, "${song.id} orbit ${hit.atMs}")
            }
        }
    }

    @Test fun finalCatcherCarriesTheSeatedBallThroughItsOneStroke() {
        val path = PlanarPath(Chart("catch", 120, 1500, listOf(Note("single", 400L, 60, 250, 1f))))
        val frame = FloatArray(CascadeFrameSize)
        for (t in listOf(400L, 425L, 440L, 470L, 560L, 610L)) {
            path.writeGpuFrame(t, frame)
            assertEquals(frame[7], frame[8])
        }
        assertEquals(.050f, frame[7])
    }
}
