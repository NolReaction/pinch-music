package music.pinch

import android.content.Context
import java.io.FileDescriptor
import java.io.FileInputStream
import android.media.MediaPlayer
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidMedia(
    private val context: Context,
    private val durationMs: Long,
    private val instrument: Instrument,
    private val samples: NoteSampleCache,
    private val foreground: () -> Boolean,
    private val onClosed: (AndroidMedia) -> Unit,
) : GameMedia {
    private val clock = SessionClock(durationMs)
    private val backingClock = BackingClock(durationMs)
    private val focus = AndroidAudioFocus(context, ::interrupt)
    private var voices: NoteSamples? = null
    private var preparation: Job? = null
    private var samplesReady = false
    private var backing: MediaPlayer? = null
    private var backingReady = false
    private var backingStarted = false
    private var backingEnded = false
    private var backingVolume = 1f
    private var error: String? = null
    private var closed = false

    private val ready get() = samplesReady && (backing == null || backingReady)

    override var onInterrupted: (() -> Unit)? = null

    override fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack?, includeStrike: Boolean) {
        if (closed || preparation != null || error != null) return
        focus.observe()
        preparation = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val asset = backingAsset ?: arrangement?.takeIf { it.bundledAsset }?.path
                if (asset != null) {
                    withContext(Dispatchers.IO) {
                        context.assets.openFd(asset).use { fd ->
                            // MediaPlayer and its listeners stay on their owning looper.
                            withContext(Dispatchers.Main.immediate) { prepareBacking(fd.fileDescriptor, fd.startOffset, fd.length) }
                        }
                    }
                } else if (arrangement != null) {
                    withContext(Dispatchers.IO) {
                        FileInputStream(arrangement.path).use { input ->
                            val length = input.channel.size()
                            withContext(Dispatchers.Main.immediate) { prepareBacking(input.fd, 0, length) }
                        }
                    }
                }
                if (pitches.isNotEmpty() || includeStrike) voices = samples.get(instrument, pitches, includeStrike)
                samplesReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Не удалось подготовить звук: ${e.localizedMessage ?: "ошибка аудиосистемы"}.")
            }
        }
        if (!foreground()) interrupt()
    }

    private fun prepareBacking(fd: FileDescriptor, offset: Long, length: Long) {
        val player = MediaPlayer()
        backing = player
        player.setAudioAttributes(gameAudioAttributes())
        player.setOnErrorListener { _, _, _ ->
            backingReady = false
            backingStarted = false
            fail("Не удалось воспроизвести аккомпанемент.")
            true
        }
        player.setOnPreparedListener {
            if (!closed && error == null) {
                it.setVolume(backingVolume, backingVolume)
                backingReady = true
            }
        }
        player.setOnCompletionListener {
            backingStarted = false
            backingEnded = true
        }
        player.setDataSource(fd, offset, length)
        player.prepareAsync()
    }

    override fun setBackingVolume(volume: Float) {
        backingVolume = volume.coerceIn(0f, 1f)
        if (backingReady && !closed) backing?.setVolume(backingVolume, backingVolume)
    }

    override fun uptimeMs() = SystemClock.uptimeMillis()

    override fun snapshot(): Playback {
        val now = SystemClock.uptimeMillis()
        val player = backing ?: return clock.snapshot(now, ready && !closed, error)
        if (backingEnded || !ready || closed || error != null) {
            return Playback(ready && !closed, false, backingEnded, if (backingEnded) durationMs else 0L, now, error)
        }
        return backingClock.snapshot(now) {
            val position = player.currentPosition.toLong().coerceIn(0L, durationMs)
            Playback(true, backingStarted, false, position, now + (uptimeMs() - now) / 2)
        }
    }

    override fun play() {
        if (closed || !ready || error != null) return
        if (!foreground() || !focus.acquire()) {
            interrupt()
            return
        }
        val player = backing
        if (player == null) {
            clock.play(SystemClock.uptimeMillis())
        } else if (!backingStarted && !backingEnded) {
            try {
                player.start()
                backingClock.reset()
                backingStarted = true
            } catch (problem: IllegalStateException) {
                fail("Не удалось начать аккомпанемент.")
            }
        }
    }

    override fun pause() {
        backingClock.reset()
        clock.pause(SystemClock.uptimeMillis())
        if (backingStarted && backingReady) {
            backingStarted = false
            backing?.pause()
        }
        voices?.silence()
        focus.abandon()
    }

    fun interrupt() {
        if (closed) return
        pause()
        onInterrupted?.invoke()
    }

    private fun fail(message: String) {
        if (closed || error != null) return
        error = message
        pause()
    }

    override fun note(midi: Int, gain: Float, durationMs: Int) {
        if ((backingStarted || clock.running) && ready && !closed && error == null) voices?.play(midi, gain, durationMs)
    }

    override fun strike(gain: Float) {
        if (backingStarted && !closed && error == null) voices?.strike(gain)
    }

    override fun close() {
        if (closed) return
        closed = true
        onInterrupted = null
        preparation?.cancel()
        pause()
        focus.close()
        backing?.release()
        backing = null
        voices = null
        onClosed(this)
    }
}
