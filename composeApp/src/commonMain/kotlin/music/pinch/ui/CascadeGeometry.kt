package music.pinch.ui

import kotlin.math.max
import kotlin.math.sqrt

internal const val CascadeKeyStride = 5

private val PlanarAttack.material: Int
    get() = when {
        playable -> 1
        isEcho -> 2
        else -> 0
    }

/** Static world coordinates. All three backends (Android, iOS and offscreen capture) use this packing. */
internal fun PlanarPath.gpuCourse(): CascadeGpuCourse {
    val keys = FloatArray(panels.size * CascadeKeyStride)
    val rails = FloatArray(panels.sumOf { it.railSamples.size } * 3)
    val lamps = FloatArray(panels.sumOf { it.ledCenters.size } * 3)
    var rail = 0
    var lamp = 0
    panels.forEachIndexed { index, panel ->
        val first = attacks[panel.firstAttack]
        val base = index * CascadeKeyStride
        // The path stores the centre of the sphere. Its contact lies one radius
        // away along the downward normal of the playing surface.
        val normalY = 1f / sqrt(1f + panel.slope * panel.slope)
        keys[base] = first.x - MarbleRadius * panel.slope * normalY
        keys[base + 1] = panel.topY + MarbleRadius * (normalY - 1f)
        keys[base + 2] = if (panel.left) -1f else 1f
        keys[base + 3] = first.material.toFloat()
        keys[base + 4] = panel.slope
        for (point in panel.railSamples) {
            rails[rail++] = index.toFloat()
            rails[rail++] = point.x
            rails[rail++] = point.y
        }
        for (point in panel.ledCenters) {
            lamps[lamp++] = index.toFloat()
            lamps[lamp++] = point.x
            lamps[lamp++] = point.y
        }
    }
    return CascadeGpuCourse(keys, rails, lamps)
}

internal fun PlanarPath.writeGpuFrame(
    timeMs: Long, out: FloatArray, hitMs: Long? = null, echoMs: Long? = null, ledTapMs: Long? = null,
) {
    val pose = frame(timeMs)
    val active = attacks.getOrNull(pose.attackIndex)
    val next = attacks.getOrNull(pose.attackIndex + 1)
    out[0] = pose.marble.x
    out[1] = pose.marble.y
    out[2] = pose.marble.z
    out[3] = pose.cameraY
    out[4] = pose.rollAngle
    out[5] = (active?.panelIndex ?: -1).toFloat()
    // The backing produces a visible strike in preview. A player's hit sustains the key's light.
    var pulse = 0f
    if (active != null) {
        val held = active.atMs == hitMs || active.atMs == echoMs
        val decayMs = if (held) 420f else 235f
        pulse = (1f - (timeMs - active.atMs) / decayMs).coerceIn(0f, 1f)
        if (!held) pulse *= .86f
        if (pose.rollingPanel != null && ledTapMs != null && timeMs - ledTapMs in 0..180L) {
            pulse = max(pulse, 1f - (timeMs - ledTapMs) / 180f)
        }
    }
    out[6] = pulse
    out[7] = (pose.rollingPanel ?: -1).toFloat()
    out[8] = pose.litLedIndex.toFloat()
    out[9] = (next?.panelIndex ?: -1).toFloat()
    out[10] = (next?.material ?: 0).toFloat()
    out[11] = (active?.material ?: 0).toFloat()
}
