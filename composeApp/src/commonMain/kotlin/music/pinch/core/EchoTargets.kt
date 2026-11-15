package music.pinch.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Pick an automatic melody note about one beat after an accent, with at least eight beats between echoes. */
internal fun Chart.echoTargetTimes(): Set<Long> {
    val echoes = mutableSetOf<Long>()
    val beatMs = (60_000.0 / bpm).roundToLong().coerceAtLeast(1L)
    var previousAccent = notes.indexOfFirst { it.playable }
    var latestEcho: Long? = null
    for (nextAccent in previousAccent + 1 until notes.size) {
        if (!notes[nextAccent].playable) continue
        val start = notes[previousAccent].atMs
        val end = notes[nextAccent].atMs
        var best: Long? = null
        var closestToBeat = Long.MAX_VALUE
        for (index in previousAccent + 1 until nextAccent) {
            val onset = notes[index].atMs
            val interval = onset - start
            val distance = abs(interval - beatMs)
            if (interval >= ECHO_CLEARANCE_MS && end - onset >= ECHO_CLEARANCE_MS &&
                distance <= beatMs / 4 && distance < closestToBeat &&
                (latestEcho == null || onset - latestEcho >= beatMs * 8)) {
                best = onset
                closestToBeat = distance
            }
        }
        if (best != null) {
            echoes += best
            latestEcho = best
        }
        previousAccent = nextAccent
    }
    return echoes
}

// Leave room for both the accent and the optional tap, including their early/late windows.
private const val ECHO_CLEARANCE_MS = FORGIVING_WINDOW_MS + FORGIVING_AUTOMATIC_WINDOW_MS
