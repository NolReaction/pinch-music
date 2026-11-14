package music.pinch

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
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
        platform.active?.interrupt()
        super.onPause()
    }

    override fun onDestroy() {
        platform.active?.close()
        super.onDestroy()
    }
}

private class AndroidPlatform(private val activity: MainActivity) : Platform {
    private val settings = activity.getSharedPreferences("rhythm", Context.MODE_PRIVATE)
    var foreground = false
    var active: AndroidMedia? = null
        private set

    private val songImport = AndroidSongImport(activity)

    override suspend fun pickAudio() = songImport.pickAudio()
    override fun readValue(key: String): String? = settings.getString(key, null)
    override fun writeValue(key: String, value: String) {
        settings.edit().putString(key, value).apply()
    }

    override var volume: Float
        get() = settings.getFloat("volume", 0.8f).coerceIn(0f, 1f)
        set(value) = settings.edit().putFloat("volume", value.coerceIn(0f, 1f)).apply()

    override var haptics: Boolean
        get() = settings.getBoolean("haptics", false)
        set(value) = settings.edit().putBoolean("haptics", value).apply()

    override fun feedback() {
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun newMedia(durationMs: Long, instrument: Instrument): GameMedia {
        active?.close()
        return AndroidMedia(activity, durationMs, instrument, { foreground }) { media ->
            if (active === media) active = null
        }.also { active = it }
    }
}
