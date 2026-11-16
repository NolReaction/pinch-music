@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAFrameRateRangeMake
import platform.UIKit.UIScreen
import platform.darwin.NSObject
import kotlin.math.roundToLong

private class DisplayTick(private val tick: (Long) -> Unit) : NSObject() {
    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    @ObjCAction
    fun nextFrame(link: CADisplayLink) {
        val uptime = NSProcessInfo.processInfo.systemUptime
        tick(((uptime + (link.targetTimestamp - CACurrentMediaTime())) * 1_000).roundToLong())
    }
}

@Composable
internal actual fun PlaybackFrames(onFrame: (presentationUptimeMs: Long) -> Unit) {
    val latest by rememberUpdatedState(onFrame)
    DisposableEffect(Unit) {
        val target = DisplayTick { latest(it) }
        val link = CADisplayLink.displayLinkWithTarget(target, NSSelectorFromString("nextFrame:"))
        // Request ProMotion while playing; iOS still controls thermal/low-power
        // limits. The pose follows targetTimestamp at every actual refresh rate.
        val maximum = UIScreen.mainScreen.maximumFramesPerSecond.toFloat().coerceAtLeast(60f)
        link.preferredFrameRateRange = CAFrameRateRangeMake(60f, maximum, maximum)
        link.addToRunLoop(NSRunLoop.mainRunLoop, forMode = NSRunLoopCommonModes)
        onDispose { link.invalidate() }
    }
}
