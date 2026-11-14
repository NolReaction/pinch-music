@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSUserDefaults
import platform.UIKit.UISelectionFeedbackGenerator
import platform.UIKit.UIView
import platform.UIKit.UIViewController

// These three interfaces are the Objective-C boundary for the native AVFoundation implementation.
interface AppleMediaFactoryProtocol {
    fun readChart(): String?
    fun createMedia(preview: Boolean, observer: AppleInterruptListener): AppleMediaSession
}

interface AppleInterruptListener {
    fun interrupted()
}

interface AppleMediaSession {
    val videoView: UIView
    fun includeSample(midi: Int)
    fun prepare()
    fun snapshot(): Playback
    fun play()
    fun pause()
    fun note(midi: Int, gain: Float)
    fun setPreviewVolume(volume: Float)
    fun close()
}

fun MainViewController(factory: AppleMediaFactoryProtocol): UIViewController {
    val platform = IosPlatform(factory)
    return ComposeUIViewController { App(platform) }
}

private class IosPlatform(private val factory: AppleMediaFactoryProtocol) : Platform {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val selectionFeedback = UISelectionFeedbackGenerator()

    override fun chartJson(): String = requireNotNull(factory.readChart()) {
        "Не удалось прочитать media/demo-chart.json."
    }

    override var volume: Float
        get() = if (defaults.objectForKey("volume") == null) 0.8f else defaults.floatForKey("volume")
        set(value) = defaults.setFloat(value.coerceIn(0f, 1f), forKey = "volume")

    override var haptics: Boolean
        get() = defaults.boolForKey("haptics")
        set(value) = defaults.setBool(value, forKey = "haptics")

    override var bestScore: Int?
        get() = if (defaults.objectForKey("bestScore") == null) null else defaults.integerForKey("bestScore").toInt()
        set(value) {
            if (value == null) defaults.removeObjectForKey("bestScore")
            else defaults.setInteger(value.toLong(), forKey = "bestScore")
        }

    override fun feedback() = selectionFeedback.selectionChanged()
    override fun newMedia(preview: Boolean): GameMedia = IosMedia(factory, preview)
}

private class IosMedia(factory: AppleMediaFactoryProtocol, private val preview: Boolean) :
    GameMedia, AppleInterruptListener {
    private val session = factory.createMedia(preview, this)
    private var pendingInterruption = false
    override var onInterrupted: (() -> Unit)? = null
        set(value) {
            field = value
            if (pendingInterruption && value != null) {
                pendingInterruption = false
                value()
            }
        }

    override fun interrupted() {
        val listener = onInterrupted
        if (listener == null) pendingInterruption = true else listener()
    }

    override fun prepare(pitches: Set<Int>) {
        if (!preview) pitches.forEach(session::includeSample)
        session.prepare()
    }

    @Composable
    override fun Video(modifier: Modifier) {
        UIKitView(factory = { session.videoView }, modifier = modifier)
    }

    override fun snapshot() = session.snapshot()
    override fun play() = session.play()
    override fun pause() = session.pause()
    override fun note(midi: Int, gain: Float) = session.note(midi, gain)
    override fun setPreviewVolume(volume: Float) = session.setPreviewVolume(volume)
    override fun close() {
        onInterrupted = null
        session.close()
    }
}
