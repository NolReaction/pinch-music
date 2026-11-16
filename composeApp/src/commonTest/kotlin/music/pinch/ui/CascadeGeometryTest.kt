package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.hypot
import music.pinch.PanelFeedback
import music.pinch.core.Chart
import music.pinch.core.Note
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
        assertEquals(0f, frame[7])
        val frozen = frame.copyOf()
        path.writeGpuFrame(second + 220L, frame, listOf(early, corrected))
        assertTrue(frozen.contentEquals(frame))
        path.writeGpuFrame(second + 220L, frame) // Preview has no results.
        for (slot in 17 until CascadeFrameSize step 2) assertEquals(-1f, frame[slot])
    }

    @Test fun finalCatcherCarriesTheSeatedBallThroughItsOneStroke() {
        val path = PlanarPath(Chart("catch", 120, 1500, listOf(Note("single", 400L, 60, 250, 1f))))
        val frame = FloatArray(CascadeFrameSize)
        for (t in listOf(400L, 425L, 440L, 470L, 560L, 610L)) {
            path.writeGpuFrame(t, frame)
            assertEquals(frame[7], frame[8])
        }
        assertEquals(0f, frame[7])
    }
}
