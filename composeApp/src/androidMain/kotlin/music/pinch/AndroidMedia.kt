package music.pinch

import android.content.Context
import android.os.SystemClock

internal class AndroidMedia(
    private val context: Context,
    durationMs: Long,
    private val instrument: Instrument,
    private val foreground: () -> Boolean,
    private val onClosed: (AndroidMedia) -> Unit,
) : GameMedia {
    private val clock = SessionClock(durationMs)
    private val focus = AndroidAudioFocus(context, ::interrupt)
    private var voices: NoteSamples? = null
    private var ready = false
    private var error: String? = null
    private var closed = false

    override var onInterrupted: (() -> Unit)? = null

    override fun prepare(pitches: Set<Int>) {
        if (closed || voices != null || error != null) return
        try {
            focus.observe()
            val samples = NoteSamples()
            voices = samples
            samples.load(context.assets, instrument, pitches, { ready = true }, ::fail)
            if (!foreground()) interrupt()
        } catch (problem: Exception) {
            fail("Не удалось подготовить звук: ${problem.localizedMessage ?: "ошибка аудиосистемы"}.")
        }
    }

    override fun snapshot() = clock.snapshot(SystemClock.uptimeMillis(), ready && !closed, error)

    override fun play() {
        if (closed || !ready || error != null) return
        if (foreground() && focus.acquire()) clock.play(SystemClock.uptimeMillis())
        else interrupt()
    }

    override fun pause() {
        clock.pause(SystemClock.uptimeMillis())
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
        if (ready && !closed && error == null && snapshot().playing) voices?.play(midi, gain)
    }

    override fun close() {
        if (closed) return
        closed = true
        onInterrupted = null
        pause()
        focus.close()
        voices?.release()
        voices = null
        onClosed(this)
    }
}
