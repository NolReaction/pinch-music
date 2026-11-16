package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

private val LogoInk = Color(0xFF151A1B)
private val LogoIvory = Color(0xFFF3ECDD)
private val LogoCoral = Color(0xFFFF936F)
private val LogoMint = Color(0xFFA4D6C0)
private val contacts = floatArrayOf(.03f, .215f, .435f)
private val centers = arrayOf(Offset(31f, 30f), Offset(45f, 55f), Offset(67f, 27f))

/** Two descending key faces and the marble, drawn in the same 100-unit space as the native icon. */
@Composable
internal fun BeatMark(modifier: Modifier = Modifier, progress: Float = 1f) {
    Canvas(modifier) {
        val scale = size.minDimension / 100f
        val t = progress.coerceIn(0f, 1f)
        withTransform({
            translate((size.width - 100 * scale) / 2, (size.height - 100 * scale) / 2)
            scale(scale, scale, Offset.Zero)
        }) {
            drawLine(LogoIvory, Offset(17f, 30f), Offset(39f, 67f), 10f, StrokeCap.Round)
            drawLine(LogoCoral, Offset(47f, 74f), Offset(81f, 30f), 10f, StrokeCap.Round)
            for (i in contacts.indices) {
                val decay = (t - contacts[i]) / .20f
                if (decay in 0f..1f) {
                    drawCircle(LogoMint.copy(alpha = .34f * (1f - decay)), 8f + 10f * decay, centers[i],
                        style = Stroke(1.1f * (1f - .5f * decay)))
                }
            }
            val ball = when {
                t < contacts.first() -> Offset(31f, 25f + 5f * t / contacts.first())
                t >= contacts.last() -> centers.last()
                else -> {
                    val i = if (t < contacts[1]) 0 else 1
                    val u = (t - contacts[i]) / (contacts[i + 1] - contacts[i])
                    val from = centers[i]
                    val to = centers[i + 1]
                    Offset(from.x + (to.x - from.x) * u,
                        from.y + (to.y - from.y) * u - 4f * (if (i == 0) 4f else 12f) * u * (1f - u))
                }
            }
            drawCircle(LogoMint, 8f, ball)
        }
    }
}

@Composable
internal fun LaunchSignature(progress: () -> Float) {
    val t = progress()
    val fade = ((1f - t) / .35f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().background(LogoInk.copy(alpha = fade)), contentAlignment = Alignment.Center) {
        BeatMark(Modifier.size(152.dp).alpha(fade), t)
    }
}
