package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.abs
import kotlin.math.sin

/** Open at the top and bottom so the sphere and its tangent point remain visible. */
internal fun DrawScope.drawLandingCue(center: Offset, ballRadius: Float, phase: Float) {
    val distance = abs(phase)
    val radius = ballRadius * (1.16f + .68f * distance)
    val topLeft = center - Offset(radius, radius * .75f)
    val bounds = Size(2f * radius, 1.5f * radius)
    val tint = if (distance < .42f) StudioIvory else StudioMint
    val line = (ballRadius * .102f).coerceAtLeast(1f)
    val sweep = 64f + 14f * (1f - distance)
    for (side in 0..1) {
        val start = 180f * side - sweep * .5f
        drawArc(StudioGraphite.copy(alpha = .75f), start, sweep, false, topLeft, bounds,
            style = Stroke(line * 2.05f, cap = StrokeCap.Round))
        drawArc(tint, start, sweep, false, topLeft, bounds,
            style = Stroke(line, cap = StrokeCap.Round), alpha = .93f - .15f * distance)
    }
}

private val sparkX = floatArrayOf(-1f, -.78f, -.41f, .48f, .81f, 1f)
private val sparkY = floatArrayOf(-.22f, -.64f, -.97f, -.86f, -.59f, -.29f)

internal fun DrawScope.drawLandingSparks(center: Offset, ballRadius: Float, phase: Float) {
    val alpha = (1f - phase) * (1f - phase)
    val reach = ballRadius * (.46f + 2.05f * phase)
    val tail = ballRadius * (.18f + .29f * (1f - phase))
    val fall = Offset(0f, ballRadius * .87f * phase * phase)
    for (i in sparkX.indices) {
        val direction = Offset(sparkX[i], sparkY[i])
        val tip = center + direction * reach + fall
        val color = when (i % 3) {
            0 -> StudioCoral
            1 -> StudioIvory
            else -> StudioMint
        }
        drawLine(StudioGraphite, tip - direction * tail, tip, ballRadius * .15f, StrokeCap.Round, alpha = .40f * alpha)
        drawLine(color, tip - direction * tail, tip, ballRadius * .09f, StrokeCap.Round, alpha = alpha)
    }
}

@Composable
internal fun StreakFlame(combo: Int, tier: Int, positionMs: () -> Long, modifier: Modifier) {
    val path = remember { Path() }
    Canvas(modifier) {
        val pulse = if (combo < 3) 0f else sin(positionMs().mod(720L) * .008726646f)
        val heat = when {
            combo >= 8 -> 1f
            combo >= 3 -> .67f
            combo > 0 -> .32f
            else -> 0f
        }
        scale(size.width / 28f, size.height / 32f, Offset.Zero) {
            val tipX = 13.8f + pulse * heat * 1.35f
            val tipY = 6f - heat * 5f
            path.reset()
            path.moveTo(tipX, tipY)
            path.cubicTo(18.1f, 9f, 12.8f, 12f, 18.7f, 15.1f)
            path.cubicTo(21.3f, 13.9f, 21.7f, 11.9f, 21.3f, 9.7f)
            path.cubicTo(29f, 20.5f, 25f, 29.5f, 15.4f, 30.4f)
            path.cubicTo(4.5f, 31.4f, .9f, 22f, 5.6f, 15.2f)
            path.cubicTo(5.8f, 18.9f, 7.3f, 20.6f, 9.1f, 20.5f)
            path.cubicTo(7.1f, 13.2f, 15.4f, 10.4f, tipX, tipY)
            path.close()
            if (combo == 0) {
                drawPath(path, StudioIvory.copy(alpha = .41f), style = Stroke(1.65f))
            } else {
                drawPath(path, if (combo >= 3) StudioCoral else Color(0xffce8061))
                if (combo >= 3) {
                    path.reset()
                    path.moveTo(14.7f, 15.5f + pulse)
                    path.cubicTo(15.5f, 20.3f, 20.8f, 21.4f, 19f, 26f)
                    path.cubicTo(17f, 31f, 10.5f, 30f, 10.7f, 25.4f)
                    path.cubicTo(10.7f, 21.3f, 14.4f, 20.4f, 14.7f, 15.5f + pulse)
                    path.close()
                    val core = when (tier) {
                        0 -> StudioIvory
                        1 -> Color(0xffffca88)
                        else -> StudioMint
                    }
                    drawPath(path, core)
                }
                if (combo >= 8) {
                    drawLine(if (tier >= 1) StudioMint else StudioCoral, Offset(23.5f, 7.1f), Offset(25f, 3.7f + pulse),
                        1.8f, StrokeCap.Round)
                    drawCircle(StudioIvory, 1.15f, Offset(6f, 7f - pulse))
                }
            }
        }
    }
}
