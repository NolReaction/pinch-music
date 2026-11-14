package music.pinch

/** Settings are local. Imported PCM is transient; only its note chart is persisted. */
interface Platform {
    fun readValue(key: String): String?
    fun writeValue(key: String, value: String)
    suspend fun pickAudio(): FloatArray?
    var volume: Float
    var haptics: Boolean
    fun feedback()
    fun newMedia(durationMs: Long, instrument: Instrument): GameMedia
}

data class Playback(
    val ready: Boolean,
    val playing: Boolean,
    val ended: Boolean,
    val positionMs: Long,
    val observedUptimeMs: Long, // same uptime origin as Compose pointer down
    val error: String? = null
)

interface GameMedia {
    fun prepare(pitches: Set<Int>)
    fun snapshot(): Playback
    fun play()
    fun pause()
    fun note(midi: Int, gain: Float)
    fun close()
    var onInterrupted: (() -> Unit)?
}
