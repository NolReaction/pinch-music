package music.pinch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Calls/observations and callbacks belong to the UI thread. */
interface Platform {
    fun chartJson(): String
    var volume: Float
    var haptics: Boolean
    var bestScore: Int?  // null until a completed game, including a score of zero
    fun feedback()
    fun newMedia(preview: Boolean): GameMedia
}

data class Playback(
    val ready: Boolean,        // decoded first video frame and any required samples
    val playing: Boolean,      // actual video progression
    val ended: Boolean,
    val positionMs: Long,
    val observedUptimeMs: Long, // same uptime origin as Compose pointer-down uptimeMillis
    val error: String? = null
)

interface GameMedia {
    // Called with the video host mounted or about to be mounted. Can begin async loading.
    fun prepare(pitches: Set<Int>)
    @Composable fun Video(modifier: Modifier)
    fun snapshot(): Playback
    fun play()
    fun pause()  // also silence voices
    fun note(midi: Int, gain: Float)  // gain includes user volume and chart velocity
    fun setPreviewVolume(volume: Float)
    fun close()
    var onInterrupted: (() -> Unit)?  // native focus, route, app background; never auto-resume
}
