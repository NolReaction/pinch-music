package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
internal fun PlanarStage(
    path: PlanarPath, positionMs: () -> Long, modifier: Modifier, animate: Boolean,
    hitMs: Long?, collectedEchoMs: Long?, ledTapMs: Long?,
) {
    val course = remember(path) { path.gpuCourse() }
    NativeCascadeStage(modifier, course, { frame ->
        path.writeGpuFrame(positionMs(), frame, hitMs, collectedEchoMs, ledTapMs)
    }, animate)
}
