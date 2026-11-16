package music.pinch.ui

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** The unkeyed approach. A solid sphere accelerates from rest along an incline, then
 * leaves the lip with the same linear and angular velocities and falls onto key zero. */
internal class StarterChute(
    override val endMs: Long, earliestMs: Long, heading: StagePoint,
    private val target: StagePoint, private val gravity: Double, private val flight: Double,
) : CourseRun {
    override val startMs = maxOf(earliestMs, endMs - 3200L)
    private val seconds = durationMs / 1000.0
    private val rolling = seconds - flight
    // Keep both ends of the starter in the opening view, even at slow tempos.
    private val lipSpeed = min(2.10, 2.76 / (.5 * rolling + flight))
    private val acceleration = lipSpeed / rolling
    private val sine = acceleration / (gravity * 5 / 7)
    private val cosine = sqrt(1 - sine * sine)
    val arrivalVx = heading.x * cosine * lipSpeed
    val arrivalVy = sine * lipSpeed + gravity * flight
    private val length = lipSpeed * .5 * rolling
    private val tangent = StagePoint((heading.x * cosine).toFloat(), sine.toFloat(), (heading.z * cosine).toFloat())
    private val normal = StagePoint((heading.x * sine).toFloat(), -cosine.toFloat(), (heading.z * sine).toFloat())
    private val lip = target - tangent * (lipSpeed * flight).toFloat() - StagePoint(0f, (.5 * gravity * flight * flight).toFloat(), 0f)
    private val start = lip - tangent * length.toFloat()
    override val railEnd = (rolling / seconds).toFloat()
    val samples: List<StagePoint> by lazy {
        val count = ceil(length / .19).toInt().coerceAtLeast(2)
        List(count + 1) { i -> start + tangent * (length * i / count).toFloat() }
    }

    fun progress(timeMs: Long) = ((timeMs - startMs) / durationMs.toFloat()).coerceIn(0f, 1f)
    override fun launchDirection() = tangent
    override fun railNormal(t: Float) = normal

    override fun ball(t: Float): StagePoint {
        if (t <= 0f) return start
        if (t >= 1f) return target
        val elapsed = t * seconds
        if (elapsed <= rolling) return start + tangent * (.5 * acceleration * elapsed * elapsed).toFloat()
        val air = elapsed - rolling
        return lip + tangent * (lipSpeed * air).toFloat() + StagePoint(0f, (.5 * gravity * air * air).toFloat(), 0f)
    }

    override fun spin(t: Float): Float {
        val elapsed = t.coerceIn(0f, 1f) * seconds
        val supported = min(elapsed, rolling)
        return ((.5 * acceleration * supported * supported + lipSpeed * (elapsed - supported)) / MarbleRadius).toFloat()
    }

    override fun lift(t: Float): Float {
        val air = (t * seconds - rolling).coerceIn(0.0, flight)
        return (.5 * gravity * air * (flight - air)).toFloat()
    }
}
