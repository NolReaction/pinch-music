package music.pinch.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import music.pinch.core.Chart

internal const val MarbleRadius = .31f
private const val DescentPerSecond = 1.92f

internal data class StagePoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: StagePoint) = StagePoint(x + other.x, y + other.y, z + other.z)
}

/** The downhill coordinate is measured from the start of the score, independent of note density. */
private fun courseCenter(seconds: Float) = StagePoint(
    1.52f * sin(seconds * .93f - .63f) + .23f * sin(seconds * 1.74f),
    seconds * DescentPerSecond,
    .56f + .46f * cos(seconds * .95f - .3f)
)

internal enum class RunType { Roll, Bank, Drop, Spring }
internal enum class PanelType { Gate, Tongue, Drum }

internal class StageKey(val atMs: Long, val panel: PanelType) {
    val center = courseCenter(atMs / 1_000f)
    private val angle = .23f * sin(atMs / 1_150f) - .12f
    private val c = cos(angle)
    private val s = sin(angle)

    fun point(x: Float, y: Float, z: Float) =
        center + StagePoint(c * x - s * z, y, s * x + c * z)
}

/** The top of the running surface. After the drop lip the marble follows a ballistic Y curve. */
internal class CourseRun(val startMs: Long, val endMs: Long, val type: RunType) {
    val durationMs = endMs - startMs
    val railEnd = when (type) {
        RunType.Drop -> .38f
        RunType.Spring -> 0f
        else -> 1f
    }
    private val descent = DescentPerSecond * durationMs / 1_000f

    fun point(t: Float): StagePoint {
        val u = t.coerceIn(0f, 1f)
        val base = courseCenter((startMs + durationMs * u) / 1_000f)
        return when (type) {
            RunType.Roll -> base
            RunType.Bank -> {
                val wave = sin(PI.toFloat() * u)
                val bend = wave * wave * min(durationMs / 1_200f, .8f)
                StagePoint(base.x + .26f * bend, base.y, base.z + bend)
            }
            RunType.Drop -> {
                val flight = ((u - railEnd) / (1f - railEnd)).coerceAtLeast(0f)
                val lag = min(.65f * descent, 1.28f)
                StagePoint(base.x, base.y - lag * (u - flight * flight), base.z)
            }
            RunType.Spring -> StagePoint(base.x, base.y - lift(u), base.z)
        }
    }

    fun lift(t: Float): Float = when (type) {
        RunType.Spring -> 4f * t * (1f - t) * min(.37f * descent, .78f)
        RunType.Drop -> {
            val flight = ((t - railEnd) / (1f - railEnd)).coerceIn(0f, 1f)
            4f * flight * (1f - flight) * min(descent * .24f, .72f)
        }
        else -> 0f
    }
}

internal data class MarbleFrame(
    val marble: StagePoint,
    val target: StagePoint,
    val anchor: Int,
    val next: Int?,
    val progress: Float,
    val lift: Float,
    val rollAngle: Float,
)

/** The rhythm chart places strike panels on the course. Passive rails span the spaces between them. */
internal class MarblePath(chart: Chart) {
    private val onsets = chart.notes.distinctBy { it.atMs }
    private val durationMs = chart.durationMs
    private val beatMs = 60_000.0 / chart.bpm
    // Two rolling turns, a free fall, then a sprung accent make a short, readable combination.
    private val pattern = listOf(RunType.Roll, RunType.Bank, RunType.Drop, RunType.Roll,
        RunType.Drop, RunType.Spring, RunType.Bank, RunType.Drop)
    val runs = onsets.zipWithNext().mapIndexed { i, (a, b) ->
        val gap = b.atMs - a.atMs
        val type = when {
            gap >= beatMs * 5 -> RunType.Bank
            gap < beatMs * .80 -> RunType.Drop
            else -> pattern[i % pattern.size]
        }
        CourseRun(a.atMs, b.atMs, type)
    }
    val keys = onsets.mapIndexed { i, note ->
        val panel = when {
            runs.getOrNull(i)?.type == RunType.Spring -> PanelType.Drum
            runs.getOrNull(i - 1)?.type in listOf(RunType.Drop, RunType.Spring) -> PanelType.Tongue
            else -> PanelType.Gate
        }
        StageKey(note.atMs, panel)
    }
    val entrance = CourseRun(0, keys.first().atMs, RunType.Drop)
    val exit = CourseRun(keys.last().atMs, durationMs, RunType.Bank)
    val spans = listOf(entrance) + runs + exit

    fun frame(timeMs: Long): MarbleFrame {
        val time = timeMs.coerceIn(0, durationMs)
        val lookup = keys.binarySearchBy(time) { it.atMs }
        val anchor = if (lookup >= 0) lookup else -lookup - 2
        val run = spans[anchor + 1]
        val t = if (run.durationMs == 0L) 0f else (time - run.startMs) / run.durationMs.toFloat()
        val contact = run.point(t)
        val center = contact + StagePoint(0f, -MarbleRadius, 0f)
        // A constant vertical dolly shows the next few panels even when the marble rolls, dives or springs.
        val target = courseCenter(time / 1_000f)
        val next = (anchor + 1).takeIf { it < keys.size }
        return MarbleFrame(center, target, anchor, next, t, run.lift(t), time / 135f)
    }
}
