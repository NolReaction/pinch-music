package music.pinch.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

internal fun stageColor(rgb: Int) = Color(rgb or (0xff shl 24))

/** Built once per size for the song cover and baked into Orbit's static wall. */
internal class StageBackdrop(size: Size, theme: StageTheme) {
    private val w = size.width
    private val h = size.height
    private val wall = stageColor(theme.wall)
    private val light = stageColor(theme.light)
    private val line = stageColor(theme.accent).copy(alpha = .14f)
    private val paint = Brush.linearGradient(listOf(light, lerp(light, wall, .48f), wall),
        Offset(w * .08f, h * .06f), Offset(w * .92f, h * .94f))
    private val stroke = Stroke(size.minDimension * .0035f)
    private val pattern = Path().apply {
        for (i in -2..7) {
            val t = i * .18f
            when (theme) {
                StageTheme.Floor -> {
                    moveTo(w * (t - .46f), h)
                    lineTo(w * (t + .45f), 0f)
                }
                StageTheme.Stereo -> {
                    moveTo(0f, h * (t + .14f))
                    cubicTo(w * .31f, h * (t - .12f), w * .72f, h * (t + .31f), w, h * t)
                }
                StageTheme.Gypsy -> {
                    moveTo(w * (t - .24f), h)
                    cubicTo(w * (t - .28f), h * .37f, w * (t + .12f), h * .37f, w * (t + .08f), h)
                }
                StageTheme.Levels -> {
                    moveTo(0f, h * t)
                    for (step in 1..5) {
                        val x = step * .20f * w
                        lineTo(x, h * (t - (step - 1) * .065f))
                        lineTo(x, h * (t - step * .065f))
                    }
                }
                StageTheme.Stay -> if (i >= 0) {
                    val r = w * (.16f + i * .23f)
                    addOval(Rect(w * .8f - r, h * .37f - r, w * .8f + r, h * .37f + r))
                }
                StageTheme.TurnDown -> {
                    moveTo(0f, h * t)
                    lineTo(w * .56f, h * (t + .15f))
                    lineTo(w, h * (t - .03f))
                }
                StageTheme.Yeah -> {
                    moveTo(0f, h * t)
                    lineTo(w, h * (t + .31f))
                    moveTo(w * t, h)
                    lineTo(w * (t + .52f), 0f)
                }
                StageTheme.Countdown -> {
                    val r = w * (1f + t)
                    addOval(Rect(w * .18f - r, h * .85f - r * .44f, w * .18f + r, h * .85f + r * .44f))
                }
            }
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        drawRect(paint)
        drawPath(pattern, line, style = stroke)
    }
}
