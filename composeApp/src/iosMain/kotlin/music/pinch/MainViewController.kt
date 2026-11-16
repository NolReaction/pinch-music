@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import androidx.compose.runtime.CompositionLocalProvider
import music.pinch.ui.AppleCascadeSurface
import music.pinch.ui.LocalCascadeFactory
import androidx.compose.ui.window.ComposeUIViewController
import music.pinch.core.Chart
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.UIKit.UISelectionFeedbackGenerator
import platform.UIKit.UIViewController

interface AppleMediaFactoryProtocol {
    fun createCascade(): AppleCascadeSurface
    fun createMedia(instrument: String, observer: AppleInterruptListener): AppleMediaSession
    fun pickAudio(listener: AppleAudioImportListener)
}

interface AppleInterruptListener {
    fun interrupted()
}

interface AppleMediaSession {
    fun includeSample(midi: Int, root: Int)
    fun prepare(backingAsset: String?, arrangementPath: String?)
    fun setBackingVolume(gain: Float)
    val ready: Boolean
    val backingPositionMs: Long
    val backingPlaying: Boolean
    val backingEnded: Boolean
    val failure: String?
    fun activate(): Boolean
    fun silence()
    fun note(midi: Int, gain: Float, durationMs: Int)
    fun strike(gain: Float)
    fun close()
}

fun MainViewController(factory: AppleMediaFactoryProtocol): UIViewController {
    val platform = IosPlatform(factory)
    val createStage = factory::createCascade
    return ComposeUIViewController {
        CompositionLocalProvider(LocalCascadeFactory provides createStage) { App(platform) }
    }
}

private class IosPlatform(private val factory: AppleMediaFactoryProtocol) : Platform {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val selectionFeedback = UISelectionFeedbackGenerator()

    override suspend fun pickAudio() = pickLocalSong(factory::pickAudio)
    override suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?) =
        appleArrangementTrack(chart, bundledId, previewInstrument)
    override fun readValue(key: String): String? = defaults.stringForKey(key)
    override fun writeValue(key: String, value: String) = defaults.setObject(value, forKey = key)

    override var volume: Float
        get() = if (defaults.objectForKey("volume") == null) 0.8f else defaults.floatForKey("volume")
        set(value) = defaults.setFloat(value.coerceIn(0f, 1f), forKey = "volume")

    override var haptics: Boolean
        get() = defaults.boolForKey("haptics")
        set(value) = defaults.setBool(value, forKey = "haptics")

    override fun feedback() = selectionFeedback.selectionChanged()
    override fun newMedia(durationMs: Long, instrument: Instrument): GameMedia =
        IosMedia(factory, durationMs, instrument)
}

private class IosMedia(factory: AppleMediaFactoryProtocol, private val durationMs: Long, instrument: Instrument) :
    GameMedia, AppleInterruptListener {
    private val clock = SessionClock(durationMs)
    private val backingClock = BackingClock(durationMs)
    private val session = factory.createMedia(instrument.name.lowercase(), this)
    private var backingAsset: String? = null
    private var arrangementPath: String? = null
    private val hasTrack get() = backingAsset != null || arrangementPath != null
    private var preparationRequested = false
    private var closed = false
    override var onInterrupted: (() -> Unit)? = null

    override fun uptimeMs() = (NSProcessInfo.processInfo.systemUptime * 1_000).toLong()

    override fun interrupted() {
        if (closed) return
        pause()
        onInterrupted?.invoke()
    }

    override fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack?) {
        if (preparationRequested || closed) return
        preparationRequested = true
        for (pitch in pitches) session.includeSample(pitch, instrumentRoot(pitch))
        this.backingAsset = backingAsset
        this.arrangementPath = arrangement?.path
        session.prepare(backingAsset, arrangementPath)
    }

    override fun prepareResume() {
        if (preparationRequested && !closed) session.prepare(backingAsset, arrangementPath)
    }

    override fun setBackingVolume(volume: Float) {
        if (!closed) session.setBackingVolume(volume.coerceIn(0f, 1f))
    }

    override fun snapshot(): Playback {
        val now = uptimeMs()
        val failure = session.failure
        val ready = !closed && session.ready
        if (!hasTrack) return clock.snapshot(now, ready, failure)
        val ended = session.backingEnded
        if (ended || !ready || failure != null) {
            val position = if (ended) durationMs else session.backingPositionMs.coerceIn(0L, durationMs)
            return Playback(ready, false, ended, position, now, failure)
        }
        return backingClock.snapshot(now) {
            val playing = session.backingPlaying
            val position = session.backingPositionMs.coerceIn(0L, durationMs)
            Playback(true, playing, false, position, now + (uptimeMs() - now) / 2)
        }
    }

    override fun play() {
        if (closed || !session.ready) return
        backingClock.reset()
        if (session.activate() && !hasTrack) clock.play(uptimeMs())
    }

    override fun pause() {
        backingClock.reset()
        clock.pause(uptimeMs())
        session.silence()
    }

    override fun note(midi: Int, gain: Float, durationMs: Int) {
        if (!closed) session.note(midi, gain, durationMs)
    }

    override fun strike(gain: Float) {
        if (!closed) session.strike(gain)
    }

    override fun close() {
        if (closed) return
        closed = true
        onInterrupted = null
        session.close()
    }
}
