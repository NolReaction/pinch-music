@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIView

internal actual val supportsPanelMesh: Boolean get() = true

/** Implemented by the application's GLKit surface, on the same main thread as Compose. */
interface AppleCascadeSurface {
    val view: UIView
    fun setCourse(keys: NSData, rails: NSData, onPresented: () -> Unit)
    /** Points to CascadeFrameSize contiguous floats, valid for this call only. */
    fun draw(frame: COpaquePointer)
    fun close()
}

internal val LocalCascadeFactory = staticCompositionLocalOf<() -> AppleCascadeSurface> {
    error("Cascade surface factory is missing")
}

@Composable
internal actual fun NativeCascadeStage(
    modifier: Modifier, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean,
    onPresented: () -> Unit,
) {
    val factory = LocalCascadeFactory.current
    val presented = remember(course) { mutableStateOf(false) }
    val notifyPresented by rememberUpdatedState {
        presented.value = true
        onPresented()
    }
    val display = remember(factory) { AppleDisplay(factory()) { notifyPresented() } }
    UIKitView(
        factory = { display.surface.view },
        modifier = modifier,
        onRelease = { display.surface.close() },
        properties = UIKitInteropProperties(interactionMode = null)
    )
    CascadeFrameClock(display, course, frame, animate || !presented.value)
}

@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private fun FloatArray.bytes(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong() * 4uL)
}

private class AppleDisplay(val surface: AppleCascadeSurface, private val onPresented: () -> Unit) : CascadeDisplay {
    private var installed: CascadeGpuCourse? = null

    override fun present(course: CascadeGpuCourse, frame: FloatArray) {
        if (installed !== course) {
            surface.setCourse(course.keys.bytes(), course.rails.bytes(), onPresented)
            installed = course
        }
        frame.usePinned { surface.draw(it.addressOf(0)) }
    }
}
