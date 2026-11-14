package music.pinch.core

import kotlin.math.abs

enum class Grade { Perfect, Good, Okay }

sealed interface TapResult {
    data class Hit(val note: Note, val grade: Grade, val errorMs: Long) : TapResult
    data object Stray : TapResult
    data object Ignored : TapResult
}

private const val WINDOW_MS = 120L

internal fun gradeFor(errorMs: Long): Grade? = when (errorMs) {
    in -45L..45L -> Grade.Perfect
    in -90L..90L -> Grade.Good
    in -WINDOW_MS..WINDOW_MS -> Grade.Okay
    else -> null
}

data class GameStats(
    val noteCount: Int,
    val perfect: Int = 0,
    val good: Int = 0,
    val okay: Int = 0,
    val missed: Int = 0,
    val stray: Int = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
) {
    private val qualityTenths get() = 10L * perfect + 7L * good + 4L * okay
    private val denominator get() = noteCount.toLong() + stray
    val accuracy: Double get() = qualityTenths / (10.0 * denominator)
    val score: Int get() = ((10_000L * qualityTenths + denominator / 2) / denominator).toInt()
}

/**
 * Call from one serial input/playback context. A delivered touch carries its original media time.
 * A video frame can arrive before a slightly older touch: keep frame-only misses provisional
 * until the next touch, so crossing a window during dispatch does not silence a valid note.
 */
class RhythmGame(chart: Chart, private val forgiving: Boolean = false) {
    private val windowMs = if (forgiving) 165L else WINDOW_MS
    private val notes = chart.notes.toList()
    private val hit = BooleanArray(notes.size)
    private enum class Phase { Playing, Paused, Finished }

    private var phase = Phase.Playing
    private var committed = GameStats(notes.size)
    private var firstUnexpired = 0
    private var lastTouchMs = -1L
    private var latestMediaMs = 0L
    var stats: GameStats = committed
        private set

    fun advance(positionMs: Long): Int {
        if (phase != Phase.Playing || positionMs <= latestMediaMs) return 0
        val oldMissed = stats.missed
        latestMediaMs = positionMs
        refreshFrameStats()
        return stats.missed - oldMissed
    }

    fun tap(positionMs: Long): TapResult {
        if (phase != Phase.Playing || positionMs < 0 || positionMs < lastTouchMs) return TapResult.Ignored
        if (positionMs - notes.first().atMs < -windowMs || positionMs - notes.last().atMs > windowMs) {
            advance(positionMs)
            return TapResult.Ignored
        }
        lastTouchMs = positionMs
        latestMediaMs = maxOf(positionMs, latestMediaMs)
        val end = expiryEnd(positionMs)
        committed = withMissesThrough(end)
        firstUnexpired = end

        var nearestIndex = -1
        var nearestDistance = Long.MAX_VALUE
        for (index in firstUnexpired until notes.size) {
            val note = notes[index]
            val error = positionMs - note.atMs
            if (error < -windowMs) break
            if (hit[index]) continue
            val distance = abs(error)
            if (distance < nearestDistance ||
                (distance == nearestDistance && note.atMs == notes[nearestIndex].atMs && note.id < notes[nearestIndex].id)
            ) {
                nearestIndex = index
                nearestDistance = distance
            }
        }
        if (nearestIndex < 0) {
            committed = committed.copy(stray = committed.stray + 1, combo = 0)
            refreshFrameStats()
            return TapResult.Stray
        }
        val note = notes[nearestIndex]
        val error = positionMs - note.atMs
        val grade = if (forgiving) when (abs(error)) {
            in 0..65 -> Grade.Perfect
            in 66..110 -> Grade.Good
            else -> Grade.Okay
        } else requireNotNull(gradeFor(error))
        hit[nearestIndex] = true
        val combo = committed.combo + 1
        committed = committed.copy(
            perfect = committed.perfect + if (grade == Grade.Perfect) 1 else 0,
            good = committed.good + if (grade == Grade.Good) 1 else 0,
            okay = committed.okay + if (grade == Grade.Okay) 1 else 0,
            combo = combo,
            maxCombo = maxOf(combo, committed.maxCombo),
        )
        refreshFrameStats()
        return TapResult.Hit(note, grade, error)
    }

    private fun expiryEnd(positionMs: Long): Int {
        var end = firstUnexpired
        while (end < notes.size && positionMs - notes[end].atMs > windowMs) end++
        return end
    }

    private fun withMissesThrough(end: Int): GameStats {
        var missed = 0
        for (index in firstUnexpired until end) if (!hit[index]) missed++
        return if (missed == 0) committed else committed.copy(missed = committed.missed + missed, combo = 0)
    }

    private fun refreshFrameStats() {
        stats = withMissesThrough(expiryEnd(latestMediaMs))
    }

    fun pause() {
        if (phase == Phase.Playing) phase = Phase.Paused
    }

    fun resume() {
        if (phase == Phase.Paused) phase = Phase.Playing
    }

    fun finish(): GameStats {
        if (phase != Phase.Finished) {
            stats = withMissesThrough(notes.size)
            phase = Phase.Finished
        }
        return stats
    }

    fun restart() {
        hit.fill(false)
        firstUnexpired = 0
        lastTouchMs = -1
        latestMediaMs = 0
        committed = GameStats(notes.size)
        stats = committed
        phase = Phase.Playing
    }
}
