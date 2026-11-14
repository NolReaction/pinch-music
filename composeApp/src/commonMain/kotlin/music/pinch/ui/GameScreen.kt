package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import music.pinch.Actions
import music.pinch.GameMedia
import music.pinch.Scene
import music.pinch.ScreenState
import music.pinch.resources.Res
import music.pinch.resources.first_rhythm
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

internal val Ink = Color(0xff0a131f)
internal val Surface = Color(0xff142334)
internal val Line = Color(0xff304356)
internal val White = Color(0xfff1f1eb)
internal val Muted = Color(0xffadbed0)
internal val Amber = Color(0xffffc063)
internal val Mint = Color(0xff63dadd)
internal val DarkText = Color(0xff231b0f)

private val AppColors = darkColorScheme(
    primary = Amber, onPrimary = DarkText, secondary = Mint, background = Ink,
    onBackground = White, surface = Surface, onSurface = White, onSurfaceVariant = Muted
)

@Composable
fun GameScreen(state: ScreenState, actions: Actions, media: GameMedia?) {
    MaterialTheme(colorScheme = AppColors) {
        Box(
            Modifier.fillMaxSize().background(Ink).safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                when (state.scene) {
                    Scene.Menu -> Menu(state, actions)
                    Scene.Error -> ErrorScreen(state, actions)
                    Scene.Result -> ResultScreen(state, actions)
                    else -> PerformanceScreen(state, actions, media)
                }
            }
        }
    }
}

@Composable
private fun Menu(state: ScreenState, actions: Actions) {
    var settings by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Mark()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { settings = true }) { Text("Настройки", color = Muted) }
        }
        Column {
            Text("В такт", fontSize = 58.sp, lineHeight = 65.sp, letterSpacing = (-3).sp,
                fontWeight = FontWeight.Black, color = White, modifier = Modifier.semantics { heading() })
            Text("Поймай ритм. Собери мелодию.", color = Muted, fontSize = 16.sp)
        }
        TrackCard(state)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Играть", onClick = { actions.start(false) }, icon = IconShape.Play)
            ActionButton("Послушать", onClick = { actions.start(true) }, primary = false)
        }
        state.bestScore?.let {
            Rule()
            ResultRow("Личный рекорд", number(it), accent = Amber)
        }
    }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Настройки") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Громкость")
                        Text("${(state.volume * 100).roundToInt()} %", color = Amber)
                    }
                    Slider(value = state.volume, onValueChange = actions::volume,
                        modifier = Modifier.semantics { contentDescription = "Громкость" })
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Тактильный отклик", modifier = Modifier.weight(1f))
                        Switch(checked = state.haptics, onCheckedChange = actions::haptics,
                            modifier = Modifier.semantics { contentDescription = "Тактильный отклик" })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("Готово") } }
        )
    }
}

@Composable
private fun TrackCard(state: ScreenState) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface)
            .border(1.dp, Line, RoundedCornerShape(22.dp))
    ) {
        val artworkWidth = (maxWidth * .32f).coerceAtMost(140.dp)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(
                Modifier.weight(1f).heightIn(min = 175.dp).padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ДЕМО", color = Mint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, letterSpacing = 1.5.sp)
                Column {
                    Text(state.title, color = White, fontSize = 26.sp, lineHeight = 30.sp,
                        letterSpacing = (-.8).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Caption("${state.bpm} BPM  ·  ${state.durationMs / 1000} с")
                }
            }
            Box(Modifier.width(artworkWidth).fillMaxHeight()) {
                Image(painterResource(Res.drawable.first_rhythm), contentDescription = null,
                    modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Surface, .38f to Color.Transparent
                )))
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
private fun ResultScreen(state: ScreenState, actions: Actions) {
    val stats = state.stats ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Mark()
        Heading("Результат")
        Text(state.title, color = Muted, fontSize = 17.sp)
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Caption(if (state.newRecord) "Новый рекорд" else "Счёт", if (state.newRecord) Mint else Muted)
            Text(number(stats.score), color = Amber, fontSize = 46.sp, lineHeight = 53.sp, fontWeight = FontWeight.Black)
            val tenths = (stats.accuracy * 1000).roundToInt()
            Caption("Точность ${tenths / 10},${tenths % 10} %")
        }
        Column {
            ResultRow("Идеально", stats.perfect.toString(), Mint)
            ResultRow("Хорошо", stats.good.toString())
            ResultRow("Попал", stats.okay.toString())
            ResultRow("Пропущено", stats.missed.toString())
            ResultRow("Лишние нажатия", stats.stray.toString())
            Rule()
            ResultRow("Лучшее комбо", "×${stats.maxCombo}", Amber)
        }
        ActionButton("Ещё раз", actions::restart, icon = IconShape.Play)
        ActionButton("В меню", actions::menu, primary = false)
    }
}

@Composable
private fun ErrorScreen(state: ScreenState, actions: Actions) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Mark()
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

@Composable
private fun Mark() {
    Canvas(Modifier.size(width = 34.dp, height = 28.dp)) {
        listOf(.28f, .65f, 1f, .52f, .3f).forEachIndexed { i, h ->
            val x = size.width * (.08f + .21f * i)
            drawLine(if (i == 2) Amber else Mint,
                Offset(x, size.height * (.5f - .36f * h)), Offset(x, size.height * (.5f + .36f * h)),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

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
    primary: Boolean = true, icon: IconShape? = null
) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (primary) DarkText else White
    Row(modifier.fillMaxWidth().clip(shape).background(if (primary) Amber else Surface)
        .border(1.dp, if (primary) Amber else Line, shape).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 50.dp).padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Symbol(icon, fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
