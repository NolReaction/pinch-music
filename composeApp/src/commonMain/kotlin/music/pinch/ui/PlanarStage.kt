package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun PlanarStage(
    stage: PreparedStage.Cascade, positionMs: () -> Long, modifier: Modifier, animate: Boolean,
    ledTapMs: Long?,
) {
    NativeCascadeStage(modifier, stage.course, { frame ->
        stage.path.writeGpuFrame(positionMs(), frame, ledTapMs)
    }, animate)
}
