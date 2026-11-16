package music.pinch

internal data class RepeatedTapDifferences(val tapsWithoutPanels: List<Long>, val panelsWithoutTaps: List<Long>)

/** Compare two performances of the same recording, within their actual overlap. */
internal fun repeatedTapDifferences(
    take: TapTake, takes: List<TapTake>, panels: List<Long>, selected: TapComparison, offsetMs: Long,
): RepeatedTapDifferences? {
    val other = takes.asSequence().filter {
        it !== take && it.version == take.version && it.chartVersionAtCapture == take.chartVersionAtCapture &&
            it.startMs < take.endMs && it.endMs > take.startMs
    }.maxByOrNull { minOf(it.endMs, take.endMs) - maxOf(it.startMs, take.startMs) } ?: return null
    val overlapStart = maxOf(other.startMs, take.startMs)
    val overlapEnd = minOf(other.endMs, take.endMs)
    val previous = compareTaps(other, panels, offsetMs)
    val unmatched = selected.tapsOnly.filter { it in overlapStart..overlapEnd }
    val slice = take.copy(startMs = overlapStart, endMs = overlapEnd, taps = unmatched)
    // Shorter than the spacing of successive sixteenths in both main songs.
    val again = compareTaps(slice, previous.tapsOnly, offsetMs = 0L, toleranceMs = 75L)
    return RepeatedTapDifferences(again.matched.map { it.tap }, selected.panelsOnly.intersect(previous.panelsOnly.toSet()).sorted())
}
