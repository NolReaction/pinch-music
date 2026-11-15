package music.pinch.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.echoTargetTimes

internal data class PlanarAttack(
    val atMs: Long, val panelIndex: Int, val x: Float, val midi: Int,
    val playable: Boolean, val isEcho: Boolean,
)

internal data class PlanarPanel(
    val left: Boolean, val outerX: Float, val tipX: Float, val topY: Float,
    val firstAttack: Int, val lastAttack: Int, val ledXs: List<Float>, val ledTimesMs: List<Long>,
)

internal data class PlanarFrame(
    val marble: StagePoint, val cameraY: Float, val rollAngle: Float,
    val attackIndex: Int, val rollingPanel: Int?, val litLedIndex: Int,
)

private const val WallX = 2.5f
private const val RepeatGapMs = 170L
private const val LedGapMs = 610L
private const val ReleaseMs = 365L

/** A horizontal powered strip accelerates only near its lip. Its release speed matches the flight. */
private class LedRoll(startX: Float, tipX: Float, endX: Float, val durationMs: Long, flightMs: Long) {
    private val length = abs(tipX - startX)
    private val duration = durationMs / 1000f
    private val releaseSpeed = abs(endX - tipX) * 1000f / flightMs
    private val blend = min(duration * .4f, .7f * length / releaseSpeed)
    private val cruise = (length - .5f * releaseSpeed * blend) / (duration - .5f * blend)

    fun progress(elapsedMs: Long): Float {
        val seconds = elapsedMs.coerceIn(0, durationMs) / 1000f
        val ramp = max(0f, seconds - (duration - blend))
        return ((cruise * seconds + .5f * (releaseSpeed - cruise) * ramp * ramp / blend) / length).coerceIn(0f, 1f)
    }

    fun timeAt(fraction: Float): Long {
        var lo = 0L
        var hi = durationMs
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (progress(mid) < fraction) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

private data class PlanarSpan(
    val roll: LedRoll?, val initialSpin: Float,
)

/**
 * Y points down. Sphere centres stay on z = 0 and the left/right shelves have horizontal tops.
 * A gap too short for a cross-stage flight becomes a repeated bounce on the same physical shelf.
 * A long gap starts on a conductor strip; lamp geometry and sound use the very same crossing time.
 */
internal class PlanarPath(chart: Chart) {
    private val durationMs = chart.durationMs
    val attacks: List<PlanarAttack>
    val panels: List<PlanarPanel>
    val ledCues: List<Note>
    private val spans: List<PlanarSpan>
    private val endSpin: Float
    private val integratedHeight: DoubleArray

    init {
        val notes = chart.notes.groupBy { it.atMs }.values.map { chord ->
            chord.firstOrNull { it.playable } ?: chord.first()
        }
        val echoes = chart.echoTargetTimes()
        val starts = mutableListOf(0)
        for (i in 1 until notes.size) {
            if (notes[i].atMs - notes[i - 1].atMs >= RepeatGapMs) starts += i
        }
        val strikes = ArrayList<PlanarAttack>(notes.size)
        val shelves = ArrayList<PlanarPanel>(starts.size)
        var top = 0f
        for ((panelIndex, first) in starts.withIndex()) {
            val last = (starts.getOrNull(panelIndex + 1) ?: notes.size) - 1
            val inGap = if (first == 0) Long.MAX_VALUE else notes[first].atMs - notes[first - 1].atMs
            val outGap = if (last == notes.lastIndex) Long.MAX_VALUE else notes[last + 1].atMs - notes[last].atMs
            val left = panelIndex % 2 == 0
            val side = if (left) -1f else 1f
            val guided = last < notes.lastIndex && outGap >= LedGapMs
            val distance = (.18f + .0025f * min(inGap, outGap)).coerceIn(if (guided) 1.3f else .70f, 1.65f)
            val x = side * distance
            val tip = if (guided) side * .46f else x - side * .12f
            shelves += PlanarPanel(left, side * WallX, tip, top, first, last, emptyList(), emptyList())
            for (i in first..last) {
                val n = notes[i]
                strikes += PlanarAttack(n.atMs, panelIndex, x, n.midi, n.playable, n.atMs in echoes)
            }
            top += (.65f + .00165f * outGap).coerceAtMost(1.78f)
        }
        val sections = ArrayList<PlanarSpan>(notes.lastIndex)
        val cues = mutableListOf<Note>()
        var spin = 0f
        integratedHeight = DoubleArray(notes.size)
        for (i in 0 until notes.lastIndex) {
            val from = strikes[i]
            val to = strikes[i + 1]
            val gap = to.atMs - from.atMs
            val panel = shelves[from.panelIndex]
            val roll = if (gap >= LedGapMs) LedRoll(from.x, panel.tipX, to.x, gap - ReleaseMs, ReleaseMs) else null
            if (roll != null) {
                val count = (roll.durationMs / 170).coerceIn(2, 5).toInt()
                val lampXs = ArrayList<Float>(count)
                val lampTimes = List(count) { lamp ->
                    val offset = roll.timeAt((lamp + 1f) / (count + 1f))
                    val x = from.x + (panel.tipX - from.x) * roll.progress(offset)
                    lampXs += x
                    val pitch = if (lamp == count - 1) to.midi else from.midi
                    val upper = if (pitch <= 115) pitch + 12 else pitch - 12
                    val time = from.atMs + offset
                    cues += Note("led-$time", time, if (lamp % 2 == 0) pitch else upper, 1, .20f, playable = false)
                    time
                }
                shelves[from.panelIndex] = panel.copy(ledXs = lampXs, ledTimesMs = lampTimes)
            }
            sections += PlanarSpan(roll, spin)
            val drop = (shelves[to.panelIndex].topY - panel.topY).toDouble()
            integratedHeight[i + 1] = integratedHeight[i] + panel.topY.toDouble() * gap +
                .5 * drop * (gap - (roll?.durationMs ?: 0L))
            spin = (spin + (to.x - from.x) / MarbleRadius) % 6.2831855f
        }
        endSpin = spin
        spans = sections
        attacks = strikes
        panels = shelves
        ledCues = cues
    }

    private fun attackIndex(time: Long): Int {
        val found = attacks.binarySearchBy(time) { it.atMs }
        return if (found >= 0) found else -found - 2
    }

    // Integrate a guide height that stays flat on a roller and descends linearly during flight.
    // Its short moving average keeps the camera gliding when adjacent jumps have different lengths.
    private fun heightIntegral(time: Long): Double {
        val i = attackIndex(time)
        if (i < 0) return 0.0
        val p = panels[attacks[i].panelIndex]
        val elapsed = (time - attacks[i].atMs).toDouble()
        val base = integratedHeight[i] + p.topY * elapsed
        if (i == attacks.lastIndex) return base
        val rollMs = spans[i].roll?.durationMs ?: 0L
        val airborne = max(0.0, elapsed - rollMs)
        val next = attacks[i + 1]
        val drop = panels[next.panelIndex].topY - p.topY
        return base + .5 * drop * airborne * airborne / (next.atMs - attacks[i].atMs - rollMs)
    }

    fun frame(timeMs: Long): PlanarFrame {
        val time = timeMs.coerceIn(0L, durationMs)
        val index = attackIndex(time)
        val cameraY = ((heightIntegral(time + 120) - heightIntegral(time - 120)) / 240).toFloat() - MarbleRadius
        val first = attacks.first()
        if (index < 0) {
            val u = (time.toDouble() / first.atMs).toFloat()
            val x = first.x * u
            return PlanarFrame(StagePoint(x, -MarbleRadius - 2.8f * (1f - u * u), 0f),
                cameraY, (x - first.x) / MarbleRadius, -1, null, -1)
        }
        val from = attacks[index]
        val panel = panels[from.panelIndex]
        val y = panel.topY - MarbleRadius
        val elapsed = time - from.atMs
        if (index == attacks.lastIndex) {
            // One small final rebound settles on the last shelf while the backing rings out.
            val u = (elapsed / 275f).coerceAtMost(1f)
            return PlanarFrame(StagePoint(from.x, y - .64f * u * (1f - u), 0f), cameraY, endSpin, index, null, -1)
        }
        val next = attacks[index + 1]
        val span = spans[index]
        val gap = next.atMs - from.atMs
        val roll = span.roll
        if (roll != null && elapsed <= roll.durationMs) {
            val x = from.x + (panel.tipX - from.x) * roll.progress(elapsed)
            val angle = span.initialSpin + (x - from.x) / MarbleRadius
            val lit = panel.ledTimesMs.indexOfLast { it <= time }
            return PlanarFrame(StagePoint(x, y, 0f), cameraY, angle, index, from.panelIndex, lit)
        }
        val rolling = roll?.durationMs ?: 0L
        val u = (elapsed - rolling).toFloat() / (gap - rolling)
        val startX = if (roll == null) from.x else panel.tipX
        val x = startX + (next.x - startX) * u
        val drop = panels[next.panelIndex].topY - panel.topY
        // Y is quadratic in flight time. A strip releases horizontally; a struck shelf kicks up.
        val kick = when {
            roll != null -> 0f
            from.panelIndex == next.panelIndex -> min(.56f, 24f * (gap / 1000f) * (gap / 1000f))
            else -> min(1.76f, .4f + .002f * gap)
        }
        val ballY = y + drop * u * u - kick * u * (1f - u)
        return PlanarFrame(StagePoint(x, ballY, 0f), cameraY,
            span.initialSpin + (x - from.x) / MarbleRadius, index, null, -1)
    }
}
