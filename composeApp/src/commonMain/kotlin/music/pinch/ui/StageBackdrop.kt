package music.pinch.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp

internal fun stageColor(rgb: Int) = Color(rgb or (0xff shl 24))

/** A clean, side-lit studio wall. Baked once at the cover or Orbit room size. */
internal class StageBackdrop(size: Size, theme: StageTheme) {
    private val wall = stageColor(theme.wall)
    private val light = stageColor(theme.light)
    private val wash = Brush.linearGradient(
        listOf(light, lerp(light, wall, .28f), lerp(light, wall, .66f)),
        Offset(size.width * .81f, 0f), Offset(size.width * .12f, size.height),
    )
    private val softbox = Brush.radialGradient(
        listOf(Color(0x20ffffff), Color.Transparent),
        Offset(size.width * .85f, size.height * .22f), size.maxDimension * .76f,
    )

    fun draw(scope: DrawScope) = with(scope) {
        drawRect(wash)
        drawRect(softbox)
    }
}
