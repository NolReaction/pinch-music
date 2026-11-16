package music.pinch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import music.pinch.Actions
import music.pinch.Scene
import music.pinch.ScreenState
import music.pinch.StageMode

internal val Ink = StudioGraphite
internal val Surface = Color(0xff202627)
internal val Line = Color(0xff384140)
internal val White = StudioIvory
internal val Muted = Color(0xffb6bcb4)
internal val DarkText = Color(0xff281b17)

private val AppColors = darkColorScheme(
    primary = StudioCoral, onPrimary = DarkText, secondary = StudioMint, background = Ink,
    onBackground = White, surface = Surface, onSurface = White, onSurfaceVariant = Muted
)

@Composable
internal fun GameScreen(state: ScreenState, actions: Actions, positionMs: () -> Long, stage: PreparedStage?) {
    val orbitAtlas = rememberOrbitAtlas(state.stageMode == StageMode.Orbit)
    MaterialTheme(colorScheme = AppColors) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.TopCenter) {
            val content = when (state.scene) {
                Scene.Menu, Scene.Error, Scene.Result -> Modifier.safeDrawingPadding().widthIn(max = 560.dp)
                else -> Modifier
            }
            Box(content.fillMaxSize()) {
                when (state.scene) {
                    Scene.Menu -> StudioMenu(state, actions)
                    Scene.Error -> ErrorScreen(state, actions)
                    Scene.Result -> ResultScreen(state, actions)
                    else -> PerformanceScreen(state, actions, positionMs, stage, orbitAtlas.value)
                }
            }
        }
    }
}

@Composable
internal fun ResultRow(label: String, value: String, accent: Color = White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Muted, fontSize = 16.sp)
        Text(value, color = accent, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun ErrorScreen(state: ScreenState, actions: Actions) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BeatMark(Modifier.size(34.dp))
        Heading("Не удалось запустить трек")
        Text(state.error?.takeIf { it.isNotBlank() } ?: "Проверь файлы мелодии и попробуй ещё раз.",
            color = Muted, fontSize = 16.sp)
        ActionButton("Попробовать снова", actions::restart)
        ActionButton("В меню", actions::menu, primary = false)
    }
}

@Composable
internal fun Caption(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun Heading(text: String) {
    Text(text, color = White, fontSize = 36.sp, lineHeight = 40.sp,
        letterSpacing = (-1.5).sp, fontWeight = FontWeight.Black, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun Rule() = Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line))

internal fun number(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

internal enum class IconShape { Play, Pause }

@Composable
internal fun Symbol(icon: IconShape, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        when (icon) {
            IconShape.Play -> drawPath(Path().apply {
                moveTo(.23f * w, .12f * h)
                lineTo(.23f * w, .88f * h)
                lineTo(.85f * w, .5f * h)
                close()
            }, tint)
            IconShape.Pause -> listOf(.35f, .65f).forEach {
                drawLine(tint, Offset(w * it, h * .19f), Offset(w * it, h * .81f),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun ActionButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = true, icon: IconShape? = null, enabled: Boolean = true, accent: Color = StudioCoral,
) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (primary) DarkText else White
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (pressed && enabled) .975f else 1f, tween(95), label = "Нажатие")
    Row(modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            alpha = if (enabled) 1f else .50f
        }
        .clip(shape).background(if (primary) accent else Surface)
        .border(1.dp, if (primary) accent else Line, shape)
        // Scrollable parents delay the default ripple. Show contact on down while
        // letting clickable own activation, keyboard semantics and scroll cancellation.
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try { waitForUpOrCancellation() } finally { pressed = false }
            }
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 50.dp).padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Symbol(icon, fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
