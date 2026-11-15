package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.hypot
import music.pinch.songLibrary

class CascadeGeometryTest {
    @Test fun eachPlannedContactLightsOneKeyTangentToTheMarble() {
        val chart = requireNotNull(songLibrary.single { it.id == "stereo-love" }.bundled)
        val path = PlanarPath(chart)
        val course = path.gpuCourse()
        val frame = FloatArray(12)
        for ((index, attack) in path.attacks.withIndex()) {
            val panel = attack.panelIndex
            val style = if (attack.playable) 1f else if (attack.isEcho) 2f else 0f
            if (attack.atMs > 0L) {
                path.writeGpuFrame(attack.atMs - 1, frame)
                assertEquals(panel.toFloat(), frame[9])
                assertEquals(style, frame[10])
            }
            path.writeGpuFrame(attack.atMs, frame)
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

    @Test fun railTapOnlyBoostsTheReachedLamp() {
        val chart = requireNotNull(songLibrary.single { it.id == "stereo-love" }.bundled)
        val path = PlanarPath(chart)
        val lamp = path.panels.first { it.ledTimesMs.isNotEmpty() }.ledTimesMs.first()
        val frame = FloatArray(12)
        path.writeGpuFrame(lamp, frame)
        val impact = frame[6]
        assertEquals(0f, frame[11])
        path.writeGpuFrame(lamp, frame, ledTapMs = lamp)
        assertEquals(impact, frame[6])
        assertEquals(1f, frame[11])
        path.writeGpuFrame(lamp + 185, frame, ledTapMs = lamp)
        assertEquals(0f, frame[11])
    }
}
