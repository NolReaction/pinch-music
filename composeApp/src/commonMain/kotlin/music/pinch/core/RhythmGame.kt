package music.pinch.core

import kotlin.math.abs

enum class Grade { Perfect, Good, Okay }

sealed interface TapResult {
    data class Hit(val note: Note, val grade: Grade, val errorMs: Long) : TapResult
    data class Automatic(val note: Note) : TapResult
    data object Stray : TapResult
    data object Ignored : TapResult
}

private const val WINDOW_MS = 120L
internal const val FORGIVING_WINDOW_MS = 165L
private const val AUTOMATIC_WINDOW_MS = 55L

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
    private val denominator get() = (noteCount.toLong() + stray).coerceAtLeast(1)
    val accuracy: Double get() = qualityTenths / (10.0 * denominator)
    val score: Int get() = ((10_000L * qualityTenths + denominator / 2) / denominator).toInt()
}

/**
 * Call from one serial input/playback context. A delivered touch carries its original media time.
 * A video frame can arrive before a slightly older touch: keep frame-only misses provisional
 * until the next touch, so crossing a window during dispatch does not silence a valid note.
 */
class RhythmGame(chart: Chart, private val forgiving: Boolean = false) {
    private val windowMs = if (forgiving) FORGIVING_WINDOW_MS else WINDOW_MS
    private val notes = chart.notes.filter { it.playable }
    private val automatic = chart.notes.filterNot { it.playable }.distinctBy { it.atMs }
    internal val firstTargetMs = notes.firstOrNull()?.atMs
    private val hit = BooleanArray(notes.size)
    private enum class Phase { Playing, Paused, Finished }

    private var phase = Phase.Playing
    private var committed = GameStats(notes.size)
    private var nextMissCue = 0
    private var firstUnexpired = 0
    private var frameEnd = 0
    private var provisionalMisses = 0
    private var lastTouchMs = -1L
    private var lastAutomaticMs = -1L
    private var latestMediaMs = 0L
    var stats: GameStats = committed
        private set

    fun advance(positionMs: Long): Int {
        if (phase != Phase.Playing || positionMs <= latestMediaMs) return 0
        latestMediaMs = positionMs
        val end = expiryEnd(positionMs, frameEnd)
        val misses = countUnhit(frameEnd, end)
        frameEnd = end
        if (misses > 0) {
            provisionalMisses += misses
            refreshFrameStats()
        }
        return misses
    }

    fun tap(positionMs: Long): TapResult {
        if (phase != Phase.Playing || notes.isEmpty() || positionMs < 0 || positionMs < lastTouchMs) return TapResult.Ignored
        val passive = if (automatic.isEmpty()) null else nearest(automatic, positionMs)
        val passiveDistance = passive?.let { abs(positionMs - it.atMs) } ?: Long.MAX_VALUE
        if (passiveDistance > AUTOMATIC_WINDOW_MS &&
            (positionMs - notes.first().atMs < -windowMs || positionMs - notes.last().atMs > windowMs)) {
            advance(positionMs)
            return TapResult.Ignored
        }
        lastTouchMs = positionMs
        advance(positionMs)
        val end = expiryEnd(positionMs, firstUnexpired)
        val expired = countUnhit(firstUnexpired, end)
        provisionalMisses -= expired
        committed = committed.withMisses(expired)
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
        // A voluntary tap on an automatic bar must not consume the wider window of a nearby
        // gold accent. On a tie, prefer the still-unplayed accent. One tick per contact.
        if (passive != null && passiveDistance <= AUTOMATIC_WINDOW_MS && passiveDistance < nearestDistance) {
            refreshFrameStats()
            if (passive.atMs == lastAutomaticMs) return TapResult.Ignored
            lastAutomaticMs = passive.atMs
            return TapResult.Automatic(passive)
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
        if (nearestIndex < frameEnd) provisionalMisses--
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

    /**
     * Emit each expired target at most once. A short dispatch grace lets on-time down events
     * reach the main thread; after a stall, only the most recent timely cue is audible.
     * Scoring still accounts for every intervening note.
     */
    internal fun takeMissCue(positionMs: Long): Note? {
        if (phase != Phase.Playing) return null
        val cueTime = positionMs - windowMs - 48
        var latest: Note? = null
        while (nextMissCue < notes.size && notes[nextMissCue].atMs < cueTime) {
            val index = nextMissCue++
            val note = notes[index]
            if (!hit[index] && cueTime - note.atMs <= 80) latest = note
        }
        return latest
    }

    internal fun nearestTarget(positionMs: Long) = nearest(notes, positionMs)

    private fun nearest(candidates: List<Note>, positionMs: Long): Note {
        val index = candidates.binarySearchBy(positionMs) { it.atMs }
        if (index >= 0) return candidates[index]
        val after = -(index + 1)
        if (after == 0) return candidates.first()
        if (after == candidates.size) return candidates.last()
        val before = candidates[after - 1]
        return if (positionMs - before.atMs <= candidates[after].atMs - positionMs) before else candidates[after]
    }

    private fun expiryEnd(positionMs: Long, from: Int): Int {
        var end = from
        while (end < notes.size && positionMs - notes[end].atMs > windowMs) end++
        return end
    }

    private fun countUnhit(from: Int, end: Int): Int {
        var missed = 0
        for (index in from until end) if (!hit[index]) missed++
        return missed
    }

    private fun GameStats.withMisses(count: Int) =
        if (count == 0) this else copy(missed = missed + count, combo = 0)

    private fun refreshFrameStats() {
        stats = committed.withMisses(provisionalMisses)
    }

    fun pause() {
        if (phase == Phase.Playing) phase = Phase.Paused
    }

    fun resume() {
        if (phase == Phase.Paused) phase = Phase.Playing
    }

    fun finish(): GameStats {
        if (phase != Phase.Finished) {
            stats = committed.withMisses(countUnhit(firstUnexpired, notes.size))
            phase = Phase.Finished
        }
        return stats
    }

    fun restart() {
        hit.fill(false)
        nextMissCue = 0
        firstUnexpired = 0
        frameEnd = 0
        provisionalMisses = 0
        lastTouchMs = -1
        lastAutomaticMs = -1
        latestMediaMs = 0
        committed = GameStats(notes.size)
        stats = committed
        phase = Phase.Playing
    }
}
