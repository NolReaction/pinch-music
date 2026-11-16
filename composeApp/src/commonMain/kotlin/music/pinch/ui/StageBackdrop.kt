package music.pinch.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.random.Random

internal fun stageColor(rgb: Int) = Color(rgb or (0xff shl 24))

/** Side-lit plaster, baked once at the cover or Orbit room's actual raster size. */
internal class StageBackdrop(size: Size, theme: StageTheme) {
    private val wall = stageColor(theme.wall)
    private val light = stageColor(theme.light)
    private val wash = Brush.linearGradient(
        listOf(light, lerp(light, wall, .34f), lerp(light, wall, .72f)),
        Offset(size.width * .81f, 0f), Offset(size.width * .12f, size.height),
    )
    private val raisedGrain = Path()
    private val sunkenGrain = Path()
    private val stroke = Stroke((size.minDimension / 340f).coerceIn(.45f, 1.2f))

    init {
        val random = Random(2741 + 47 * theme.ordinal)
        val count = (size.width * size.height / 34f).toInt().coerceIn(0, 2600)
        repeat(count) { i ->
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val path = if (i % 2 == 0) raisedGrain else sunkenGrain
            path.moveTo(x, y)
            path.lineTo(x + (.42f + random.nextFloat()) * stroke.width,
                y + (random.nextFloat() - .5f) * stroke.width)
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        drawRect(wash)
        drawPath(raisedGrain, Color.White, alpha = .12f, style = stroke)
        drawPath(sunkenGrain, Color(0xff444748), alpha = .067f, style = stroke)
    }
}
