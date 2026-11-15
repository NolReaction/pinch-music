package music.pinch.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import music.pinch.core.Chart
import music.pinch.core.echoTargetTimes

internal const val MarbleRadius = .23f
// World units per second squared, Y positive towards the bottom of the installation.
private const val Gravity = 64f
private const val Descent = 2.40f

internal data class StagePoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: StagePoint) = StagePoint(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: StagePoint) = StagePoint(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Float) = StagePoint(x * factor, y * factor, z * factor)
    fun length() = sqrt(x * x + y * y + z * z)
    fun unit() = this * (1f / length().coerceAtLeast(.00001f))
    fun cross(b: StagePoint) = StagePoint(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
}

/** The contacts wind down the front and back lanes of an oval, with space for the return pass. */
private fun orbit(angle: Double, y: Float) =
    StagePoint(2.12f * sin(angle).toFloat(), y, .78f + .94f * cos(angle).toFloat())

internal enum class PanelShape { WoodBar, Drum, Fork }

internal class StageKey(
    val atMs: Long, val midi: Int, val playable: Boolean, val isEcho: Boolean, val shape: PanelShape,
    val ballCenter: StagePoint, val normal: StagePoint, outward: StagePoint, adjacentGapMs: Long,
) {
    val center = ballCenter - normal * MarbleRadius
    val halfLength = when (shape) {
        PanelShape.WoodBar -> if (playable) .57f else .46f
        // A wider skin fits between widely spaced landings; rapid rolls use a small pad.
        PanelShape.Drum -> (.18f + (adjacentGapMs - 235L).coerceAtLeast(0L)/1100f)
            .coerceAtMost(if (playable) .30f else .25f)
        PanelShape.Fork -> if (playable) .64f else .53f
    }
    val halfWidth = when (shape) {
        PanelShape.WoodBar -> .12f
        PanelShape.Drum -> halfLength
        PanelShape.Fork -> .15f
    }
    // Bar spans across the direction of travel. Orthonormal axes keep the two
    // rivets, top face and strike point on the very same plane when it is banked.
    private val depth = normal.cross(outward).unit()
    private val length = depth.cross(normal)
    fun point(x: Float, y: Float, z: Float) = center + length * x - normal * y + depth * z
}

/** Start and end are sphere centers. All trajectory coefficients are solved once from the audio interval. */
internal class CourseRun(
    val startMs: Long, val endMs: Long, private val start: StagePoint, private val end: StagePoint,
    private val fromAngle: Double, private val toAngle: Double, val initialSpin: Float,
) {
    val durationMs = endMs - startMs
    private val seconds = durationMs / 1000f
    private val drop = end.y - start.y
    // A long rest would force a gigantic ballistic arc. Keep the ball on curved
    // metal until the remaining fall fits the available vertical distance.
    private val flight = if (seconds <= .57f) seconds else min(.36f, sqrt(1.30f * drop / Gravity))
    private val rolling = seconds - flight
    val railEnd = if (seconds == 0f) 0f else rolling / seconds
    private val lipAngle = fromAngle + (toAngle - fromAngle) * railEnd
    private val lip = if (rolling == 0f) start else orbit(lipAngle, start.y)
    private val fallTerm = .5f * Gravity * flight * flight
    private val vy = if (seconds > 0f) (drop - fallTerm) / seconds else 0f
    private val flightVx = if (flight > 0f) (end.x - lip.x) / flight else 0f
    private val flightVz = if (flight > 0f) (end.z - lip.z) / flight else 0f
    private val angularSpeed = if (seconds > 0f) (toAngle - fromAngle) / seconds else 0.0
    private val blendTime = min(.24f, rolling * .65f)
    private val lipCorrectionX = flightVx - (2.12 * cos(lipAngle) * angularSpeed).toFloat()
    private val lipCorrectionZ = flightVz + (.94 * sin(lipAngle) * angularSpeed).toFloat()
    private val releaseSpeed = sqrt(flightVx * flightVx + flightVz * flightVz + vy * vy)
    private val rolledDistance = if (rolling <= 0f) floatArrayOf(0f) else {
        val steps = ceil(rolling / .04f).toInt().coerceIn(8, 512)
        val distance = FloatArray(steps + 1)
        var previous = start
        for (i in 1..steps) {
            val point = ball(railEnd * i / steps)
            distance[i] = distance[i - 1] + (point - previous).length()
            previous = point
        }
        distance
    }

    fun spin(t: Float): Float {
        // Roll by arc length through the curved guide; retain angular velocity after release.
        val distance = if (t < railEnd) {
            val sample = t / railEnd * rolledDistance.lastIndex
            val i = sample.toInt().coerceAtMost(rolledDistance.lastIndex - 1)
            rolledDistance[i] + (sample - i) * (rolledDistance[i + 1] - rolledDistance[i])
        } else rolledDistance.last() + (t - railEnd) * seconds * releaseSpeed
        return initialSpin + distance / MarbleRadius
    }

    fun launchDirection(): StagePoint = if (rolling > 0f) StagePoint(
        (2.12 * cos(fromAngle) * angularSpeed).toFloat(), vy,
        (-.94 * sin(fromAngle) * angularSpeed).toFloat(),
    ).unit() else StagePoint(flightVx, vy, flightVz).unit()

    fun ball(t: Float): StagePoint {
        if (t <= 0f) return start
        if (t >= 1f) return end
        val elapsed = seconds * t
        val air = (elapsed - rolling).coerceAtLeast(0f)
        val y = start.y + vy * elapsed + .5f * Gravity * air * air
        if (rolling <= 0f || t >= railEnd) {
            return StagePoint(lip.x + flightVx * air, y, lip.z + flightVz * air)
        }
        val p = orbit(fromAngle + angularSpeed * elapsed, y)
        // Match horizontal as well as vertical velocity at the rail lip. This
        // cubic correction has zero displacement at both ends of the blend.
        val h = ((elapsed - rolling + blendTime) / blendTime).coerceIn(0f, 1f)
        val correction = (elapsed - rolling) * h * h
        return StagePoint(p.x + lipCorrectionX * correction, y, p.z + lipCorrectionZ * correction)
    }

    /** Unit normal above the running surface, perpendicular to the sphere's tangent. */
    fun railNormal(t: Float): StagePoint {
        val elapsed = seconds * t
        val angle = fromAngle + angularSpeed * elapsed
        val h = if (blendTime > 0f) ((elapsed - rolling + blendTime) / blendTime).coerceIn(0f, 1f) else 0f
        val derivative = 3f * h * h - 2f * h
        val vx = (2.12 * cos(angle) * angularSpeed).toFloat() + lipCorrectionX * derivative
        val vz = (-.94 * sin(angle) * angularSpeed).toFloat() + lipCorrectionZ * derivative
        val horizontal = sqrt(vx * vx + vz * vz).coerceAtLeast(.0001f)
        return StagePoint(vx * vy / horizontal, -horizontal, vz * vy / horizontal).unit()
    }

    // The rail mesh is sampled at this actual contact, including on a sloping guide.
    fun point(t: Float) = ball(t) - railNormal(t) * MarbleRadius

    fun lift(t: Float): Float {
        if (t <= railEnd || t >= 1f) return 0f
        val u = (t - railEnd) / (1f - railEnd)
        return fallTerm * u * (1f - u)
    }
}

internal data class MarbleFrame(
    val marble: StagePoint, val target: StagePoint, val anchor: Int, val next: Int?,
    val progress: Float, val lift: Float, val rollAngle: Float,
)

/** An absolute media timestamp indexes both the sounding attack and this exact contact.
 * Coincident pitches share one physical bar. Their manual accent survives either note order. */
internal class MarblePath(chart: Chart) {
    private val attacks = chart.notes.groupBy { it.atMs }.values.map { chord ->
        chord.firstOrNull { it.playable } ?: chord.first()
    }
    private val durationMs = chart.durationMs
    private val times = longArrayOf(0L, *attacks.map { it.atMs }.toLongArray(), durationMs)
    private val centers = ArrayList<StagePoint>(times.size)
    private val angles = DoubleArray(times.size)
    private val integratedX = DoubleArray(times.size)
    val spans: List<CourseRun>
    val keys: List<StageKey>

    init {
        angles[0] = -.72
        centers.add(orbit(angles[0], MarbleRadius * -1f))
        val sections = ArrayList<CourseRun>(times.lastIndex)
        var spin = 0f
        for (i in 1..times.lastIndex) {
            val seconds = (times[i] - times[i - 1]) / 1000f
            // Give fast alternating notes room around the oval, while also keeping
            // the vertical step below a free fall from rest for very short intervals.
            val advance = 1.72 * seconds + .28 * min(1f, seconds / .118f)
            angles[i] = angles[i - 1] + advance
            val drop = min(Descent * seconds, .19f * Gravity * seconds * seconds)
            val previous = centers.last()
            val next = orbit(angles[i], previous.y + drop)
            val span = CourseRun(times[i - 1], times[i], previous, next, angles[i - 1], angles[i], spin)
            sections.add(span)
            spin = span.spin(1f) % (2f * kotlin.math.PI.toFloat())
            centers.add(next)
            integratedX[i] = integratedX[i - 1] + (times[i] - times[i - 1]) * (previous.x + next.x).toDouble() / 2
        }
        spans = sections
        val echoes = chart.echoTargetTimes()
        val beatMs = 60_000.0 / chart.bpm
        val gaps = attacks.zipWithNext { a, b -> (b.atMs - a.atMs).toDouble() }.sorted()
        val usualStep = gaps.getOrNull(gaps.size/2) ?: beatMs
        val sustainedMs = max(.75*beatMs, .88*usualStep)
        val longGapMs = max(1.25*beatMs, 1.40*usualStep)
        val firstAttack = attacks.first().atMs
        keys = attacks.mapIndexed { i, note ->
            val outgoing = spans[i + 1]
            val angle = angles[i + 1]
            val direction = outgoing.launchDirection()
            // The plate releases the sphere onto the curved rail. Pitch it a
            // little below the rail tangent so the outside edge clears the ball.
            val normal = if (outgoing.railEnd > 0f) (outgoing.railNormal(0f) + direction * .09f).unit()
                else StagePoint(.10f * sin(angle).toFloat(), -1f, .16f * cos(angle).toFloat()).unit()
            val outward = StagePoint(-direction.z, 0f, direction.x).unit()
            // Sustains ring on a fork. Beat-aligned strikes get a skin; the smaller
            // wood bars keep the intervening subdivisions legible in a fast run.
            val beat = (note.atMs - firstAttack) / beatMs
            val nearestBeat = round(beat)
            val downbeat = nearestBeat.toLong() % 4L == 0L
            val shape = when {
                note.durationMs >= sustainedMs || outgoing.durationMs >= longGapMs -> PanelShape.Fork
                abs(beat-nearestBeat) <= .075 && (downbeat || note.durationMs < .67*beatMs) -> PanelShape.Drum
                else -> PanelShape.WoodBar
            }
            StageKey(note.atMs, note.midi, note.playable, note.atMs in echoes, shape, centers[i + 1], normal, outward,
                min(spans[i].durationMs, outgoing.durationMs))
        }
    }

    private fun spanIndex(time: Long): Int {
        // A chart can start with a chord at zero. Use the last equal timestamp.
        var lo = 0
        var hi = times.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= time) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(spans.lastIndex)
    }

    /** Box-filter the contact lane over a short time window so a reversal does not jerk the camera. */
    private fun xIntegral(time: Long): Double {
        if (time <= 0) return time * centers.first().x.toDouble()
        if (time >= durationMs) return integratedX.last() + (time - durationMs) * centers.last().x.toDouble()
        val index = spanIndex(time)
        val elapsed = (time - times[index]).toDouble()
        val a = centers[index].x
        val b = centers[index + 1].x
        return integratedX[index] + elapsed * (a + .5 * (b - a) * elapsed / spans[index].durationMs)
    }

    fun frame(timeMs: Long): MarbleFrame {
        val time = timeMs.coerceIn(0L, durationMs)
        val index = spanIndex(time)
        val run = spans[index]
        val t = if (run.durationMs > 0L) (time - run.startMs) / run.durationMs.toFloat() else 0f
        val targetX = ((xIntegral(time + 240) - xIntegral(time - 240)) * (.42 / 480)).toFloat()
        val targetY = centers[index].y + (centers[index + 1].y - centers[index].y) * t
        return MarbleFrame(run.ball(t), StagePoint(targetX, targetY, .78f), index - 1,
            index.takeIf { it < keys.size }, t, run.lift(t), run.spin(t))
    }
}
