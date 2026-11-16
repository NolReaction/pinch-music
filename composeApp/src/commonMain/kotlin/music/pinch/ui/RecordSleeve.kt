package music.pinch.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Each record has its own paper, label, ink and cutting pattern. */
private enum class Sleeve(val paper: Color, val label: Color, val overprint: Color) {
    Floor(Color(0xfff2a278), Color(0xfff0c96b), Color(0xffaf4939)),
    Stereo(Color(0xffa8ceaf), Color(0xffffa07b), Color(0xff347668)),
    Gypsy(Color(0xffe9c35e), Color(0xffa0c3aa), Color(0xffa46d31)),
    Levels(Color(0xffaabcec), Color(0xfff6ab84), Color(0xff496399)),
    Stay(Color(0xffe4aec2), Color(0xffedb85e), Color(0xff986078)),
    TurnDown(Color(0xffefb66f), Color(0xffb4d2c1), Color(0xffb26239)),
    Yeah(Color(0xffc3cf82), Color(0xffeaa68a), Color(0xff77844a)),
    Countdown(Color(0xffbbb2d7), Color(0xffefc283), Color(0xff766595)),
}

private val Vinyl = Color(0xff202423)
private val VinylLight = Color(0xff8d9484)
internal val SleeveType = Color(0xff1c2925)

@Composable
internal fun RecordSleeve(theme: StageTheme, modifier: Modifier, thumbnail: Boolean = false) {
    Spacer(modifier.drawWithCache {
        val sleeve = Sleeve.entries[theme.ordinal]
        val w = size.width
        val h = size.height
        val r = if (thumbnail) .59f * h else minOf(.755f * h, .448f * w)
        val center = Offset(w * if (thumbnail) .84f else .92f, h * (.48f + theme.ordinal % 3 * .035f))
        val angle = -45f + theme.ordinal * 16f
        // The left paper jacket physically overlaps the disc. Its cut-out never
        // enters the typographic column, even for a long title or a larger font.
        val jacket = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * .704f, 0f)
            lineTo(w * .704f, h * .17f)
            cubicTo(w * .704f, h * .30f, w * .646f, h * .33f, w * .646f, h * .50f)
            cubicTo(w * .646f, h * .67f, w * .704f, h * .70f, w * .704f, h * .83f)
            lineTo(w * .704f, h)
            lineTo(0f, h)
            close()
        }
        val grain = Path()
        var seed = 110351 + 739 * theme.ordinal
        fun fraction(): Float {
            seed = seed * 1664525 + 1013904223
            return seed.ushr(8) / 16777216f
        }
        repeat(if (thumbnail) 28 else 290) {
            val x = fraction() * w
            val y = fraction() * h
            grain.moveTo(x, y)
            grain.relativeLineTo(if (thumbnail) .65.dp.toPx() else 1.15.dp.toPx(), .32.dp.toPx())
        }
        val grainStroke = Stroke(.58.dp.toPx())
        val labelLines = Path()
        val spokes = 4 + theme.ordinal % 4
        repeat(spokes) {
            val a = (it * 2.0 * PI / spokes + .35 * theme.ordinal).toFloat()
            val ca = cos(a)
            val sa = sin(a)
            labelLines.moveTo(center.x + ca * r * .105f, center.y + sa * r * .105f)
            labelLines.lineTo(center.x + ca * r * .211f - sa * r * .025f,
                center.y + sa * r * .211f + ca * r * .025f)
        }
        onDrawBehind {
            clipRect {
                drawRect(sleeve.paper)
                // One broad offset-ink orbit joins the record edge and its paper sleeve.
                val halo = r * 1.13f
                drawCircle(sleeve.overprint.copy(alpha = .42f), halo,
                    center + Offset(r * .055f, r * .065f), style = Stroke(r * .062f))
                drawCircle(sleeve.overprint.copy(alpha = .58f), r * 1.04f, center + Offset(-r * .025f, r * .026f))
                drawCircle(Vinyl, r, center)
                val n = if (thumbnail) 9 else 30
                for (i in 0 until n) {
                    val radius = r * (.31f + .673f * i / (n - 1))
                    val origin = center - Offset(radius, radius)
                    val extent = Size(2 * radius, 2 * radius)
                    val cut = maxOf(.48.dp.toPx(), r * .0039f)
                    drawCircle(VinylLight.copy(alpha = if (i % 5 == 0) .56f else .25f),
                        radius, center, style = Stroke(cut))
                    drawArc(sleeve.paper.copy(alpha = .65f), angle + (i % 4) * 2f,
                        51f + (i % 6) * 5f, false, origin, extent, style = Stroke(cut * 1.34f))
                    drawArc(sleeve.paper.copy(alpha = .44f), angle + 181f, 64f,
                        false, origin, extent, style = Stroke(cut))
                }
                drawCircle(sleeve.label, r * .275f, center)
                drawPath(labelLines, sleeve.overprint, style = Stroke(r * .027f))
                drawCircle(Vinyl, r * .041f, center)
                drawCircle(sleeve.paper, r * .015f, center)
                if (!thumbnail) {
                    drawPath(jacket, sleeve.paper)
                    drawPath(jacket, sleeve.overprint.copy(alpha = .65f), style = Stroke(1.dp.toPx()))
                } else {
                    drawRect(sleeve.paper, size = Size(w * .39f, h))
                    drawLine(sleeve.overprint, Offset(w * .39f, 0f), Offset(w * .39f, h), .6.dp.toPx())
                    // A pair of registration rules on the spine read even at catalogue size.
                    val left = w * .095f
                    val y = h * (.22f + (theme.ordinal % 4) * .13f)
                    drawRect(sleeve.overprint, Offset(left, y), Size(w * .21f, h * .075f))
                    drawRect(sleeve.overprint, Offset(left, y + .105f*h), Size(w * .13f, h * .025f))
                }
                drawPath(grain, SleeveType.copy(alpha = .13f), style = grainStroke)
            }
        }
    })
}
