package music.pinch

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Prepare the small launch sound in parallel with the first layout. Only the worker owns the native player. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AndroidLaunchSound(context: Context) {
    private val app = context.applicationContext
    private val worker = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var preparing: Job? = null
    private var playing: Job? = null
    private var player: MediaPlayer? = null

    fun warm() {
        if (preparing != null) return
        preparing = worker.launch {
            var source: MediaPlayer? = null
            try {
                val media = MediaPlayer()
                source = media
                media.setAudioAttributes(attributes)
                app.assets.openFd("launch.wav").use { media.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                media.prepare()
                currentCoroutineContext().ensureActive()
                player = media
                source = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The catalogue does not depend on the startup sound.
            } finally {
                source?.release()
            }
        }
    }

    fun play(gain: Float) {
        if (playing != null || gain <= 0f) return
        warm()
        val preparation = preparing
        playing = worker.launch {
            try {
                preparation?.join()
                currentCoroutineContext().ensureActive()
                player?.let { playIfQuiet(it, gain) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Platform audio services can be unavailable at startup.
            } finally {
                releasePlayer()
            }
        }
    }

    fun stop() {
        preparing?.cancel()
        playing?.cancel()
        worker.launch { releasePlayer() }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private suspend fun playIfQuiet(media: MediaPlayer, gain: Float) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.isMusicActive || audio.isStreamMute(AudioManager.STREAM_MUSIC) ||
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
            notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val job = currentCoroutineContext()[Job] ?: return
        val loss = AudioManager.OnAudioFocusChangeListener { if (it < 0) job.cancel() }
        // Use the API 24 focus contract for this short-lived effect. No versioned
        // framework class may appear in the coroutine's generated continuation fields.
        @Suppress("DEPRECATION")
        val result = audio.requestAudioFocus(loss, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        try {
            currentCoroutineContext().ensureActive()
            if (!LaunchPresentation.visible) return
            val finished = CompletableDeferred<Unit>()
            media.setOnCompletionListener { finished.complete(Unit) }
            media.setOnErrorListener { _, _, _ -> finished.complete(Unit); true }
            media.setVolume(gain, gain)
            media.start()
            finished.await()
        } finally {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(loss)
        }
    }
}
