package music.pinch

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock

internal class AndroidMedia(
    private val context: Context,
    private val durationMs: Long,
    private val instrument: Instrument,
    private val foreground: () -> Boolean,
    private val onClosed: (AndroidMedia) -> Unit,
) : GameMedia {
    private val clock = SessionClock(durationMs)
    private val focus = AndroidAudioFocus(context, ::interrupt)
    private var voices: NoteSamples? = null
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

    override fun prepare(pitches: Set<Int>, backingAsset: String?) {
        if (closed || voices != null || backing != null || samplesReady || error != null) return
        try {
            focus.observe()
            if (backingAsset != null) prepareBacking(backingAsset)
            if (pitches.isEmpty()) samplesReady = true
            else {
                val samples = NoteSamples()
                voices = samples
                samples.load(context.assets, instrument, pitches, backingAsset != null, { samplesReady = true }, ::fail)
            }
            if (!foreground()) interrupt()
        } catch (problem: Exception) {
            fail("Не удалось подготовить звук: ${problem.localizedMessage ?: "ошибка аудиосистемы"}.")
        }
    }

    private fun prepareBacking(asset: String) {
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
        context.assets.openFd(asset).use { fd ->
            player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        }
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
        // Android's decoder position remains on the same timeline through focus loss and resume.
        val position = when {
            backingEnded -> durationMs
            backingReady && !closed -> player.currentPosition.toLong().coerceIn(0, durationMs)
            else -> 0L
        }
        return Playback(ready && !closed, backingStarted && !backingEnded && !closed, backingEnded, position, now, error)
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
                backingStarted = true
            } catch (problem: IllegalStateException) {
                fail("Не удалось начать аккомпанемент.")
            }
        }
    }

    override fun pause() {
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

    override fun note(midi: Int, gain: Float) {
        if ((backingStarted || clock.running) && ready && !closed && error == null) voices?.play(midi, gain)
    }

    override fun strike(gain: Float) {
        if (backingStarted && !closed && error == null) voices?.strike(gain)
    }

    override fun close() {
        if (closed) return
        closed = true
        onInterrupted = null
        pause()
        focus.close()
        backing?.release()
        backing = null
        voices?.release()
        voices = null
        onClosed(this)
    }
}
