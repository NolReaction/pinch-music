package music.pinch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.songLibrary

class CascadeGeometryTest {
    @Test fun frameTracksIndividualNotesEvenWhenTheyShareAKey() {
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
            path.writeGpuFrame(attack.atMs, frame, hitMs = attack.atMs)
            assertEquals(course.keys[panel * 4], frame[0])
            assertEquals(course.keys[panel * 4 + 1] - MarbleRadius, frame[1])
            assertEquals(0f, frame[2])
            assertEquals(panel.toFloat(), frame[5])
            assertEquals(1f, frame[6])
            assertEquals(style, frame[11])
            if (index == path.attacks.lastIndex) assertEquals(-1f, frame[9])
        }
        assertTrue(path.attacks.zipWithNext().any { (a, b) -> a.panelIndex == b.panelIndex && a.playable != b.playable })
    }
}
