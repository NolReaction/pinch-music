package music.pinch

/** Transport measured in the native touch event's monotonic time domain. */
internal class SessionClock(private val durationMs: Long) {
    private var baseMs = 0L
    private var startedAtMs: Long? = null
    val running: Boolean get() = startedAtMs != null

    fun play(nowMs: Long) {
        if (startedAtMs == null && baseMs < durationMs) startedAtMs = nowMs
    }

    fun pause(nowMs: Long) {
        baseMs = position(nowMs)
        startedAtMs = null
    }

    private fun position(nowMs: Long): Long {
        val elapsed = startedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return (baseMs + elapsed).coerceAtMost(durationMs)
    }

    fun snapshot(nowMs: Long, ready: Boolean, error: String?): Playback {
        val positionMs = position(nowMs)
        val ended = positionMs == durationMs
        return Playback(ready, startedAtMs != null && !ended, ended, positionMs, nowMs, error)
    }
}

/** Smooth the screen between duplicate decoder timestamps. Scores always use the native clock. */
internal class DecoderDisplayClock {
    private var lastSampleMs = -1L
    private var changedAt = 0L
    private var displayedMs = 0L

    fun position(mediaMs: Long, uptimeMs: Long, durationMs: Long): Long {
        if (mediaMs != lastSampleMs) {
            lastSampleMs = mediaMs
            changedAt = uptimeMs
        }
        // Audio may actually have stalled. Predict no more than a frame and a half at 60 Hz.
        val between = if (mediaMs == 0L) 0L else (uptimeMs - changedAt).coerceIn(0L, 24L)
        displayedMs = maxOf(displayedMs, mediaMs + between).coerceAtMost(durationMs)
        return displayedMs
    }

    fun reset() {
        lastSampleMs = -1L
        displayedMs = 0L
    }
}

// Sparse octave samples cover MIDI 0…127 with no more than seven semitones of resampling.
internal fun instrumentRoot(midi: Int): Int = (((midi + 6) / 12) * 12).coerceIn(0, 120)
