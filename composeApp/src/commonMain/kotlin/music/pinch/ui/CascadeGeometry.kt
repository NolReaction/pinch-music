package music.pinch.ui

import kotlin.math.sqrt

internal const val CascadeKeyStride = 5
internal const val CascadeFrameSize = 13

private val PlanarAttack.material: Int
    get() = when {
        playable -> 1
        isEcho -> 2
        else -> 0
    }

private val PlanarPath.panelOffset: Int get() = if (entryRail.isEmpty()) 0 else 1

/** Static world coordinates. All three backends (Android, iOS and offscreen capture) use this packing. */
internal fun PlanarPath.gpuCourse(): CascadeGpuCourse {
    val keys = FloatArray((panelOffset + panels.size) * CascadeKeyStride)
    val rails = FloatArray((entryRail.size + panels.sumOf { it.railSamples.size }) * 3)
    val lamps = FloatArray(panels.sumOf { it.ledCenters.size } * 3)
    var rail = 0
    var lamp = 0
    fun addRail(index: Int, samples: List<StagePoint>) {
        for (point in samples) {
            rails[rail++] = index.toFloat()
            rails[rail++] = point.x
            rails[rail++] = point.y
        }
    }
    if (entryRail.isNotEmpty()) {
        val start = entryRail.first()
        keys[0] = start.x
        keys[1] = start.y
        keys[3] = -1f // Launcher origin; this row has no playing face.
        addRail(0, entryRail)
    }
    panels.forEachIndexed { panelIndex, panel ->
        val index = panelIndex + panelOffset
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
        addRail(index, panel.railSamples)
        for (point in panel.ledCenters) {
            lamps[lamp++] = index.toFloat()
            lamps[lamp++] = point.x
            lamps[lamp++] = point.y
        }
    }
    return CascadeGpuCourse(keys, rails, lamps)
}

internal fun PlanarPath.writeGpuFrame(timeMs: Long, out: FloatArray, ledTapMs: Long? = null) {
    val pose = frame(timeMs)
    val active = attacks.getOrNull(pose.attackIndex)
    val next = attacks.getOrNull(pose.attackIndex + 1)
    out[0] = pose.marble.x
    out[1] = pose.marble.y
    out[2] = pose.marble.z
    out[3] = pose.cameraY
    out[4] = pose.rollAngle
    out[5] = (active?.let { it.panelIndex + panelOffset } ?: -1).toFloat()
    // A key flashes once, at physical contact. Input on a rail only lights its own lamp.
    out[6] = active?.let { (1f - (timeMs - it.atMs) / 235f).coerceIn(0f, 1f) } ?: 0f
    out[7] = (pose.rollingPanel?.plus(panelOffset) ?: -1).toFloat()
    out[8] = pose.litLedIndex.toFloat()
    out[9] = (next?.let { it.panelIndex + panelOffset } ?: -1).toFloat()
    out[10] = pose.cameraX
    val lamp = pose.rollingPanel?.let { panels[it].ledTimesMs.getOrNull(pose.litLedIndex) }
    out[11] = if (lamp != null && lamp == ledTapMs) (1f - (timeMs - lamp) / 180f).coerceIn(0f, 1f) else 0f
    out[12] = pose.cameraTravel
}
