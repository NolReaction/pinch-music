package music.pinch.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.cascadeChart
import music.pinch.core.echoTargetTimes

internal data class PlanarAttack(
    val atMs: Long, val panelIndex: Int, val x: Float, val midi: Int,
    val playable: Boolean, val isEcho: Boolean,
)

/** The renderer offsets these centreline samples along the normal to make the two supporting rods. */
internal data class PlanarPanel(
    val left: Boolean, val topY: Float, val firstAttack: Int, val lastAttack: Int, val slope: Float = 0f,
    val railSamples: List<StagePoint> = emptyList(), val ledCenters: List<StagePoint> = emptyList(),
    val ledTimesMs: List<Long> = emptyList(),
)

internal data class PlanarFrame(
    val marble: StagePoint, val cameraY: Float, val rollAngle: Float,
    val attackIndex: Int, val rollingPanel: Int?, val litLedIndex: Int,
    val cameraX: Float, val cameraTravel: Float,
)

internal const val CascadeGravity = 17.0
private const val GuideTerminalSpeed = 4.6
private const val RollingGravity = CascadeGravity * 5 / 7 // solid sphere, I = 2mr²/5

/** Straight pitched guide. Rolling resistance limits speed on long descents. */
private class CascadeRail(
    private val start: StagePoint, val direction: Int, pitch: Double, val seconds: Double, entrySpeed: Double,
) {
    private val cosine = 1 / hypot(1.0, pitch)
    private val sine = pitch * cosine
    private val gravityAlong = RollingGravity * sine
    private val drag = gravityAlong / GuideTerminalSpeed
    private val deltaSpeed = GuideTerminalSpeed - entrySpeed

    fun distanceAt(t: Double) = GuideTerminalSpeed * t + deltaSpeed * expm1(-drag * t) / drag
    fun centerAt(s: Double) = StagePoint(
        (start.x + direction * cosine * s).toFloat(), (start.y + sine * s).toFloat(), 0f,
    )

    val length = distanceAt(seconds)
    val releaseSpeed = GuideTerminalSpeed - deltaSpeed * exp(-drag * seconds)
    val releaseVx = direction * cosine * releaseSpeed
    val releaseVy = sine * releaseSpeed
    val lip = centerAt(length)
    // Bound mesh size even for long silences in user-imported audio.
    val samples = ceil(length / .24).toInt().coerceIn(2, 96).let { count ->
        List(count + 1) { i -> centerAt(length * i / count) }
    }
}

/** A silent starter chute releases from rest. Solve its incline from the time
 * available before the first strike. A very long intro can wait on its cradle. */
private class CascadeEntry(firstMs: Long, private val direction: Int, private val target: StagePoint) {
    private val first = firstMs / 1000.0
    private val guided = first >= .64
    private val air = if (guided) .32 else first
    private val roll = if (guided) min(first - air, 2.24) else 0.0
    private val release = first - air - roll
    private val lipSpeed = if (guided) 2.10 else 0.0
    private val acceleration = if (guided) lipSpeed / roll else 0.0
    private val sine = acceleration / RollingGravity
    private val cosine = sqrt(1 - sine * sine)
    val vx = direction * cosine * lipSpeed
    val arrivalVy = sine * lipSpeed + CascadeGravity * air
    private val carried = lipSpeed * (.5 * roll + air)
    private val start = StagePoint((target.x - direction * cosine * carried).toFloat(),
        (target.y - sine * carried - .5 * CascadeGravity * air * air).toFloat(), 0f)

    private fun onRail(s: Double) = StagePoint((start.x + direction * cosine * s).toFloat(),
        (start.y + sine * s).toFloat(), 0f)

    val samples: List<StagePoint> = if (!guided) emptyList() else {
        val length = .5 * lipSpeed * roll
        val count = ceil(length / .22).toInt().coerceAtLeast(2)
        List(count + 1) { i -> onRail(length * i / count) }
    }

    fun position(time: Long): StagePoint {
        val t = (time / 1000.0 - release).coerceIn(0.0, roll + air)
        if (t <= roll) return onRail(.5 * acceleration * t * t)
        val remaining = roll + air - t
        return StagePoint((target.x - vx * remaining).toFloat(),
            (target.y - remaining * (arrivalVy - .5 * CascadeGravity * remaining)).toFloat(), 0f)
    }

    fun angle(time: Long): Float {
        val t = (time / 1000.0 - release).coerceIn(0.0, roll + air)
        val supported = min(t, roll)
        return (direction * (.5 * acceleration * supported * supported + lipSpeed * (t - supported)) /
            MarbleRadius).toFloat()
    }
}

private class PlanarSpan(
    val start: StagePoint, val rail: CascadeRail?, val launchVy: Double, val vx: Double,
    val initialSpin: Float, val seconds: Double,
) {
    private val lip = rail?.lip ?: start
    private val spinSpeed = if (rail == null) vx else rail.direction * rail.releaseSpeed

    fun position(t: Double): StagePoint {
        if (rail != null && t <= rail.seconds) return rail.centerAt(rail.distanceAt(t))
        val air = t - (rail?.seconds ?: 0.0)
        return StagePoint((lip.x + vx * air).toFloat(),
            (lip.y + air * (launchVy + .5 * CascadeGravity * air)).toFloat(), 0f)
    }

    fun angle(t: Double): Float {
        val roll = if (rail == null) 0.0 else rail.direction * rail.distanceAt(min(t, rail.seconds))
        val air = maxOf(0.0, t - (rail?.seconds ?: 0.0))
        return initialSpin + ((roll + spinSpeed * air) / MarbleRadius).toFloat()
    }

    val end = position(seconds)
    val arrivalVy = launchVy + CascadeGravity * (seconds - (rail?.seconds ?: 0.0))

    // Track the descent of the course; leave the rebound visible within the frame.
    fun cameraPoint(t: Double): StagePoint {
        val guideTime = rail?.seconds ?: 0.0
        if (rail != null && t <= guideTime) return rail.centerAt(rail.distanceAt(t))
        val u = ((t - guideTime) / (seconds - guideTime)).toFloat()
        return StagePoint(lip.x + (end.x - lip.x) * u, lip.y + (end.y - lip.y) * u, 0f)
    }
}

/** A returning flight must pass below the previous key, including its tilted skirt. */
private fun returnSpeedLimit(previous: PlanarPanel, previousX: Float, x: Float, topY: Float, vx: Double): Double {
    val travel = x.toDouble() - previousX
    if (travel * vx >= 0.0) return Double.POSITIVE_INFINITY
    // Local axes point toward the previous lip and into the key. Project the
    // actual key bounds, then expand them by the marble radius.
    val pitch = if (travel > 0) previous.slope.toDouble() else -previous.slope.toDouble()
    val norm = hypot(1.0, pitch)
    val guide = previous.railSamples.isNotEmpty()
    val lip = if (guide) 0.0 else .13
    val back = if (guide) -.46 else -.57
    val depth = .36
    val nearEdge = (lip - MarbleRadius * pitch + maxOf(0.0, -depth * pitch)) / norm + MarbleRadius
    val bottom = (MarbleRadius + depth + maxOf(lip * pitch, back * pitch)) / norm + MarbleRadius
    val clearance = topY - previous.topY - bottom
    val enter = (abs(travel) - nearEdge) / abs(vx)
    // At every t after reaching the inflated key, h - v*t + g*t²/2 >= 0.
    // Minimize h/t + g*t/2 over that interval to bound the takeoff speed.
    val critical = maxOf(.0001, enter, sqrt(2 * maxOf(0.0, clearance) / CascadeGravity))
    return maxOf(0.0, clearance / critical + .5 * CascadeGravity * critical)
}

/** A fixed world-space contact plane in a perspective scene. All lengths and times are solved once per chart. */
internal class PlanarPath(chart: Chart, contactChart: Chart = chart.cascadeChart()) {
    private val durationMs = chart.durationMs
    val attacks: List<PlanarAttack>
    val panels: List<PlanarPanel>
    val ledCues: List<Note>
    private val spans: List<PlanarSpan>
    private val endSpin: Float
    private val origin: StagePoint
    private val entry: CascadeEntry
    val entryRail: List<StagePoint> get() = entry.samples

    init {
        val notes = contactChart.notes
        val echoes = contactChart.echoTargetTimes()
        val beat = 60_000.0 / chart.bpm
        val strikes = ArrayList<PlanarAttack>(notes.size)
        val keys = ArrayList<PlanarPanel>(notes.size)
        val sections = ArrayList<PlanarSpan>(notes.lastIndex)
        val cues = mutableListOf<Note>()
        var direction = if ((notes.getOrNull(1)?.midi ?: notes.first().midi) >= notes.first().midi) 1 else -1
        origin = StagePoint(0f, -MarbleRadius, 0f)
        entry = CascadeEntry(notes.first().atMs, direction, origin)
        var center = origin
        var runSteps = 0
        var spin = entry.angle(notes.first().atMs) % 6.2831855f
        var sourceIndex = 0
        for ((i, note) in notes.withIndex()) {
            strikes += PlanarAttack(note.atMs, i, center.x, note.midi, note.playable, note.atMs in echoes)
            val next = notes.getOrNull(i + 1)
            if (next == null) {
                keys += PlanarPanel(direction > 0, center.y + MarbleRadius, i, i)
                break
            }
            val gap = next.atMs - note.atMs
            val seconds = gap / 1000.0
            val interval = next.midi - note.midi
            val previous = sections.lastOrNull()
            val incomingX = previous?.vx ?: entry.vx
            val incomingY = previous?.arrivalVy ?: entry.arrivalVy
            val guide = CascadeGravity * seconds - min(4.0, .55 * incomingY) > 9.3 || (seconds >= .68 &&
                (note.durationMs >= gap * .42 || (gap > beat * 1.4 && abs(interval) <= 2)))
            // A phrase runs as a staircase. Turn after a melodic reversal or at
            // the end of a bank. Never shorten a step to fit into a screen width.
            val melodicTurn = interval * direction <= -3 && runSteps >= 2
            val outerBank = center.x * direction > 4.5 && runSteps >= 2
            if (melodicTurn || outerBank) {
                direction = -direction
                runSteps = 0
            }
            runSteps++
            val targetSpeed = 2.55 + minOf(9, abs(interval)) * .08 + if (next.playable) .20 else 0.0
            val speed = if (previous == null) targetSpeed else
                (.55 * abs(incomingX) + .45 * targetSpeed).coerceIn(2.30, 4.25)
            var panel = PlanarPanel(direction > 0, center.y + MarbleRadius, i, i)
            val slope: Double
            val rail: CascadeRail?
            val vx: Double
            val vy: Double
            if (guide) {
                val pitch = .24 + minOf(9, abs(interval)) * .017 + if (note.playable) .035 else 0.0
                val air = (.28 + seconds * .065).coerceIn(.32, .43)
                rail = CascadeRail(center, direction, pitch, seconds - air, speed)
                slope = direction * pitch
                vx = rail.releaseVx
                vy = rail.releaseVy
                val lights = ArrayList<Note>()
                val releaseMs = note.atMs + (rail.seconds * 1000).roundToLong()
                while (sourceIndex < chart.notes.size && chart.notes[sourceIndex].atMs < releaseMs - 70) {
                    val ornament = chart.notes[sourceIndex++]
                    if (ornament.atMs - note.atMs < 100) continue
                    val last = lights.lastOrNull()
                    if (last == null || ornament.atMs - last.atMs >= 150) lights += ornament
                    else if (ornament.velocity > last.velocity) lights[lights.lastIndex] = ornament
                }
                cues += lights
                panel = panel.copy(railSamples = rail.samples, ledTimesMs = lights.map { it.atMs },
                    ledCenters = lights.map { rail.centerAt(rail.distanceAt((it.atMs - note.atMs) / 1000.0)) })
            } else {
                rail = null
                vx = direction * speed
                val overhead = if (i == 0) Double.POSITIVE_INFINITY else
                    returnSpeedLimit(keys.last(), strikes[i - 1].x, center.x, panel.topY, vx)
                val freeFall = .5 * CascadeGravity * seconds * seconds
                val rebound = minOf(overhead, 4.0, .55 * incomingY, maxOf(0.0, (freeFall - 1.0) / seconds))
                vy = -rebound
                slope = ((vx - incomingX) / maxOf(.01, incomingY - vy)).coerceIn(-.56, .56)
            }
            val section = PlanarSpan(center, rail, vy, vx, spin, seconds)
            keys += panel.copy(slope = slope.toFloat())
            sections += section
            spin = section.angle(seconds) % 6.2831855f
            center = section.end
        }
        spans = sections
        attacks = strikes
        panels = keys
        ledCues = cues
        endSpin = spin
    }

    private fun attackIndex(time: Long): Int {
        val found = attacks.binarySearchBy(time) { it.atMs }
        return if (found >= 0) found else -found - 2
    }

    private fun cameraPoint(time: Long): StagePoint {
        val i = attackIndex(time)
        if (i < 0) {
            // Keep the release and the first real key in the same opening shot.
            val ball = entry.position(time)
            return StagePoint((ball.x + origin.x) * .5f, (ball.y + origin.y) * .5f, 0f)
        }
        val span = spans.getOrNull(i) ?: return spans.lastOrNull()?.end ?: origin
        return span.cameraPoint((time - attacks[i].atMs) / 1000.0)
    }

    fun frame(timeMs: Long): PlanarFrame {
        val time = timeMs.coerceIn(0L, durationMs)
        val index = attackIndex(time)
        // Uniform cubic B-spline: position, velocity and acceleration stay
        // continuous even when a staircase reverses at a key.
        val step = 280L
        val ahead = time + 85
        val knot = ahead - ahead % step
        val u = (ahead - knot).toFloat() / step
        val v = 1f - u
        val a = cameraPoint(knot - step)
        val b = cameraPoint(knot)
        val c = cameraPoint(knot + step)
        val d = cameraPoint(knot + 2 * step)
        val wa = v * v * v
        val wb = 4 - 6 * u * u + 3 * u * u * u
        val wc = 4 - 6 * v * v + 3 * v * v * v
        val wd = u * u * u
        fun coordinate(p: Float, q: Float, r: Float, s: Float) = (p * wa + q * wb + r * wc + s * wd) / 6
        fun speed(p: Float, q: Float, r: Float, s: Float) =
            ((q - p) * v * v + (r - q) * (1 + 2 * u * v) + (s - r) * u * u) * (500f / step)
        val cameraX = coordinate(a.x, b.x, c.x, d.x)
        val cameraY = coordinate(a.y, b.y, c.y, d.y)
        val travel = hypot(speed(a.x, b.x, c.x, d.x), speed(a.y, b.y, c.y, d.y))
        if (index < 0) {
            return PlanarFrame(entry.position(time), cameraY, entry.angle(time), -1, null, -1, cameraX, travel)
        }
        val span = spans.getOrNull(index)
        if (span == null) {
            val end = spans.lastOrNull()?.end ?: origin
            return PlanarFrame(end, cameraY, endSpin, index, null, -1, cameraX, travel)
        }
        val t = (time - attacks[index].atMs) / 1000.0
        val rolling = if (span.rail != null && t <= span.rail.seconds) index else null
        val lit = if (rolling == null) -1 else panels[index].ledTimesMs.indexOfLast { it <= time }
        return PlanarFrame(span.position(t), cameraY, span.angle(t), index, rolling, lit, cameraX, travel)
    }
}
