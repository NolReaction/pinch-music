package music.pinch

import music.pinch.core.Chart

/** A seekable arrangement on disk, or a packaged Android asset. */
data class ArrangementTrack(val path: String, val bundledAsset: Boolean = false)

/** Settings and imported note charts are stored locally. */
interface Platform {
    fun readValue(key: String): String?
    fun writeValue(key: String, value: String)
    suspend fun pickAudio(): FloatArray?
    suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack
    var volume: Float
    var haptics: Boolean
    fun playLaunchSound(volume: Float) {}
    fun stopLaunchSound() {}
    fun feedback()
    fun newMedia(durationMs: Long, instrument: Instrument): GameMedia
}

data class Playback(
    val ready: Boolean,
    val playing: Boolean,
    val ended: Boolean,
    val positionMs: Long,
    val observedUptimeMs: Long, // same uptime origin as Compose pointer down
    val error: String? = null,
    // The last position reported by the decoder. Expiring notes uses this watermark.
    val confirmedPositionMs: Long? = null,
    val stalled: Boolean = false,
)

interface GameMedia {
    /** A backing asset or an arrangement starts at the same time as the chart. */
    fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack? = null)
    /** Rebuild interrupted hardware when the user asks to resume. */
    fun prepareResume() {}
    fun setBackingVolume(volume: Float)
    fun uptimeMs(): Long
    fun snapshot(): Playback
    fun play()
    fun pause()
    fun note(midi: Int, gain: Float, durationMs: Int)
    /** A short, unpitched attack layered on a fully recorded melody. */
    fun strike(gain: Float)
    fun close()
    var onInterrupted: (() -> Unit)?
}
