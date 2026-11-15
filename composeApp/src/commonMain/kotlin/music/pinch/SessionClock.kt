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

/** Share one decoder query across several display frames. All times use touch-event uptime. */
internal class BackingClock(private val durationMs: Long) {
    private var sample: Playback? = null
    private var advancing = false

    fun snapshot(nowMs: Long, read: () -> Playback): Playback {
        val last = sample
        if (last == null || nowMs - last.observedUptimeMs >= 64) {
            val fresh = read()
            // Observe real progress before coasting. Identical samples freeze the estimate through
            // startup or a decoder stall. A later callback, pause or resume invalidates the anchor.
            advancing = fresh.playing && fresh.positionMs > (last?.positionMs ?: 0L)
            sample = fresh
            return fresh.copy(stalled = fresh.playing && !advancing)
        }
        val elapsed = if (advancing) (nowMs - last.observedUptimeMs).coerceAtLeast(0L) else 0L
        return last.copy(positionMs = (last.positionMs + elapsed).coerceAtMost(durationMs),
            observedUptimeMs = nowMs, confirmedPositionMs = last.positionMs, stalled = last.playing && !advancing)
    }

    fun reset() {
        sample = null
        advancing = false
    }
}

// Sparse octave samples cover MIDI 0…127 with no more than seven semitones of resampling.
internal fun instrumentRoot(midi: Int): Int = (((midi + 6) / 12) * 12).coerceIn(0, 120)
