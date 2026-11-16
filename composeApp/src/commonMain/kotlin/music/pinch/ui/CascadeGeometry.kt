package music.pinch.ui

import kotlin.math.sqrt
import music.pinch.PanelFeedback

internal const val CascadeKeyStride = 5
internal const val CascadeFrameSize = 31

private val PlanarAttack.material: Int
    get() = if (playable) 1 else 0

private val PlanarPath.panelOffset: Int get() = if (entryRail.isEmpty()) 0 else 1

/** Static world coordinates. All three backends (Android, iOS and offscreen capture) use this packing. */
internal fun PlanarPath.gpuCourse(): CascadeGpuCourse {
    val keys = FloatArray((panelOffset + panels.size) * CascadeKeyStride)
    val rails = FloatArray((entryRail.size + panels.sumOf { it.railSamples.size }) * 3)
    var rail = 0
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
    }
    return CascadeGpuCourse(keys, rails)
}

internal fun PlanarPath.writeGpuFrame(timeMs: Long, out: FloatArray,
    feedback: List<PanelFeedback> = emptyList(), theme: StageTheme = StageTheme.Stereo, showWindow: Boolean = false) {
    val pose = frame(timeMs)
    val active = attacks.getOrNull(pose.attackIndex)
    val next = attacks.getOrNull(pose.attackIndex + 1)
    out[0] = pose.marble.x
    out[1] = pose.marble.y
    out[2] = pose.marble.z
    out[3] = pose.cameraY
    out[4] = pose.rollAngle
    out[5] = (active?.let { it.panelIndex + panelOffset } ?: -1).toFloat()
    // The cap starts moving at physical contact. A delayed score update only changes its colour.
    out[6] = active?.let { (1f - (timeMs - it.atMs) / 235f).coerceIn(0f, 1f) } ?: 0f
    val age = timeMs - (active?.atMs ?: timeMs)
    val travel = if (age in 0L..190L) {
        val u = if (age < 40) age / 40f else (190L - age) / 150f
        .081f * u * u * (3f - 2f * u)
    } else 0f
    out[7] = travel
    out[8] = if (active != null && next == null) travel else 0f // ball stays seated in the last catcher
    out[9] = (next?.let { it.panelIndex + panelOffset } ?: -1).toFloat()
    out[10] = pose.cameraX
    out[11] = theme.ordinal.toFloat()
    out[12] = pose.cameraTravel
    // All 24-bit sRGB values are represented exactly in a 32-bit float.
    out[13] = theme.wall.toFloat()
    out[14] = theme.light.toFloat()
    out[15] = theme.panel.toFloat()
    out[16] = theme.accent.toFloat()
    val cue = if (showWindow) landingCueIndex(timeMs, pose.attackIndex, attacks.size, feedback) {
        attacks[it].atMs
    } else -1
    out[25] = if (cue >= 0) (attacks[cue].panelIndex + panelOffset).toFloat() else -1f
    out[26] = if (cue >= 0) landingPhase(timeMs, attacks[cue].atMs) else 0f
    for (slot in 0 until 4) {
        val base = 17 + 2 * slot
        val result = feedback.getOrNull(feedback.lastIndex - slot)
        val elapsed = if (result != null && result.effectsVisible) timeMs - result.shownAtMs else -1
        val opacity = feedbackStrength(elapsed)
        val index = if (result != null && opacity > 0f)
            attacks.binarySearchBy(result.noteAtMs) { it.atMs } else -1
        out[base] = if (index >= 0) (attacks[index].panelIndex + panelOffset).toFloat() else -1f
        out[27 + slot] = if (showWindow && index >= 0 && result != null) sparkPhase(timeMs, result) else -1f
        out[base + 1] = if (index < 0 || result == null) 0f else if (result.hit) opacity else -opacity
    }
}
