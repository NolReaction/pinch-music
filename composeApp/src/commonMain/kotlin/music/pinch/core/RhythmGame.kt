package music.pinch.core

import kotlin.math.abs

enum class Grade { Perfect, Good, Okay }

sealed interface TapResult {
    data class Hit(val note: Note, val grade: Grade, val errorMs: Long) : TapResult
    data object Stray : TapResult
    data object Ignored : TapResult
}

private const val WINDOW_MS = 120L
internal const val FORGIVING_WINDOW_MS = 195L
private const val DOUBLE_TAP_MS = 75L
private const val MAX_MISS_CUE_FRAME_MS = 125L

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
 * Input and decoder observations arrive on the same thread, but not always in timestamp order.
 * A missed window seen by a frame can still be corrected by an on-time queued touch.
 */
class RhythmGame(chart: Chart, private val forgiving: Boolean = false) {
    private val windowMs = if (forgiving) FORGIVING_WINDOW_MS else WINDOW_MS
    // Older/imported scores may contain a chord. The ball contacts one panel at its onset.
    private val notes = buildList<Note> {
        for (voice in chart.notes) {
            val previous = lastOrNull()
            if (previous == null || previous.atMs != voice.atMs) add(voice)
            else if (!previous.playable && voice.playable) set(lastIndex, voice)
        }
    }
    internal val firstTargetMs = notes.first().atMs
    private val hit = BooleanArray(notes.size)
    private enum class Phase { Playing, Paused, Finished }
    private var phase = Phase.Playing
    private var expiredEnd = 0
    private var reportedEnd = 0
    private var nextMissCue = 0
    private var previousCueTime = -1L
    private var lastTouchMs = Long.MIN_VALUE
    private var lastHitMs = Long.MIN_VALUE
    private var lastHitIndex = -1
    private var lastStrayMs = Long.MIN_VALUE
    private var hitStreak = 0
    var stats = GameStats(notes.size)
        private set

    fun advance(positionMs: Long) {
        if (phase != Phase.Playing || positionMs < 0) return
        var misses = 0
        while (expiredEnd < notes.size && positionMs - notes[expiredEnd].atMs > windowMs) {
            if (!hit[expiredEnd]) misses++
            expiredEnd++
        }
        if (misses > 0) {
            // In overlapping windows the previous panel can expire after the next one was hit.
            val combo = if (expiredEnd > lastHitIndex + 1) 0 else stats.combo
            stats = stats.copy(missed = stats.missed + misses, combo = combo)
        }
    }

    /** Drain every newly missed physical panel, including windows crossed by tap() between frames. */
    internal fun takeMisses(): List<Note> {
        if (reportedEnd == expiredEnd) return emptyList()
        val from = reportedEnd
        reportedEnd = expiredEnd
        return buildList {
            for (index in from until expiredEnd) if (!hit[index]) add(notes[index])
        }
    }

    fun tap(positionMs: Long): TapResult {
        if (phase != Phase.Playing || positionMs < firstTargetMs - windowMs || positionMs <= lastTouchMs) {
            return TapResult.Ignored
        }
        lastTouchMs = positionMs
        advance(positionMs)
        val index = nearestIndex(positionMs)
        val note = notes[index]
        val error = positionMs - note.atMs
        if (abs(error) > windowMs) {
            if (positionMs < firstTargetMs || positionMs > notes.last().atMs) return TapResult.Ignored
            val previous = maxOf(lastHitMs, lastStrayMs)
            if (previous != Long.MIN_VALUE && positionMs - DOUBLE_TAP_MS <= previous) return TapResult.Ignored
            lastStrayMs = positionMs
            hitStreak = 0
            stats = stats.copy(stray = stats.stray + 1, combo = 0)
            return TapResult.Stray
        }
        // Assign the down to the nearest physical panel even if it already scored. Otherwise
        // a second finger on that panel could silently spend the next overlapping window.
        if (hit[index]) return TapResult.Ignored
        if (lastHitIndex >= 0) {
            val spacing = note.atMs - notes[lastHitIndex].atMs
            val debounce = minOf(DOUBLE_TAP_MS, spacing / 2)
            // Two downs straddling the midpoint are one gesture unless the second is already
            // within a display frame of the next contact. Fast exact landings always count.
            if (positionMs - debounce < lastHitMs && -error > minOf(16L, spacing / 4)) return TapResult.Ignored
        }
        val grade = if (forgiving) when (abs(error)) {
            in 0..80 -> Grade.Perfect
            in 81..135 -> Grade.Good
            else -> Grade.Okay
        } else requireNotNull(gradeFor(error))
        hitStreak = if (index == lastHitIndex + 1) hitStreak + 1 else 1
        lastHitIndex = index
        lastHitMs = positionMs
        hit[index] = true
        stats = stats.copy(
            perfect = stats.perfect + if (grade == Grade.Perfect) 1 else 0,
            good = stats.good + if (grade == Grade.Good) 1 else 0,
            okay = stats.okay + if (grade == Grade.Okay) 1 else 0,
            missed = stats.missed - if (index < expiredEnd) 1 else 0,
            combo = if (expiredEnd > index + 1) 0 else hitStreak,
            maxCombo = maxOf(hitStreak, stats.maxCombo),
        )
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
        // Wait for one more observation before sounding a miss. A delayed frame often runs
        // just ahead of its queued touch; the first observation still updates the visible stats.
        val confirmedThrough = minOf(cueTime, previousCueTime)
        previousCueTime = cueTime
        // Judge freshness at the first observation, then allow the confirming frame its
        // own interval. At 10 fps these can each take 100 ms even during steady playback.
        val confirmationTimely = cueTime - confirmedThrough <= MAX_MISS_CUE_FRAME_MS
        var latest: Note? = null
        while (nextMissCue < notes.size && notes[nextMissCue].atMs < confirmedThrough) {
            val index = nextMissCue++
            val note = notes[index]
            if (!hit[index] && confirmationTimely && confirmedThrough - note.atMs <= MAX_MISS_CUE_FRAME_MS) {
                latest = note
            }
        }
        return latest
    }

    internal fun nearestTarget(positionMs: Long): Note = notes[nearestIndex(positionMs)]

    private fun nearestIndex(positionMs: Long): Int {
        val exact = notes.binarySearchBy(positionMs) { it.atMs }
        if (exact >= 0) return exact
        val after = -(exact + 1)
        if (after == 0) return 0
        if (after == notes.size) return notes.lastIndex
        return if (positionMs - notes[after - 1].atMs <= notes[after].atMs - positionMs) after - 1 else after
    }

    fun pause() {
        if (phase != Phase.Playing) return
        phase = Phase.Paused
        // Discard miss sounds already waiting for dispatch before the interruption.
        nextMissCue = maxOf(nextMissCue, expiredEnd)
        previousCueTime = -1L
    }

    fun resume() {
        if (phase == Phase.Paused) phase = Phase.Playing
    }

    fun finish(): GameStats {
        if (phase != Phase.Finished) {
            val remaining = notes.size - stats.perfect - stats.good - stats.okay - stats.missed
            val missedAfterLastHit = lastHitIndex != notes.lastIndex
            stats = stats.copy(missed = stats.missed + remaining, combo = if (missedAfterLastHit) 0 else stats.combo)
            expiredEnd = notes.size
            phase = Phase.Finished
        }
        return stats
    }

    fun restart() {
        hit.fill(false)
        expiredEnd = 0
        reportedEnd = 0
        nextMissCue = 0
        previousCueTime = -1
        lastTouchMs = Long.MIN_VALUE
        lastHitMs = Long.MIN_VALUE
        lastHitIndex = -1
        lastStrayMs = Long.MIN_VALUE
        hitStreak = 0
        stats = GameStats(notes.size)
        phase = Phase.Playing
    }
}
