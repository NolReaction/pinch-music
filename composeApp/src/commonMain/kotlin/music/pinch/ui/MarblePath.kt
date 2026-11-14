package music.pinch.ui

import music.pinch.core.Chart
import kotlin.math.cos
import kotlin.math.sin

internal const val MarbleRadius = 0.34f

internal data class StagePoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: StagePoint) = StagePoint(x + other.x, y + other.y, z + other.z)
    fun blend(other: StagePoint, t: Float) =
        StagePoint(x + (other.x - x) * t, y + (other.y - y) * t, z + (other.z - z) * t)
}

internal class StageKey(val center: StagePoint, val atMs: Long, angle: Float, val pitch: Int) {
    private val c = cos(angle)
    private val s = sin(angle)

    fun point(x: Float, y: Float, z: Float) =
        center + StagePoint(c * x - s * z, y, s * x + c * z)
}

internal data class MarbleFrame(
    val marble: StagePoint,
    val target: StagePoint,
    val anchor: Int,
    val next: Int?,
    val progress: Float,
    val lift: Float,
)

/** Y points down. The upper, horizontal face of key i is at keys[i].center.y. */
internal class MarblePath(chart: Chart) {
    val keys = chart.notes.distinctBy { it.atMs }.mapIndexed { i, note ->
        val theta = i * .89f - .76f
        val pitchOffset = ((note.midi - 66) / 16f).coerceIn(-1f, 1f)
        val point = StagePoint(
            sin(theta) * 1.43f + pitchOffset * .29f,
            1.25f * i,
            .62f + cos(theta) * .63f
        )
        StageKey(point, note.atMs, .28f * sin(theta + .6f) - .12f, note.midi)
    }

    // Damped follow without a second animation clock. Hermite interpolation through
    // the contact points leaves the ball a readable, uninterrupted parabolic arc.
    private fun follow(i: Int, t: Float): StagePoint {
        val before = keys[(i - 1).coerceAtLeast(0)]
        val a = keys[i]
        val b = keys[i + 1]
        val after = keys[(i + 2).coerceAtMost(keys.lastIndex)]
        val dt = (b.atMs - a.atMs).toFloat()
        val va = if (i == 0) 0f else dt / (b.atMs - before.atMs)
        val vb = if (i + 2 >= keys.size) 0f else dt / (after.atMs - a.atMs)
        val t2 = t*t
        val t3 = t2*t
        fun axis(p0: Float, p1: Float, p2: Float, p3: Float) =
            (2*t3-3*t2+1)*p1 + (t3-2*t2+t)*(p2-p0)*va +
                (3*t2-2*t3)*p2 + (t3-t2)*(p3-p1)*vb
        return StagePoint(
            axis(before.center.x,a.center.x,b.center.x,after.center.x),
            axis(before.center.y,a.center.y,b.center.y,after.center.y),
            axis(before.center.z,a.center.z,b.center.z,after.center.z),
        )
    }

    fun frame(timeMs: Long): MarbleFrame {
        val lookup = keys.binarySearchBy(timeMs) { it.atMs }
        // Last attack at or before the clock, including an attack exactly on this frame.
        val i = if (lookup >= 0) lookup else -lookup - 2
        if (i < 0) {
            val first = keys.first()
            val fallMs = first.atMs.coerceIn(1L, 1150L)
            val t = (1f - (first.atMs - timeMs) / fallMs.toFloat()).coerceIn(0f, 1f)
            val clearance = (1f - t * t) * 2.12f
            val center = first.center + StagePoint(-.43f * (1 - t), -MarbleRadius - clearance, 0f)
            return MarbleFrame(center, first.center, 0, 0, t, clearance)
        }
        val start = keys[i]
        val end = keys.getOrNull(i + 1)
        if (end == null) {
            val center = start.center + StagePoint(0f, -MarbleRadius, 0f)
            return MarbleFrame(center, start.center, i, null, 1f, 0f)
        }
        val interval = end.atMs - start.atMs
        val t = ((timeMs - start.atMs) / interval.toFloat()).coerceIn(0f, 1f)
        val contact = start.center.blend(end.center, t)
        val height = (.75f + .00053f * interval).coerceIn(.78f, 1.57f)
        val lift = 4f * height * t * (1 - t)
        val center = contact + StagePoint(0f, -MarbleRadius - lift, 0f)
        return MarbleFrame(center, follow(i,t), i, i + 1, t, lift)
    }
}
