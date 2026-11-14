package music.pinch

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** Keeps game samples and preview audio under a single focus owner. */
internal class AndroidAudioFocus(context: Context, private val interrupt: () -> Unit) {
    private val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val main = Handler(Looper.getMainLooper())
    private var focused = false
    private var observing = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (focused && change < 0) interrupt()
    }
    private val request = if (Build.VERSION.SDK_INT >= 26) createRequest() else null
    private var outputs: Set<Int> = emptySet()
    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = checkOutputs()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = checkOutputs()
    }

    private fun outputs() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()

    private fun checkOutputs() {
        if (!observing) return
        val current = outputs()
        if (current != outputs) {
            outputs = current
            interrupt()
        }
    }

    fun observe() {
        outputs = outputs()
        observing = true
        manager.registerAudioDeviceCallback(routes, main)
    }

    @RequiresApi(26)
    private fun createRequest() = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(gameAudioAttributes())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener, main)
        .build()

    @Suppress("DEPRECATION")
    fun acquire(): Boolean {
        if (!focused) {
            val result = if (Build.VERSION.SDK_INT >= 26) {
                manager.requestAudioFocus(checkNotNull(request))
            } else {
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return focused
    }

    @Suppress("DEPRECATION")
    fun abandon() {
        if (!focused) return
        focused = false
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(checkNotNull(request))
        else manager.abandonAudioFocus(listener)
    }

    fun close() {
        if (observing) {
            observing = false
            manager.unregisterAudioDeviceCallback(routes)
        }
        abandon()
    }
}
