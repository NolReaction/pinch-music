package music.pinch.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import music.pinch.core.Chart
import music.pinch.core.cascadeChart
import music.pinch.core.cascadePreRollMs
import music.pinch.core.hasGuideAfter

internal data class PlanarAttack(
    val atMs: Long, val panelIndex: Int, val x: Float, val midi: Int,
    val playable: Boolean,
)

/** The renderer offsets these centreline samples along the normal to make the two supporting rods. */
internal data class PlanarPanel(
    val left: Boolean, val topY: Float, val firstAttack: Int, val lastAttack: Int, val slope: Float = 0f,
    val railSamples: List<StagePoint> = emptyList(),
)

internal data class PlanarFrame(
    val marble: StagePoint, val cameraY: Float, val rollAngle: Float,
    val attackIndex: Int, val rollingPanel: Int?,
    val cameraX: Float, val cameraTravel: Float,
)

internal const val CascadeGravity = 13.0
private const val MaxKeySlope = 3.2 // 73 degrees, steep enough to reflect a rapid side-to-side phrase.
private const val RollingGravity = CascadeGravity * 5 / 7 // solid sphere, I = 2mr²/5

private enum class GuideBend { Dive, Scoop, Wave }

/** Integrate gravity along the two passive runners. Time determines the length
 * of the guide. Each chord has constant tangent and exact rolling acceleration. */
private class CascadeRail(
    start: StagePoint, val direction: Int, bend: GuideBend, val seconds: Double, entrySpeed: Double,
    maxEntrySine: Double,
) {
    // Resolve the entrance/exit bends even if an imported rest lasts minutes.
    // A long middle reach has a shallow grade and needs only a few straight chords.
    private val shoulder = min(.86, seconds * .5)
    private val edgeSteps = ceil(shoulder * 50).toInt().coerceAtLeast(4)
    private val middle = maxOf(0.0, seconds - 2 * shoulder)
    private val middleSteps = ceil(middle * 6).toInt().coerceIn(0, 32)
    private val count = 2 * edgeSteps + middleSteps
    private val times = DoubleArray(count + 1) { i ->
        when {
            i <= edgeSteps -> shoulder * i / edgeSteps
            i <= edgeSteps + middleSteps -> shoulder + middle * (i - edgeSteps) / middleSteps
            else -> seconds - shoulder * (count - i) / edgeSteps
        }
    }
    private val speed = DoubleArray(count + 1)
    private val arc = DoubleArray(count + 1)
    private val tangentX = DoubleArray(count)
    private val tangentY = DoubleArray(count)
    val samples: List<StagePoint>

    init {
        var x = start.x.toDouble()
        var y = start.y.toDouble()
        val points = ArrayList<StagePoint>(count + 1)
        points += start
        speed[0] = entrySpeed
        // Smooth tangent transitions. A spoon-shaped rise trades momentum for
        // height at the lip; a dive or a wave stores that energy in the flight.
        val grade = min(.16, .15 / seconds)
        for (i in 0 until count) {
            val dt = times[i + 1] - times[i]
            val t = times[i] + .5 * dt
            fun ease(z: Double): Double {
                val u = z.coerceIn(0.0, 1.0)
                return u * u * (3 - 2 * u)
            }
            val entrance = ease(1 - t / shoulder)
            val exit = ease(1 - (seconds - t) / shoulder)
            val nominal = when (bend) {
                GuideBend.Dive -> grade + .06 * entrance + .36 * exit
                GuideBend.Scoop -> grade + .38 * entrance - .16 * exit
                GuideBend.Wave -> grade + shoulder * (1.42 * entrance * (1 - entrance) +
                    .85 * exit * (1 - exit))
            }
            val sine = minOf(nominal, maxEntrySine + (1 - entrance) * .60)
            val tx = direction * sqrt(1 - sine * sine)
            val v = speed[i] + RollingGravity * sine * dt
            val ds = .5 * (speed[i] + v) * dt
            speed[i + 1] = v
            arc[i + 1] = arc[i] + ds
            tangentX[i] = tx
            tangentY[i] = sine
            x += tx * ds
            y += sine * ds
            points += StagePoint(x.toFloat(), y.toFloat(), 0f)
        }
        samples = points
    }

    val entrySlope = (tangentY.first() / tangentX.first()).toFloat()
    val releaseSpeed = speed.last()
    val releaseVx = tangentX.last() * releaseSpeed
    val releaseVy = tangentY.last() * releaseSpeed
    val lip = samples.last()

    private fun segment(t: Double): Int {
        var lo = 0
        var hi = count
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    private fun along(i: Int, t: Double): Double {
        val dt = t - times[i]
        return dt * (speed[i] + .5 * RollingGravity * tangentY[i] * dt)
    }

    fun distanceAt(t: Double): Double {
        val i = segment(t)
        return arc[i] + along(i, t)
    }

    fun position(t: Double): StagePoint {
        val i = segment(t)
        val ds = along(i, t)
        return StagePoint((samples[i].x + ds * tangentX[i]).toFloat(),
            (samples[i].y + ds * tangentY[i]).toFloat(), 0f)
    }
}

/** A silent starter chute releases from rest. Solve its incline from the time
 * available before the first strike. A very long intro can wait on its cradle. */
private class CascadeEntry(
    firstMs: Long, private val startMs: Long, private val direction: Int, private val target: StagePoint,
) {
    private val first = (firstMs - startMs) / 1000.0
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
        val t = ((time - startMs) / 1000.0 - release).coerceIn(0.0, roll + air)
        if (t <= roll) return onRail(.5 * acceleration * t * t)
        val remaining = roll + air - t
        return StagePoint((target.x - vx * remaining).toFloat(),
            (target.y - remaining * (arrivalVy - .5 * CascadeGravity * remaining)).toFloat(), 0f)
    }

    fun angle(time: Long): Float {
        val t = ((time - startMs) / 1000.0 - release).coerceIn(0.0, roll + air)
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
        if (rail != null && t <= rail.seconds) return rail.position(t)
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
        if (rail != null && t <= guideTime) return rail.position(t)
        val u = ((t - guideTime) / (seconds - guideTime)).toFloat()
        // Follow wide arcs through the middle of a long jump while keeping
        // ordinary rebounds readable against the next key.
        val airTime = seconds - guideTime
        val swing = maxOf(0.0, .5 * CascadeGravity * airTime * airTime - 3.2)
        val y = lip.y + (end.y - lip.y) * u - (swing * u * (1 - u)).toFloat()
        return StagePoint(lip.x + (end.x - lip.x) * u, y, 0f)
    }
}

/** Lowest safe vertical takeoff velocity when a new flight crosses an older bank.
 * Y is positive down. Expand its tilted body by one ball radius, then find
 * the apex of the ballistic inequality only during the horizontal overlap. */
private fun overheadVelocity(
    panel: PlanarPanel, contactX: Float, start: StagePoint, vx: Double, seconds: Double,
): Double {
    val pitch = panel.slope.toDouble()
    val norm = hypot(1.0, pitch)
    val side = if (panel.left) -1 else 1
    val rail = panel.railSamples.isNotEmpty()
    val back = side * (if (rail) .46 else .57)
    val front = -side * (if (rail) 0.0 else .13)
    val left = (minOf(back, front) - MarbleRadius * pitch + minOf(0.0, -.36 * pitch)) / norm - MarbleRadius
    val right = (maxOf(back, front) - MarbleRadius * pitch + maxOf(0.0, -.36 * pitch)) / norm + MarbleRadius
    val a = (contactX - start.x + left) / vx
    val b = (contactX - start.x + right) / vx
    val begin = maxOf(.0001, minOf(a, b))
    val end = minOf(seconds, maxOf(a, b))
    if (begin > end) return Double.NEGATIVE_INFINITY
    val bottom = panel.topY - MarbleRadius +
        (MarbleRadius + .36 + maxOf(back * pitch, front * pitch)) / norm + MarbleRadius
    val above = start.y - bottom
    val critical = sqrt(2 * maxOf(0.0, above) / CascadeGravity).coerceIn(begin, end)
    return -above / critical - .5 * CascadeGravity * critical
}

/** A fixed world-space contact plane in a perspective scene. All lengths and times are solved once per chart. */
internal class PlanarPath(chart: Chart, contactChart: Chart = chart.cascadeChart()) {
    private val durationMs = chart.durationMs
    private val startMs = -contactChart.cascadePreRollMs()
    val attacks: List<PlanarAttack>
    val panels: List<PlanarPanel>
    private val spans: List<PlanarSpan>
    private val endSpin: Float
    private val origin: StagePoint
    private val entry: CascadeEntry
    val entryRail: List<StagePoint> get() = entry.samples

    init {
        val notes = contactChart.notes
        val strikes = ArrayList<PlanarAttack>(notes.size)
        val keys = ArrayList<PlanarPanel>(notes.size)
        val sections = ArrayList<PlanarSpan>(notes.lastIndex)
        var direction = if ((notes.getOrNull(1)?.midi ?: notes.first().midi) >= notes.first().midi) 1 else -1
        origin = StagePoint(0f, -MarbleRadius, 0f)
        entry = CascadeEntry(notes.first().atMs, startMs, direction, origin)
        var center = origin
        var runSteps = 0
        var spin = entry.angle(notes.first().atMs) % 6.2831855f
        for ((i, note) in notes.withIndex()) {
            strikes += PlanarAttack(note.atMs, i, center.x, note.midi, note.playable)
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
            val guide = contactChart.hasGuideAfter(i)
            val beat = 60_000.0 / contactChart.bpm
            val previousInterval = if (i > 0) note.midi - notes[i - 1].midi else 0
            val nextInterval = notes.getOrNull(i + 2)?.let { it.midi - next.midi } ?: 0
            val fast = gap <= (.86 * beat).coerceIn(285.0, 435.0)
            // A staircase needs room for the whole key before the next strike.
            // On a tight pickup its back edge would cut across the incoming arc;
            // rebound towards the opposite bank instead.
            val scaleRun = abs(incomingX) * seconds >= .70 && abs(interval) in 1..3 &&
                (previousInterval * interval > 0 || nextInterval * interval > 0)
            val reply = !guide && !scaleRun && (fast || (gap <= 1.24 * beat &&
                (interval == 0 || interval * previousInterval < 0 || abs(interval) >= 5)))
            val turn = !guide && previous != null && (reply || center.x * direction > 4.3 ||
                (runSteps >= 2 && !scaleRun && interval * direction <= -3))
            if (turn) {
                direction = -direction
                runSteps = 0
            }
            runSteps++
            val targetSpeed = (if (fast) 3.52 else 2.70) + minOf(9, abs(interval)) * .10 +
                if (next.playable) .16 else 0.0
            val carriedSpeed = if (previous == null) targetSpeed else
                (.55 * abs(incomingX) + .45 * targetSpeed).coerceIn(2.40, 4.65)
            // Aim back at the facing bank, within the reach of this note's time
            // budget. A small drift is preferable to injecting horizontal energy.
            val reachSpeed = if (reply && previous != null && previous.rail == null) {
                (abs(center.x - previous.start.x) / seconds).coerceIn(.88 * carriedSpeed, 1.12 * carriedSpeed)
            } else carriedSpeed
            val energy = incomingX * incomingX + incomingY * incomingY
            val speed = minOf(reachSpeed, .94 * sqrt(energy))
            var panel = PlanarPanel(direction > 0, center.y + MarbleRadius, i, i)
            val slope: Double
            val rail: CascadeRail?
            val vx: Double
            val vy: Double
            if (guide) {
                val bend = when {
                    interval < -2 -> GuideBend.Scoop
                    interval > 2 -> GuideBend.Dive
                    else -> GuideBend.Wave
                }
                val air = (.28 + seconds * .065).coerceIn(.32, .43)
                val toward = abs(incomingX).coerceAtLeast(.01)
                val availablePitch = ((incomingY - .5 * CascadeGravity * (1.0 / toward)) / toward)
                    .coerceIn(.0, 1.0)
                rail = CascadeRail(center, direction, bend, seconds - air, speed,
                    availablePitch / hypot(1.0, availablePitch))
                slope = rail.entrySlope.toDouble()
                vx = rail.releaseVx
                vy = rail.releaseVy
                panel = panel.copy(railSamples = rail.samples)
            } else {
                rail = null
                vx = direction * speed
                var overhead = Double.NEGATIVE_INFINITY
                for (j in maxOf(0, i - 4) until i) {
                    overhead = maxOf(overhead, overheadVelocity(keys[j], strikes[j].x, center, vx, seconds))
                }
                val freeFall = .5 * CascadeGravity * seconds * seconds
                // A fast return redirects the lateral velocity while continuing
                // down past the other bank. Longer accents have time for a visible apex.
                val desiredDrop = when {
                    fast -> .64 + minOf(8, abs(interval)) * .018
                    reply -> .68
                    scaleRun -> 1.02
                    else -> .82
                }
                val maxRebound = minOf(5.0, .70 * incomingY)
                val desiredVy = (desiredDrop - freeFall) / seconds
                val separation = if (turn) incomingY - abs(vx - incomingX) / MaxKeySlope else .83 * incomingY
                val verticalBudget = sqrt(maxOf(0.0, energy - vx * vx))
                vy = maxOf(-maxRebound, overhead, minOf(desiredVy, separation)).coerceIn(-verticalBudget, verticalBudget)
                val requested = ((vx - incomingX) / maxOf(.01, incomingY - vy) * direction)
                    .coerceIn(-MaxKeySlope, MaxKeySlope)
                // A sharp loss of horizontal speed after a fast rail can tip the
                // key back into the new trajectory. Leave enough normal clearance
                // for the sphere to pass the full projecting lip and skirt.
                val face = (.13 - MarbleRadius * requested + maxOf(0.0, -.36 * requested)) /
                    hypot(1.0, requested) + MarbleRadius
                val departure = (vy + .5 * CascadeGravity * face / abs(vx)) / abs(vx)
                slope = direction * maxOf(requested, departure).coerceAtMost(MaxKeySlope)
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
        endSpin = spin
    }

    private fun attackIndex(time: Long): Int {
        var lo = 0
        var hi = attacks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (attacks[mid].atMs <= time) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    fun onRail(timeMs: Long): Boolean {
        val i = attackIndex(timeMs)
        val rail = spans.getOrNull(i)?.rail ?: return false
        return (timeMs - attacks[i].atMs) / 1000.0 <= rail.seconds
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
        val time = timeMs.coerceIn(startMs, durationMs)
        val index = attackIndex(time)
        // Uniform cubic B-spline: position, velocity and acceleration stay
        // continuous even when a staircase reverses at a key.
        val step = 280L
        val ahead = time + 85
        val knot = ahead - ahead.mod(step)
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
            return PlanarFrame(entry.position(time), cameraY, entry.angle(time), -1, null, cameraX, travel)
        }
        val span = spans.getOrNull(index)
        if (span == null) {
            val end = spans.lastOrNull()?.end ?: origin
            return PlanarFrame(end, cameraY, endSpin, index, null, cameraX, travel)
        }
        val t = (time - attacks[index].atMs) / 1000.0
        val rolling = if (span.rail != null && t <= span.rail.seconds) index else null
        return PlanarFrame(span.position(t), cameraY, span.angle(t), index, rolling, cameraX, travel)
    }
}
