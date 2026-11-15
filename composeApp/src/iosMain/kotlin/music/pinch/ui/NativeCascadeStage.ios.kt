@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIView

/** Implemented by the application's GLKit surface, on the same main thread as Compose. */
interface AppleCascadeSurface {
    val view: UIView
    fun setCourse(keys: NSData, rails: NSData, lamps: NSData)
    fun draw(frame: NSData)
    fun close()
}

internal val LocalCascadeFactory = staticCompositionLocalOf<() -> AppleCascadeSurface> {
    error("Cascade surface factory is missing")
}

@Composable
internal actual fun NativeCascadeStage(
    modifier: Modifier, course: CascadeGpuCourse, frame: (FloatArray) -> Unit, animate: Boolean
) {
    val factory = LocalCascadeFactory.current
    val display = remember(factory) { AppleDisplay(factory()) }
    UIKitView(
        factory = { display.surface.view },
        modifier = modifier,
        onRelease = { display.surface.close() },
        properties = UIKitInteropProperties(interactionMode = null)
    )
    CascadeFrameClock(display, course, frame, animate)
}

private fun FloatArray.bytes(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong() * 4uL)
}

private class AppleDisplay(val surface: AppleCascadeSurface) : CascadeDisplay {
    private var installed: CascadeGpuCourse? = null

    override fun present(course: CascadeGpuCourse, frame: FloatArray) {
        if (installed !== course) {
            surface.setCourse(course.keys.bytes(), course.rails.bytes(), course.lamps.bytes())
            installed = course
        }
        // draw() consumes these pinned bytes synchronously. Swift copies the 12
        // floats into its reusable frame before returning (including paused frames).
        frame.usePinned {
            val bytes = NSData.create(bytesNoCopy = it.addressOf(0), length = frame.size.toULong() * 4uL,
                freeWhenDone = false)
            surface.draw(bytes)
        }
    }
}
