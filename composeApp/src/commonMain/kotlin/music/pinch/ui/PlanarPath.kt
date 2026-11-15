package music.pinch.ui

import kotlin.math.abs
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
)

internal const val CascadeGravity = 17.0
private const val StageEdge = 1.48
private const val MaxCrossingSpeed = 4.35 // world units per second
private const val Segments = 32
private const val RollingGravity = CascadeGravity * 5 / 7 // I = (2/5) m r² for a solid sphere.

// The two level end sections make entry onto a key and release off the lip exactly horizontal.
private fun curve(t: Double) = t * t * (3 - 2 * t)

private fun bend(t: Double) = if (t < .5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)

private fun shape(groove: Boolean, lead: Int = 1, compact: Boolean = false) = DoubleArray(Segments + 1) { index ->
    val u = ((index - lead).toDouble() / (Segments - lead - 1)).coerceIn(0.0, 1.0)
    when {
        compact -> bend(u)
        !groove -> curve(u)
        u < .55 -> 1.45 * bend(u / .55)
        else -> 1.45 - .45 * bend((u - .55) / .45)
    }
}
private val downhill = shape(false)
private val trough = shape(true)
private val longApproach = shape(false, lead = 12)
// Two parabolic halves distribute the change of tangent over the full guide.
// This bounds curvature even on short rails between tightly spaced keys.
private val compactRun = shape(false, compact = true)

private fun travelTime(profile: DoubleArray, width: Double, height: Double, speed: Double, flightDistance: Double): Double {
    var previousSpeed = speed
    var time = 0.0
    for (i in 1..Segments) {
        val nextSpeed = sqrt(speed * speed + 2 * RollingGravity * height * profile[i])
        val length = hypot(width / Segments, height * (profile[i] - profile[i - 1]))
        time += 2 * length / (previousSpeed + nextSpeed)
        previousSpeed = nextSpeed
    }
    return time + flightDistance / previousSpeed
}

/** Invert a monotone travel-time function; unreachable times select the closest physical limit. */
private inline fun fit(lo: Double, hi: Double, target: Double, timeAt: (Double) -> Double): Double {
    val first = timeAt(lo)
    val last = timeAt(hi)
    if (target <= minOf(first, last)) return if (first <= last) lo else hi
    if (target >= maxOf(first, last)) return if (first >= last) lo else hi
    var low = lo
    var high = hi
    repeat(36) {
        val mid = (low + high) * .5
        if ((timeAt(mid) < target) == (first < last)) low = mid else high = mid
    }
    return (low + high) * .5
}

private class CascadeRail(startX: Float, startY: Float, nextX: Float, gap: Double, variant: Int) {
    private val direction = if (nextX > startX) 1 else -1
    private val distance = abs(nextX.toDouble() - startX)
    private val preferredSpeed = 1.9
    private val longRun = gap >= 1.35
    private val varyLength = variant != 1 || longRun
    private val grooved = variant == 2 && gap >= .72 && !longRun && distance >= 1.8
    private val compact = distance < 2.15
    private val compactWidth = maxOf(.55, distance * .43)
    private val profile = when {
        // Spread the slow approach over the available run before the bend.
        // Keep enough curved track for the sphere to follow its normal.
        compact && longRun -> {
            val lead = ((1.0 - .55 / compactWidth) * Segments).toInt().coerceIn(1, 12)
            shape(false, lead, compact = true)
        }
        compact -> compactRun
        longRun -> longApproach
        grooved -> trough
        else -> downhill
    }
    // A long rest gets a longer level approach. The ball can continue to roll visibly across the gap.
    // A short guide needs a shallower bend to keep the rolling sphere in contact.
    private val pitchScale = min(1.0, distance * distance / (2.72 * 2.72))
    // At a horizontal lip v² must exceed g times the ball/rod contact radius
    // or the ball would pivot around the rod end before entering free flight.
    private val standardDrop = maxOf(.18, (if (longRun || grooved) .21 else .32) * pitchScale)
    private val width = if (compact) {
        // Leave flight room below a compact guide before the course turns back.
        compactWidth
    } else if (varyLength) {
        val broad = longRun || grooved
        val minWidth = distance * (if (broad) .62 else .44)
        val maxWidth = distance * (if (broad) .65 else .60)
        fit(minWidth, maxWidth, gap) { w -> travelTime(profile, w, standardDrop, preferredSpeed, distance - w) }
    } else distance * .56
    private val height = if (varyLength) standardDrop else fit(maxOf(.18, .20 * pitchScale), maxOf(.20, .43 * pitchScale), gap) { h ->
        travelTime(profile, width, h, preferredSpeed, distance - width)
    }
    // The spring at the strike establishes the entry speed when the available reach or pitch is exhausted.
    private val entrySpeed = fit(min(1e-9, width / (gap * Segments * 2)), distance / gap * 2, gap) { v ->
        travelTime(profile, width, height, v, distance - width)
    }
    val samples = List(Segments + 1) { i ->
        StagePoint((startX + direction * width * i / Segments).toFloat(), (startY + height * profile[i]).toFloat(), 0f)
    }
    private val arc = DoubleArray(Segments + 1)
    private val time = DoubleArray(Segments + 1)
    private val speeds = DoubleArray(Segments + 1) { i -> sqrt(entrySpeed * entrySpeed + 2 * RollingGravity * height * profile[i]) }
    init {
        for (i in 1..Segments) {
            val ds = hypot(width / Segments, height * (profile[i] - profile[i - 1]))
            arc[i] = arc[i - 1] + ds
            time[i] = time[i - 1] + 2 * ds / (speeds[i - 1] + speeds[i])
        }
    }
    val seconds = time.last()
    val length = arc.last()
    val releaseVx = direction * speeds.last()
    val landingDrop = height + .5 * CascadeGravity * (gap - seconds) * (gap - seconds)

    fun distanceAt(t: Double): Double {
        val i = segment(time, t)
        val dt = t - time[i]
        val acceleration = (speeds[i + 1] - speeds[i]) / (time[i + 1] - time[i])
        return (arc[i] + dt * (speeds[i] + .5 * acceleration * dt)).coerceIn(0.0, length)
    }

    fun centerAt(s: Double): StagePoint {
        val i = segment(arc, s)
        val u = ((s - arc[i]) / (arc[i + 1] - arc[i])).toFloat()
        val a = samples[i]
        val b = samples[i + 1]
        return StagePoint(a.x + u * (b.x - a.x), a.y + u * (b.y - a.y), 0f)
    }

    private fun segment(table: DoubleArray, position: Double): Int {
        var lower = 0
        var upper = table.lastIndex
        while (lower + 1 < upper) {
            val middle = (lower + upper) / 2
            if (position < table[middle]) upper = middle else lower = middle
        }
        return lower
    }
}

private data class PlanarSpan(val rail: CascadeRail?, val launchVy: Double, val vx: Double, val initialSpin: Float) {
    fun airborneAngle(elapsed: Double): Float {
        val rolled = if (rail == null) 0.0 else if (vx < 0) -rail.length else rail.length
        val travel = rolled + vx * (elapsed - (rail?.seconds ?: 0.0))
        return initialSpin + (travel / MarbleRadius).toFloat()
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
    private val integratedHeight: DoubleArray

    init {
        val notes = contactChart.notes
        val echoes = contactChart.echoTargetTimes()
        val beat = 60_000.0 / chart.bpm
        val strikes = ArrayList<PlanarAttack>(notes.size)
        val keys = ArrayList<PlanarPanel>(notes.size)
        val guides = BooleanArray(notes.size)
        // Reserve a lane for the first phrase. Following a pitch contour gives a run
        // of steps; a turn in the melody or the edge of the installation ends that run.
        var direction = if ((notes.getOrNull(1)?.midi ?: notes.first().midi) >= notes.first().midi) 1 else -1
        var x = -direction * StageEdge * .78
        for ((index, note) in notes.withIndex()) {
            val next = notes.getOrNull(index + 1)
            val strikeX = x.toFloat()
            if (next != null) {
                val gap = next.atMs - note.atMs
                val seconds = gap / 1000.0
                val interval = next.midi - note.midi
                // Very long rests need a run-in. Sustained notes and wider gaps
                // within a phrase can also become guides, with a short flight off the lip.
                val guide = seconds >= .90 || (seconds >= .61 &&
                    (note.durationMs >= gap * .40 || (gap > beat * 1.38 && abs(interval) <= 2)))
                guides[index] = guide
                val preferred = when {
                    interval >= 2 -> 1
                    interval <= -2 -> -1
                    else -> direction
                }
                // A close ornament already belongs to the recording. Selected contacts have
                // enough room for a separate plate. Use the flight time to limit its reach and keep
                // space for the following plate when the contour continues in the same direction.
                val speed = 2.35 + minOf(abs(interval), 9) * .105 + if (next.playable) .32 else 0.0
                val reach = min(StageEdge * 1.82, MaxCrossingSpeed * seconds)
                val wanted = if (guide) (1.70 + (seconds - .61) * .72).coerceAtMost(2.57)
                    else (speed * seconds).coerceIn(.79, 1.95)
                val minStep = if (guide) 1.35 else (1.9 * seconds).coerceIn(.80, 1.42)
                direction = if (StageEdge - preferred * x >= minStep) preferred else -preferred
                val room = StageEdge - direction * x
                val followingPitch = notes.getOrNull(index + 2)?.midi
                val continuation = followingPitch != null && (followingPitch - next.midi) * direction >= 0
                val reserve = if (continuation && !guide && room > 2 * minStep) .46 else 0.0
                x += direction * minOf(room - reserve, wanted, reach)
            }
            val left = direction > 0
            keys += PlanarPanel(left, 0f, index, index)
            strikes += PlanarAttack(note.atMs, index, strikeX, note.midi, note.playable, note.atMs in echoes)
        }
        val sections = ArrayList<PlanarSpan>(notes.lastIndex)
        val cues = mutableListOf<Note>()
        var spin = 0f
        var sourceIndex = 0
        integratedHeight = DoubleArray(notes.size)
        for (i in 0 until notes.lastIndex) {
            val from = strikes[i]
            val to = strikes[i + 1]
            val gap = to.atMs - from.atMs
            val seconds = gap / 1000.0
            val panel = keys[from.panelIndex]
            val y = panel.topY - MarbleRadius
            val interval = to.midi - from.midi
            val variant = when {
                interval < 0 -> 2
                notes[i].durationMs >= gap * .48 -> 0
                else -> 1
            }
            val rail = if (guides[i]) CascadeRail(from.x, y, to.x, seconds, variant) else null
            val previous = sections.lastOrNull()
            val incomingTime = if (previous == null) min(.38, from.atMs / 1000.0)
                else (from.atMs - strikes[i - 1].atMs) / 1000.0 - (previous.rail?.seconds ?: 0.0)
            val incomingVy = (previous?.launchVy ?: 0.0) + CascadeGravity * incomingTime
            // A lossy collision retains a fraction of the arrival speed. Integrate
            // the resulting flight to place the next surface on its musical onset.
            val freeFall = .5 * CascadeGravity * seconds * seconds
            val vx = rail?.releaseVx ?: (to.x - from.x) / seconds
            val overhead = if (i == 0 || rail != null) Double.POSITIVE_INFINITY
                else returnSpeedLimit(keys[i - 1], strikes[i - 1].x, from.x, panel.topY, vx)
            val rebound = minOf(overhead, 3.60, .49 * incomingVy, maxOf(0.0, (freeFall - .92) / seconds))
            val drop = rail?.landingDrop ?: freeFall - seconds * rebound
            keys[i + 1] = keys[i + 1].copy(topY = (panel.topY + drop).toFloat())
            // A lamp marks a real intervening note while the sphere is supported.
            // Leave the entry, release and rapid ornaments clear. A held note has no lamps.
            if (rail != null) {
                val lights = ArrayList<Note>()
                val releaseMs = from.atMs + (rail.seconds * 1000).roundToLong()
                while (sourceIndex < chart.notes.size && chart.notes[sourceIndex].atMs < releaseMs - 70) {
                    val note = chart.notes[sourceIndex++]
                    if (note.atMs - from.atMs < 100) continue
                    val previousLight = lights.lastOrNull()
                    if (previousLight == null || note.atMs - previousLight.atMs >= 150) lights += note
                    else if (note.velocity > previousLight.velocity) lights[lights.lastIndex] = note
                }
                cues += lights
                val lamps = lights.map { rail.centerAt(rail.distanceAt((it.atMs - from.atMs) / 1000.0)) }
                keys[i] = panel.copy(railSamples = rail.samples, ledCenters = lamps,
                    ledTimesMs = lights.map { it.atMs })
            }
            val vy = if (rail != null) 0.0 else drop / seconds - .5 * CascadeGravity * seconds
            if (rail == null) {
                // The incoming and outgoing momentum give the normal of the strike.
                // Limit the pitch to the key's mechanical range.
                val horizontalImpulse = vx - (previous?.vx ?: 0.0)
                val upwardImpulse = maxOf(.01, incomingVy - vy)
                keys[i] = panel.copy(slope = (horizontalImpulse / upwardImpulse).coerceIn(-.56, .56).toFloat())
            }
            val section = PlanarSpan(rail, vy, vx, spin)
            sections += section
            spin = section.airborneAngle(seconds) % 6.2831855f
            integratedHeight[i + 1] = integratedHeight[i] + gap * (panel.topY + .5 * drop)
        }
        endSpin = spin
        spans = sections
        attacks = strikes
        panels = keys
        ledCues = cues
    }

    private fun attackIndex(time: Long): Int {
        val found = attacks.binarySearchBy(time) { it.atMs }
        return if (found >= 0) found else -found - 2
    }

    private fun heightIntegral(time: Long): Double {
        val i = attackIndex(time)
        if (i < 0) return 0.0
        val from = attacks[i]
        val top = panels[from.panelIndex].topY
        val elapsed = (time - from.atMs).toDouble()
        val next = attacks.getOrNull(i + 1) ?: return integratedHeight[i] + elapsed * top
        val drop = panels[next.panelIndex].topY - top
        return integratedHeight[i] + elapsed * (top + .5 * drop * elapsed / (next.atMs - from.atMs))
    }

    fun frame(timeMs: Long): PlanarFrame {
        val time = timeMs.coerceIn(0L, durationMs)
        val index = attackIndex(time)
        val cameraY = ((heightIntegral(time + 120) - heightIntegral(time - 120)) / 240).toFloat() - MarbleRadius
        if (index < 0) {
            val first = attacks.first()
            val duration = min(first.atMs / 1000.0, .38)
            val t = (duration - (first.atMs - time) / 1000.0).coerceAtLeast(0.0)
            val y = .5 * CascadeGravity * (t * t - duration * duration) - MarbleRadius
            return PlanarFrame(StagePoint(first.x, y.toFloat(), 0f), cameraY, 0f, -1, null, -1)
        }
        val from = attacks[index]
        val panel = panels[from.panelIndex]
        val y = panel.topY - MarbleRadius
        val t = (time - from.atMs) / 1000.0
        if (index == attacks.lastIndex) {
            // The final catcher absorbs the strike. A rebound here would make
            // another visible contact after the music has already ended.
            return PlanarFrame(StagePoint(from.x, y, 0f), cameraY, endSpin, index, null, -1)
        }
        val span = spans[index]
        val rail = span.rail
        if (rail != null && t <= rail.seconds) {
            val s = rail.distanceAt(t)
            val angle = span.initialSpin + (if (panel.left) s else -s).toFloat() / MarbleRadius
            val lit = panel.ledTimesMs.indexOfLast { it <= time }
            return PlanarFrame(rail.centerAt(s), cameraY, angle, index, from.panelIndex, lit)
        }
        val air = t - (rail?.seconds ?: 0.0)
        val lip = rail?.samples?.last()
        val x = (lip?.x ?: from.x) + span.vx * air
        val ballY = (lip?.y ?: y) + air * (span.launchVy + .5 * CascadeGravity * air)
        return PlanarFrame(StagePoint(x.toFloat(), ballY.toFloat(), 0f), cameraY,
            span.airborneAngle(t), index, null, -1)
    }
}
