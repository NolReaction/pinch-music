package music.pinch.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.echoTargetTimes

internal data class PlanarAttack(
    val atMs: Long, val panelIndex: Int, val x: Float, val midi: Int,
    val playable: Boolean, val isEcho: Boolean,
)

/** The renderer offsets these centreline samples along the normal to make the two supporting rods. */
internal data class PlanarPanel(
    val left: Boolean, val topY: Float, val firstAttack: Int, val lastAttack: Int,
    val railSamples: List<StagePoint> = emptyList(), val ledCenters: List<StagePoint> = emptyList(),
    val ledTimesMs: List<Long> = emptyList(),
)

internal data class PlanarFrame(
    val marble: StagePoint, val cameraY: Float, val rollAngle: Float,
    val attackIndex: Int, val rollingPanel: Int?, val litLedIndex: Int,
)

internal const val CascadeGravity = 42.0
private const val RepeatGapMs = 170L
private const val RailGapMs = 610L
private const val Segments = 32
private const val RollingGravity = CascadeGravity * 5 / 7 // I = (2/5) m r² for a solid sphere.

// The two level end sections make entry onto a key and release off the lip exactly horizontal.
private fun curve(t: Double) = t * t * (3 - 2 * t)

private fun bend(t: Double) = if (t < .5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)

private fun shape(groove: Boolean, lead: Int = 1) = DoubleArray(Segments + 1) { index ->
    val u = ((index - lead).toDouble() / (Segments - lead - 1)).coerceIn(0.0, 1.0)
    when {
        !groove -> curve(u)
        u < .55 -> 1.45 * bend(u / .55)
        else -> 1.45 - .45 * bend((u - .55) / .45)
    }
}
private val downhill = shape(false)
private val trough = shape(true)
private val longApproach = shape(false, lead = 12)

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
    private val grooved = variant == 2 && gap >= .72 && !longRun
    private val profile = when {
        longRun -> longApproach
        grooved -> trough
        else -> downhill
    }
    // A long rest gets a longer level approach. The ball can continue to roll visibly across the gap.
    private val standardDrop = if (longRun || grooved) .21 else .32
    private val width = if (varyLength) {
        val broad = longRun || grooved
        val minWidth = distance * (if (broad) .62 else .44)
        val maxWidth = distance * (if (broad) .65 else .60)
        fit(minWidth, maxWidth, gap) { w -> travelTime(profile, w, standardDrop, preferredSpeed, distance - w) }
    } else distance * .56
    private val height = if (varyLength) standardDrop else fit(.20, .43, gap) { h ->
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

    fun timeAtDistance(s: Double): Double {
        val i = segment(arc, s)
        val ds = s - arc[i]
        val acceleration = (speeds[i + 1] - speeds[i]) / (time[i + 1] - time[i])
        val v = sqrt(speeds[i] * speeds[i] + 2 * acceleration * ds)
        return time[i] + 2 * ds / (speeds[i] + v)
    }

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

/** A fixed world-space contact plane in a perspective scene. All lengths and times are solved once per chart. */
internal class PlanarPath(chart: Chart) {
    private val durationMs = chart.durationMs
    val attacks: List<PlanarAttack>
    val panels: List<PlanarPanel>
    val ledCues: List<Note>
    private val spans: List<PlanarSpan>
    private val endSpin: Float
    private val integratedHeight: DoubleArray

    init {
        val notes = chart.notes.groupBy { it.atMs }.values.map { chord -> chord.firstOrNull { it.playable } ?: chord.first() }
        val echoes = chart.echoTargetTimes()
        val starts = mutableListOf(0)
        for (i in 1 until notes.size) if (notes[i].atMs - notes[i - 1].atMs >= RepeatGapMs) starts += i
        val strikes = ArrayList<PlanarAttack>(notes.size)
        val keys = ArrayList<PlanarPanel>(starts.size)
        for ((index, first) in starts.withIndex()) {
            val last = (starts.getOrNull(index + 1) ?: notes.size) - 1
            val left = index % 2 == 0
            val spread = 1.28f + ((notes[first].midi % 7) - 3) * .062f
            val x = if (left) -spread else spread
            keys += PlanarPanel(left, 0f, first, last)
            for (i in first..last) {
                val n = notes[i]
                strikes += PlanarAttack(n.atMs, index, x, n.midi, n.playable, n.atMs in echoes)
            }
        }
        val sections = ArrayList<PlanarSpan>(notes.lastIndex)
        val cues = mutableListOf<Note>()
        var spin = 0f
        integratedHeight = DoubleArray(notes.size)
        for (i in 0 until notes.lastIndex) {
            val from = strikes[i]
            val to = strikes[i + 1]
            val gap = to.atMs - from.atMs
            val seconds = gap / 1000.0
            val panel = keys[from.panelIndex]
            val y = panel.topY - MarbleRadius
            val rail = if (gap >= RailGapMs) CascadeRail(from.x, y, to.x, seconds, from.panelIndex % 3) else null
            val sameKey = from.panelIndex == to.panelIndex
            val drop = if (sameKey) 0.0 else rail?.landingDrop ?: .36 * CascadeGravity * seconds * seconds
            if (!sameKey) keys[to.panelIndex] = keys[to.panelIndex].copy(topY = (panel.topY + drop).toFloat())
            if (rail != null) {
                val count = (rail.seconds / .17).toInt().coerceIn(2, 5)
                val lamps = ArrayList<StagePoint>(count)
                val times = List(count) { lamp ->
                    val s = rail.length * (lamp + 1) / (count + 1)
                    val offset = (1000 * rail.timeAtDistance(s)).roundToLong()
                    lamps += rail.centerAt(rail.distanceAt(offset / 1000.0))
                    val pitch = if (lamp == count - 1) to.midi else from.midi
                    val upper = if (pitch <= 115) pitch + 12 else pitch - 12
                    val time = from.atMs + offset
                    cues += Note("led-$time", time, if (lamp % 2 == 0) pitch else upper, 1, .20f, playable = false)
                    time
                }
                keys[from.panelIndex] = panel.copy(railSamples = rail.samples, ledCenters = lamps, ledTimesMs = times)
            }
            val vx = rail?.releaseVx ?: (to.x - from.x) / seconds
            val vy = if (rail != null) 0.0 else drop / seconds - .5 * CascadeGravity * seconds
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
            val air = min(t, .20)
            val rebound = .5 * CascadeGravity * air * (air - .20)
            return PlanarFrame(StagePoint(from.x, (y + rebound).toFloat(), 0f), cameraY, endSpin, index, null, -1)
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
