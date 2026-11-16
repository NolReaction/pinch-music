package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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

@Composable
internal fun CascadeFrameClock(
    display: CascadeDisplay, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean
) {
    val readFrame by rememberUpdatedState(frame)
    val values = remember(display) { FloatArray(CascadeFrameSize) }
    if (animate) {
        LaunchedEffect(display, course) {
            while (true) withFrameNanos {
                readFrame(values)
                display.present(course, values)
            }
        }
    } else {
        SideEffect {
            readFrame(values)
            display.present(course, values)
        }
    }
}
