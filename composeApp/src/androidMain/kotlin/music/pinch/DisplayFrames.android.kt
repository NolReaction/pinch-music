package music.pinch

import android.annotation.TargetApi
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToLong

@Composable
internal actual fun PlaybackFrames(onFrame: (presentationUptimeMs: Long) -> Unit) {
    val view = LocalView.current
    val latest by rememberUpdatedState(onFrame)
    DisposableEffect(view) {
        val scheduler = Choreographer.getInstance()
        val tick: (Long) -> Unit = { nanos ->
            // Frame timelines use nanoTime, while input and GameMedia use uptimeMillis.
            val target = SystemClock.uptimeMillis() + ((nanos - System.nanoTime()) / 1_000_000.0).roundToLong()
            latest(target)
        }
        val cancel = if (Build.VERSION.SDK_INT >= 33) timelineFrames(scheduler, tick)
            else legacyFrames(scheduler, view, tick)
        onDispose { cancel() }
    }
}

@TargetApi(33)
private fun timelineFrames(scheduler: Choreographer, tick: (Long) -> Unit): () -> Unit {
    val callback = object : Choreographer.VsyncCallback {
        override fun onVsync(data: Choreographer.FrameData) {
            scheduler.postVsyncCallback(this)
            tick(data.preferredFrameTimeline.expectedPresentationTimeNanos)
        }
    }
    scheduler.postVsyncCallback(callback)
    return { scheduler.removeVsyncCallback(callback) }
}

private fun legacyFrames(scheduler: Choreographer, view: View, tick: (Long) -> Unit): () -> Unit {
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(vsync: Long) {
            scheduler.postFrameCallback(this)
            val hz = view.display?.refreshRate ?: 0f
            val interval = if (hz > 0f) (1_000_000_000.0 / hz).roundToLong() else 0L
            tick(vsync + interval)
        }
    }
    scheduler.postFrameCallback(callback)
    return { scheduler.removeFrameCallback(callback) }
}
