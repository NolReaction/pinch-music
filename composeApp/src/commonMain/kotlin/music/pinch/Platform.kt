package music.pinch

import music.pinch.core.Chart

/** A seekable arrangement on disk, or a packaged Android asset. */
data class ArrangementTrack(val path: String, val bundledAsset: Boolean = false)

/** Settings and imported note charts are stored locally. */
interface Platform {
    fun readValue(key: String): String?
    fun writeValue(key: String, value: String)
    fun removeValue(key: String)
    suspend fun pickAudio(): PickedAudio?
    suspend fun pickReferenceRecording(id: String): ChosenRecording? = null
    fun referenceFile(id: String, suffix: String): ArrangementTrack? = null
    fun removeReferenceRecording(id: String) {}
    suspend fun saveImportAudio(id: String, audio: PickedAudio): Boolean = false
    fun removeImportAudio(id: String) {}
    fun importAudioTrack(id: String): ArrangementTrack? = null
    suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack
    var volume: Float
    var haptics: Boolean
    fun firstMenuFrame() {}
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
    /** Set the absolute starting point before the first prepare. Snapshots keep the same file timeline. */
    fun startAt(positionMs: Long)
    /** A backing asset or an arrangement starts at the same time as the chart. */
    fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack? = null, includeStrike: Boolean = false)
    /** Rebuild interrupted hardware when the user asks to resume. */
    fun prepareResume() {}
    fun setBackingVolume(volume: Float)
    fun uptimeMs(): Long
    fun snapshot(): Playback
    /** Platforms that support sample-clock scheduling can arm the recording during the run-up. */
    fun scheduleStart(atUptimeMs: Long) {}
    fun play()
    fun pause()
    fun note(midi: Int, gain: Float, durationMs: Int)
    /** A short, unpitched attack layered on a fully recorded melody. */
    fun strike(gain: Float)
    fun close()
    var onInterrupted: (() -> Unit)?
}
