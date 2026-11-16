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
import music.pinch.core.cascadePreRollMs
import music.pinch.core.hasGuideAfter

internal const val MarbleRadius = .23f
// World units per second squared, Y positive towards the bottom of the installation.
internal const val OrbitGravity = 28f
private const val Descent = 2.40f

/** One stroke per physical impact. A short roll finishes releasing its old key before
 * the next is struck; the final catcher stays loaded under the resting sphere. */
internal fun panelCompression(ageMs: Long, nextGapMs: Long?): Float {
    if (ageMs <= 0L) return 0f
    fun ease(u: Float) = u * u * (3f - 2f * u)
    if (nextGapMs == null) return .050f * ease((ageMs / 85f).coerceAtMost(1f))
    val release = minOf(200L, nextGapMs)
    if (ageMs >= release) return 0f
    val peak = minOf(42f, release * .29f)
    val u = if (ageMs < peak) ageMs / peak else (release - ageMs) / (release - peak)
    return .084f * ease(u)
}

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
    val atMs: Long, val midi: Int, val shape: PanelShape,
    val ballCenter: StagePoint, val normal: StagePoint, outward: StagePoint, adjacentGapMs: Long,
) {
    val center = ballCenter - normal * MarbleRadius
    private val spacing = ((adjacentGapMs - 180L) / 350f).coerceIn(0f, 1f)
    val halfLength = when (shape) {
        PanelShape.WoodBar -> .46f + .11f * spacing
        // A wider skin fits between widely spaced landings; rapid rolls use a small pad.
        PanelShape.Drum -> (.18f + (adjacentGapMs - 235L).coerceAtLeast(0L)/1100f)
            .coerceAtMost(.25f + .05f * spacing)
        PanelShape.Fork -> .53f + .11f * spacing
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
    fun point(x: Float, y: Float, z: Float) = StagePoint(
        center.x + length.x * x - normal.x * y + depth.x * z,
        center.y + length.y * x - normal.y * y + depth.y * z,
        center.z + length.z * x - normal.z * y + depth.z * z,
    )
}

internal interface CourseRun {
    val startMs: Long
    val endMs: Long
    val durationMs: Long get() = endMs - startMs
    val railEnd: Float
    fun ball(t: Float): StagePoint
    fun spin(t: Float): Float
    fun lift(t: Float): Float
    fun launchDirection(): StagePoint
    fun railNormal(t: Float): StagePoint
    fun point(t: Float) = ball(t) - railNormal(t) * MarbleRadius
}

/** Start and end are sphere centers. All trajectory coefficients are solved once from the audio interval. */
private class OrbitRun(
    override val startMs: Long, override val endMs: Long, private val start: StagePoint, private val end: StagePoint,
    private val fromAngle: Double, private val toAngle: Double, val initialSpin: Float, guided: Boolean,
) : CourseRun {
    private val seconds = durationMs / 1000f
    private val stationary = start == end
    private val drop = end.y - start.y
    // A long rest would force a gigantic ballistic arc. Keep the ball on curved
    // metal until the remaining fall fits the available vertical distance.
    private val flight = if (guided) min(.36f, sqrt(1.30f * drop / OrbitGravity)) else seconds
    private val rolling = if (stationary) 0f else seconds - flight
    override val railEnd = if (seconds == 0f) 0f else rolling / seconds
    private val lipAngle = fromAngle + (toAngle - fromAngle) * railEnd
    private val lip = if (rolling == 0f) start else orbit(lipAngle, start.y)
    private val fallTerm = if (stationary) 0f else .5f * OrbitGravity * flight * flight
    // Enter a guide horizontally: a steep entrance after a sixteenth would cut
    // through the arriving sphere. Solve its vertical curvature and lip velocity
    // from the remaining drop, preserving the tangent through the final free flight.
    private val vy = if (rolling <= 0f && seconds > 0f) (drop - fallTerm) / seconds else 0f
    private val guideAcceleration = if (rolling > 0f) (drop - fallTerm) / (rolling * (.5f * rolling + flight)) else 0f
    private val lipVy = vy + guideAcceleration * rolling
    private val flightVx = if (flight > 0f) (end.x - lip.x) / flight else 0f
    private val flightVz = if (flight > 0f) (end.z - lip.z) / flight else 0f
    private val angularSpeed = if (seconds > 0f) (toAngle - fromAngle) / seconds else 0.0
    private val blendTime = min(.24f, rolling * .65f)
    private val lipCorrectionX = flightVx - (2.12 * cos(lipAngle) * angularSpeed).toFloat()
    private val lipCorrectionZ = flightVz + (.94 * sin(lipAngle) * angularSpeed).toFloat()
    private val releaseSpeed = sqrt(flightVx * flightVx + flightVz * flightVz + lipVy * lipVy)
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

    override fun spin(t: Float): Float {
        // Roll by arc length through the curved guide; retain angular velocity after release.
        val distance = if (t < railEnd) {
            val sample = t / railEnd * rolledDistance.lastIndex
            val i = sample.toInt().coerceAtMost(rolledDistance.lastIndex - 1)
            rolledDistance[i] + (sample - i) * (rolledDistance[i + 1] - rolledDistance[i])
        } else rolledDistance.last() + (t - railEnd) * seconds * releaseSpeed
        return initialSpin + distance / MarbleRadius
    }

    override fun launchDirection(): StagePoint = if (rolling > 0f) StagePoint(
        (2.12 * cos(fromAngle) * angularSpeed).toFloat(), vy,
        (-.94 * sin(fromAngle) * angularSpeed).toFloat(),
    ).unit() else StagePoint(flightVx, vy, flightVz).unit()

    override fun ball(t: Float): StagePoint {
        if (stationary || t <= 0f) return start
        if (t >= 1f) return end
        val elapsed = seconds * t
        val air = (elapsed - rolling).coerceAtLeast(0f)
        val supported = min(elapsed, rolling)
        val y = start.y + supported * (vy + .5f * guideAcceleration * supported) + air * (lipVy + .5f * OrbitGravity * air)
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
    override fun railNormal(t: Float): StagePoint {
        val elapsed = seconds * t
        val angle = fromAngle + angularSpeed * elapsed
        val h = if (blendTime > 0f) ((elapsed - rolling + blendTime) / blendTime).coerceIn(0f, 1f) else 0f
        val derivative = 3f * h * h - 2f * h
        val vx = (2.12 * cos(angle) * angularSpeed).toFloat() + lipCorrectionX * derivative
        val vz = (-.94 * sin(angle) * angularSpeed).toFloat() + lipCorrectionZ * derivative
        val horizontal = sqrt(vx * vx + vz * vz).coerceAtLeast(.0001f)
        val vertical = vy + guideAcceleration * min(elapsed, rolling)
        return StagePoint(vx * vertical / horizontal, -horizontal, vz * vertical / horizontal).unit()
    }

    override fun lift(t: Float): Float {
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
        val contacts = if (chart.notes.size == attacks.size) chart else chart.copy(notes = attacks)
        val beatMs = 60_000.0 / chart.bpm
        var heading = if (phraseDirection(attacks, 0, beatMs) < 0) -1 else 1
        angles[1] = -.72
        val first = orbit(angles[1], -MarbleRadius)
        // The approach starts towards the middle of the installation and terminates at
        // the actual first key. Its metal is rendered from the same run as the marble.
        val entry = StarterChute(times[1], -chart.cascadePreRollMs(), StagePoint(-1f, 0f, 0f), first, OrbitGravity.toDouble(), .24)
        times[0] = entry.startMs
        val sections = ArrayList<CourseRun>(times.lastIndex)
        sections.add(entry)
        centers.add(entry.ball(0f))
        centers.add(first)
        integratedX[1] = entry.durationMs * (centers[0].x + first.x).toDouble() / 2
        var lastTurn = -4
        var spin = entry.spin(1f) % (2f * kotlin.math.PI.toFloat())
        for (i in 2..times.lastIndex) {
            val gapMs = times[i] - times[i - 1]
            val seconds = gapMs / 1000f
            val note = attacks.getOrNull(i - 2)
            val target = attacks.getOrNull(i - 1)
            val interval = if (note != null && target != null) target.midi - note.midi else 0
            // Turn below the old bank after a deep descent. Keep the return and its
            // immediate follow-up airborne arcs short enough to clear the bank above.
            // An eighth note can start the new phrase. Its onsets are rounded to milliseconds.
            val nextGap = if (i + 1 < times.lastIndex) times[i + 1] - times[i] else 0L
            if (i >= 3 && target != null && i - lastTurn >= 4 &&
                centers.last().y - centers[centers.lastIndex - 1].y > 1.62f &&
                gapMs.toDouble() in (.5 * beatMs - 1.0)..(1.12 * beatMs) &&
                nextGap <= 1.65 * beatMs) {
                val desired = phraseDirection(attacks, i - 2, beatMs)
                if (desired != 0 && desired != heading) {
                    heading = desired
                    lastTurn = i
                }
            }
            val reach = min(8, abs(interval)) / 8f
            val advance = (1.56 + .34 * reach) * seconds + (.28 + .06 * reach) * min(1f, seconds / .118f)
            val catcher = i == times.lastIndex
            angles[i] = if (catcher) angles[i - 1] else angles[i - 1] + heading * advance
            val drop = min((Descent - .12f + .22f * reach) * seconds, .19f * OrbitGravity * seconds * seconds)
            val previous = centers.last()
            val next = if (catcher) previous else orbit(angles[i], previous.y + drop)
            val guided = contacts.hasGuideAfter(i - 2)
            val span = OrbitRun(times[i - 1], times[i], previous, next, angles[i - 1], angles[i], spin, guided)
            sections.add(span)
            spin = span.spin(1f) % (2f * kotlin.math.PI.toFloat())
            centers.add(next)
            integratedX[i] = integratedX[i - 1] + (times[i] - times[i - 1]) * (previous.x + next.x).toDouble() / 2
        }
        spans = sections
        val gaps = attacks.zipWithNext { a, b -> (b.atMs - a.atMs).toDouble() }.sorted()
        val usualStep = gaps.getOrNull(gaps.size/2) ?: beatMs
        val sustainedMs = max(.75*beatMs, .88*usualStep)
        val longGapMs = max(1.25*beatMs, 1.40*usualStep)
        val firstAttack = attacks.first().atMs
        keys = attacks.mapIndexed { i, note ->
            val outgoing = spans[i + 1]
            val angle = angles[i + 1]
            val direction = if (i == attacks.lastIndex) {
                StagePoint(2.12f * cos(angle).toFloat(), 0f, -.94f * sin(angle).toFloat()).unit()
            } else outgoing.launchDirection()
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
            StageKey(note.atMs, note.midi, shape, centers[i + 1], normal, outward,
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

    fun onRail(timeMs: Long): Boolean {
        if (timeMs < times.first() || timeMs >= keys.last().atMs) return false
        val span = spans[spanIndex(timeMs)]
        val elapsed = timeMs - span.startMs
        return elapsed > 0L && elapsed < span.railEnd * span.durationMs
    }

    /** Box-filter the contact lane over a short time window so a reversal does not jerk the camera. */
    private fun xIntegral(time: Long): Double {
        if (time <= times.first()) return (time - times.first()) * centers.first().x.toDouble()
        if (time >= durationMs) return integratedX.last() + (time - durationMs) * centers.last().x.toDouble()
        val index = spanIndex(time)
        val elapsed = (time - times[index]).toDouble()
        val a = centers[index].x
        val b = centers[index + 1].x
        return integratedX[index] + elapsed * (a + .5 * (b - a) * elapsed / spans[index].durationMs)
    }

    fun frame(timeMs: Long): MarbleFrame {
        val time = timeMs.coerceIn(times.first(), durationMs)
        val last = keys.last()
        if (time >= last.atMs) {
            val center = last.ballCenter
            val targetX = ((xIntegral(last.atMs + 240) - xIntegral(last.atMs - 240)) * (.42 / 480)).toFloat()
            val ball = center - last.normal * panelCompression(time - last.atMs, null)
            return MarbleFrame(ball, StagePoint(targetX, center.y, .78f), keys.lastIndex, null,
                0f, 0f, spans.last().spin(0f))
        }
        val index = spanIndex(time)
        val run = spans[index]
        val t = if (run.durationMs > 0L) (time - run.startMs) / run.durationMs.toFloat() else 0f
        val ball = run.ball(t)
        val smoothedX = ((xIntegral(time + 240) - xIntegral(time - 240)) * (.42 / 480)).toFloat()
        // A camera moving at the average speed of the starter would overtake the ball
        // as it releases from rest. Follow its actual roll, then blend into the course.
        val targetX = if (index > 0) smoothedX else {
            val blend = ((time - run.endMs + 300) / 300f).coerceIn(0f, 1f)
            val openingX = ball.x * .42f
            openingX + (smoothedX - openingX) * (blend * blend * (3f - 2f * blend))
        }
        val targetY = if (index == 0) (ball.y + centers[1].y) * .5f
            else centers[index].y + (centers[index + 1].y - centers[index].y) * t
        return MarbleFrame(ball, StagePoint(targetX, targetY, .78f), index - 1,
            index.takeIf { it < keys.size }, t, run.lift(t), run.spin(t))
    }
}
