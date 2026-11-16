package music.pinch.ui

import kotlin.math.abs
import music.pinch.PanelFeedback
import music.pinch.core.FORGIVING_WINDOW_MS

/** Use the judge's nearest-onset rule, including its earlier-key tie break for overlapping windows. */
internal inline fun landingCueIndex(
    timeMs: Long, previous: Int, count: Int, feedback: List<PanelFeedback>, onset: (Int) -> Long,
): Int {
    if (count == 0) return -1
    val index = when {
        previous < 0 -> 0
        previous >= count - 1 -> count - 1
        timeMs - onset(previous) <= onset(previous + 1) - timeMs -> previous
        else -> previous + 1
    }
    val at = onset(index)
    return if (abs(timeMs - at) <= FORGIVING_WINDOW_MS && feedback.none { it.noteAtMs == at }) index else -1
}

internal fun landingPhase(timeMs: Long, atMs: Long): Float = (timeMs - atMs) / FORGIVING_WINDOW_MS.toFloat()

internal fun sparkPhase(timeMs: Long, feedback: PanelFeedback): Float {
    val age = timeMs - feedback.shownAtMs
    return if (feedback.hit && feedback.effectsVisible && age in 0L until 360L) age / 360f else -1f
}
