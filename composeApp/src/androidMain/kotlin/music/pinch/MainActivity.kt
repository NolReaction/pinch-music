package music.pinch

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import music.pinch.core.Chart
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        volumeControlStream = AudioManager.STREAM_MUSIC
        platform = AndroidPlatform(this)
        platform.warmLaunchSound()
        setContent {
            App(platform) { enabled, action -> BackHandler(enabled = enabled, onBack = action) }
        }
    }

    override fun onResume() {
        super.onResume()
        platform.foreground = true
    }

    override fun onPause() {
        platform.foreground = false
        LaunchPresentation.dismiss()
        platform.stopLaunchSound()
        platform.active?.interrupt()
        super.onPause()
    }

    override fun onDestroy() {
        platform.close()
        super.onDestroy()
    }
}

private class AndroidPlatform(private val activity: MainActivity) : Platform {
    private val settings = activity.getSharedPreferences("rhythm", Context.MODE_PRIVATE)
    var foreground = false
    var active: AndroidMedia? = null
        private set

    private val recordings = AndroidRecordings(activity)
    private val songImport = AndroidSongImport(activity)
    private val samples = NoteSampleCache(activity.assets)
    private val launchSound = AndroidLaunchSound(activity)

    fun close() {
        stopLaunchSound()
        active?.close()
        samples.close()
    }

    override suspend fun saveImportAudio(id: String, audio: PickedAudio): Boolean {
        val recording = audio.recording ?: return false
        recordings.save(id, recording)
        return true
    }
    override fun importAudioTrack(id: String) = recordings.track(id)
    override fun removeImportAudio(id: String) = recordings.remove(id)

    override suspend fun pickAudio() = songImport.pickAudio()
    override suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?) =
        AndroidAccompaniment.track(activity, chart, bundledId, previewInstrument)
    override fun readValue(key: String): String? = settings.getString(key, null)
    override fun writeValue(key: String, value: String) {
        settings.edit().putString(key, value).apply()
    }

    override fun removeValue(key: String) { settings.edit().remove(key).apply() }

    override var volume: Float
        get() = settings.getFloat("volume", 0.8f).coerceIn(0f, 1f)
        set(value) = settings.edit().putFloat("volume", value.coerceIn(0f, 1f)).apply()

    override var haptics: Boolean
        get() = settings.getBoolean("haptics", false)
        set(value) = settings.edit().putBoolean("haptics", value).apply()

    fun warmLaunchSound() { if (LaunchPresentation.visible && volume > 0f) launchSound.warm() }
    override fun playLaunchSound(volume: Float) { if (foreground) launchSound.play(volume) }
    override fun stopLaunchSound() = launchSound.stop()

    override fun feedback() {
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun newMedia(durationMs: Long, instrument: Instrument): GameMedia {
        stopLaunchSound()
        active?.close()
        return AndroidMedia(activity, durationMs, instrument, samples, { foreground }) { media ->
            if (active === media) active = null
        }.also { active = it }
    }
}
