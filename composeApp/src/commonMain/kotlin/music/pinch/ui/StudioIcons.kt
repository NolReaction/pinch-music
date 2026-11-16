package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

internal enum class StudioIcon { Settings, Catalogue, Headphones, Orbit, Cascade, Chevron, Close, Check, Medal, Import, Lock, Heart, FilledHeart, Remove }

@Composable
internal fun StudioGlyph(icon: StudioIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = unit * 1.65f
        fun p(x: Number, y: Number) = Offset(x.toFloat() * unit, y.toFloat() * unit)
        fun s(w: Number, h: Number) = Size(w.toFloat() * unit, h.toFloat() * unit)
        fun line(x: Number, y: Number, x2: Number, y2: Number) =
            drawLine(color, p(x,y), p(x2,y2), stroke, StrokeCap.Round)
        when (icon) {
            StudioIcon.Settings -> {
                line(3,7,10,7)
                line(15,7,21,7)
                line(3,17,6,17)
                line(11,17,21,17)
                drawCircle(color, 2.5f*unit,p(12.5,7),style = Stroke(stroke))
                drawCircle(color, 2.5f*unit,p(8.5,17),style = Stroke(stroke))
            }
            StudioIcon.Catalogue -> {
                line(3,5,3,19)
                line(7,5,7,19)
                line(12,5,12,19)
                line(15,6,19,19)
                line(17,5,21,18)
            }
            StudioIcon.Headphones -> {
                drawArc(color, 180f, 180f, false, p(3,3), s(18,18), style = Stroke(stroke))
                line(3,12,3,17); line(21,12,21,17)
                drawRoundRect(color,p(2.8,12),s(4.8,8),CornerRadius(unit * 1.8f),style = Stroke(stroke))
                drawRoundRect(color,p(16.4,12),s(4.8,8),CornerRadius(unit * 1.8f),style = Stroke(stroke))
            }
            StudioIcon.Orbit -> {
                drawOval(color,p(2,7),s(20,10),style = Stroke(stroke))
                drawCircle(color, 2.8f*unit,p(16.5,8))
            }
            StudioIcon.Cascade -> {
                line(2,7,8,8.5); line(11,12.5,17.5,11); line(8,18,14,20)
                drawCircle(color,1.9f*unit,p(11.5,5))
            }
            StudioIcon.Chevron -> { line(9,5,16,12); line(16,12,9,19) }
            StudioIcon.Close -> { line(6,6,18,18); line(18,6,6,18) }
            StudioIcon.Check -> { line(4,12.5,9.5,18); line(9.5,18,20,6) }
            StudioIcon.Medal -> {
                val badge = Path().apply {
                    moveTo(12*unit,2*unit)
                    lineTo(20*unit,5.7f*unit)
                    lineTo(20*unit,13*unit)
                    lineTo(17*unit,17.8f*unit)
                    lineTo(12*unit,21.8f*unit)
                    lineTo(7*unit,17.8f*unit)
                    lineTo(4*unit,13*unit)
                    lineTo(4*unit,5.7f*unit)
                    close()
                }
                drawPath(badge,color,style = Stroke(stroke))
                line(8.5,12.5,12,8.7); line(12,8.7,15.5,12.5)
            }
            StudioIcon.Lock -> {
                drawArc(color, 180f, 180f, false, p(7,3), s(10,13), style = Stroke(stroke))
                drawRoundRect(color, p(5,10), s(14,11), CornerRadius(unit * 2f), style = Stroke(stroke))
            }
            StudioIcon.Heart, StudioIcon.FilledHeart -> {
                val heart = Path().apply {
                    moveTo(12 * unit, 20 * unit)
                    cubicTo(9 * unit, 17 * unit, 2.5f * unit, 12.6f * unit, 2.5f * unit, 8.2f * unit)
                    cubicTo(2.5f * unit, 2.3f * unit, 9.4f * unit, 1.9f * unit, 12 * unit, 6.9f * unit)
                    cubicTo(14.6f * unit, 1.9f * unit, 21.5f * unit, 2.3f * unit, 21.5f * unit, 8.2f * unit)
                    cubicTo(21.5f * unit, 12.6f * unit, 15 * unit, 17 * unit, 12 * unit, 20 * unit)
                    close()
                }
                if (icon == StudioIcon.FilledHeart) drawPath(heart, color)
                else drawPath(heart, color, style = Stroke(stroke))
            }
            StudioIcon.Remove -> {
                line(4, 6, 20, 6)
                line(9, 3, 15, 3)
                line(6, 9, 7, 20)
                line(7, 20, 17, 20)
                line(17, 20, 18, 9)
            }
            StudioIcon.Import -> {
                line(12,3,12,15)
                line(7.5,10.5,12,15)
                line(12,15,16.5,10.5)
                line(4,17,4,20)
                line(4,20,20,20)
                line(20,20,20,17)
            }
        }
    }
}
