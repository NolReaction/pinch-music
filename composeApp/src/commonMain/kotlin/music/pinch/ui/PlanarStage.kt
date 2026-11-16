package music.pinch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import music.pinch.PanelFeedback

@Composable
internal fun PlanarStage(
    stage: PreparedStage.Cascade, positionMs: () -> Long, modifier: Modifier, animate: Boolean,
    feedback: List<PanelFeedback>, theme: StageTheme, onPresented: () -> Unit,
) {
    NativeCascadeStage(modifier, stage.course, { frame ->
        stage.path.writeGpuFrame(positionMs(), frame, feedback, theme)
    }, animate, onPresented)
}
