package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The GL drawable can be smaller than the native-resolution Compose interface around it. */
object CascadeRaster {
    fun scale(width: Double, height: Double, nativeScale: Double): Double {
        if (width <= 0 || height <= 0) return nativeScale
        return minOf(nativeScale, 864.0 / minOf(width, height), 1600.0 / maxOf(width, height))
    }
}

/** Immutable for the lifetime of one chart. Strides match nativeStage/stage3d.h. */
internal class CascadeGpuCourse(val keys: FloatArray, val rails: FloatArray)

@Composable
internal expect fun NativeCascadeStage(
    modifier: Modifier,
    course: CascadeGpuCourse,
    frame: (FloatArray) -> Unit,
    animate: Boolean,
    onPresented: () -> Unit,
)

internal interface CascadeDisplay {
    fun present(course: CascadeGpuCourse, frame: FloatArray)
}

/** The session observes audio and presents the native surface in the same display callback. */
internal class StageFrames {
    var render: (() -> Unit)? = null
}

internal val LocalStageFrames = staticCompositionLocalOf<StageFrames> { error("Missing stage frame driver") }

@Composable
internal fun CascadeFrameClock(
    display: CascadeDisplay, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean
) {
    val readFrame by rememberUpdatedState(frame)
    val values = remember(display) { FloatArray(CascadeFrameSize) }
    val frames = LocalStageFrames.current
    val draw = remember(display, course) { {
        readFrame(values)
        display.present(course, values)
    } }
    if (animate) {
        DisposableEffect(frames, draw) {
            frames.render = draw
            onDispose { if (frames.render === draw) frames.render = null }
        }
    } else SideEffect(draw)
}
