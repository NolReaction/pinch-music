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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import music.pinch.Actions
import music.pinch.Instrument
import music.pinch.Song
import music.pinch.Scene
import music.pinch.ScreenState
import music.pinch.StageMode
import kotlin.math.roundToInt

internal val Ink = Color(0xff11101a)
internal val Surface = Color(0xff1e1c2a)
internal val Line = Color(0xff373444)
internal val White = Color(0xfff6f3fd)
internal val Muted = Color(0xffb8b2ca)
internal val Lilac = Color(0xffd7b2ff)
internal val Coral = Color(0xffffa794)
internal val Mint = Color(0xff91e6c6)
internal val DarkText = Color(0xff291339)

private val TrackShade = Brush.horizontalGradient(0f to Surface, .43f to Surface,
    .74f to Surface.copy(alpha = .6f), 1f to Color.Transparent)
private val RecordingAndInstruments: List<Instrument?> = listOf(null) + Instrument.entries

private val AppColors = darkColorScheme(
    primary = Lilac, onPrimary = DarkText, secondary = Mint, background = Ink,
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
                    Scene.Menu -> Menu(state, actions)
                    Scene.Error -> ErrorScreen(state, actions)
                    Scene.Result -> ResultScreen(state, actions)
                    else -> PerformanceScreen(state, actions, positionMs, stage, orbitAtlas.value)
                }
            }
        }
    }
}

@Composable
private fun Menu(state: ScreenState, actions: Actions) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var library by rememberSaveable { mutableStateOf(false) }
    val songAccent = stageColor(stageTheme(state.selectedSong.id).accent)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Mark()
            Spacer(Modifier.width(9.dp))
            Text("В такт", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                letterSpacing = (-1.4).sp, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { settings = true }) { Text("Настройки", color = Muted) }
        }
        StageTabs(state.stageMode, songAccent, actions::selectStageMode)
        TrackCard(state)
        ActionButton("Выбрать мелодию", onClick = { library = true }, primary = false, enabled = !state.importing)
        if (state.recordedAsset != null) {
            Choices("Звучание", RecordingAndInstruments, state.instrument.takeIf { state.backingAsset == null },
                { it?.label ?: "Оригинал" }, { voice ->
                    if (voice == null) actions.selectRecording() else actions.selectInstrument(voice)
                }, columns = 2)
        } else if (state.hasScore) {
            Choices("Инструмент", Instrument.entries, state.instrument, { it.label }, actions::selectInstrument)
        }
        if (state.importing) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Lilac, strokeWidth = 2.dp)
                Text("Разбираем мелодию…", color = White)
            }
        } else if (state.hasScore) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Играть", onClick = { actions.start(false) }, modifier = Modifier.weight(1.1f),
                    icon = IconShape.Play, accent = songAccent)
                ActionButton("Послушать", onClick = { actions.start(true) }, modifier = Modifier.weight(1f), primary = false)
            }
            Caption("Касайся экрана при каждом приземлении шарика на панель. Пока он катится по рельсам, жди.")
            val imported = state.selectedSong.id in state.importedIds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = actions::importAudio) {
                    Text(if (imported) "Заменить запись" else "Выбрать свою запись", color = Muted)
                }
                if (imported && state.selectedSong.hasBundled) {
                    TextButton(onClick = actions::restoreBundled) { Text("Вернуть встроенную", color = Lilac) }
                }
            }
        } else {
            Caption("Выбери запись песни в MP3, M4A или WAV. Игра выделит основные ноты из короткого фрагмента.")
            ActionButton("Выбрать аудиофайл", actions::importAudio)
        }
        state.bestScore?.let {
            Rule()
            ResultRow("Рекорд", number(it), accent = Lilac)
        }
    }
    if (library) {
        SongPicker(state, onDismiss = { library = false }) {
            library = false
            if (it.id != state.selectedSong.id) actions.selectSong(it.id)
        }
    }
    state.notice?.let { notice ->
        AlertDialog(onDismissRequest = actions::dismissNotice, text = { Text(notice) },
            confirmButton = { TextButton(onClick = actions::dismissNotice) { Text("Понятно") } })
    }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Настройки") },
            text = {
                Column {
                    VolumeSlider(state.volume, actions::volume)
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
private fun VolumeSlider(volume: Float, save: (Float) -> Unit) {
    var value by remember(volume) { mutableFloatStateOf(volume) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Громкость")
        Text("${(value * 100).roundToInt()} %", color = Lilac)
    }
    Slider(value = value, onValueChange = { value = it },
        onValueChangeFinished = { if (value != volume) save(value) },
        modifier = Modifier.semantics { contentDescription = "Громкость" })
}

@Composable
private fun StageTabs(selected: StageMode, accent: Color, choose: (StageMode) -> Unit) {
    val track = RoundedCornerShape(16.dp)
    val modes = StageMode.entries
    val selectedPosition = animateFloatAsState(selected.ordinal.toFloat(), tween(160), label = "Сцена")
    Row(Modifier.fillMaxWidth().clip(track).background(Surface).border(1.dp, Line, track)
        .selectableGroup().padding(3.dp).drawBehind {
            val gap = 3.dp.toPx()
            val width = (size.width - gap * (modes.size - 1)) / modes.size
            val start = (width + gap) * selectedPosition.value
            val x = if (layoutDirection == LayoutDirection.Ltr) start else size.width - width - start
            drawRoundRect(accent.copy(alpha = .15f), Offset(x, 0f), Size(width, size.height),
                cornerRadius = CornerRadius(12.dp.toPx()))
        }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (mode in modes) {
            val active = selected == mode
            val tint = if (active) accent else Muted
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .selectable(active, role = Role.Tab, onClick = { if (!active) choose(mode) })
                .heightIn(min = 44.dp).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(22.dp, 20.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = 1.6.dp.toPx()
                    when (mode) {
                        StageMode.Orbit -> {
                            drawOval(tint, Offset(.06f*w, .29f*h), Size(.88f*w, .44f*h), style = Stroke(stroke))
                            drawCircle(tint, .12f*h, Offset(.70f*w, .31f*h))
                        }
                        StageMode.Planar -> {
                            drawLine(tint, Offset(.05f*w, .42f*h), Offset(.52f*w, .42f*h), stroke, StrokeCap.Round)
                            drawLine(tint, Offset(.52f*w, .77f*h), Offset(.96f*w, .77f*h), stroke, StrokeCap.Round)
                            drawCircle(tint, .11f*h, Offset(.35f*w, .22f*h))
                        }
                    }
                }
                Text(mode.label, color = tint, fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TrackCard(state: ScreenState) {
    val shape = RoundedCornerShape(20.dp)
    val song = state.selectedSong
    val imported = song.id in state.importedIds
    val theme = stageTheme(song.id)
    val accent = stageColor(theme.accent)
    Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(shape).background(Surface).border(1.dp, Line, shape)) {
        val source = when {
            !state.hasScore -> "Нужен аудиофайл"
            imported -> "Из записи"
            state.backingAsset != null -> "Полный инструментал"
            state.recordedAsset != null -> "Мелодия с ритм-секцией"
            else -> "Фрагмент"
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            StageCover(state.stageMode, theme, Modifier.matchParentSize())
            Spacer(Modifier.matchParentSize().background(TrackShade))
        }
        Column(Modifier.fillMaxWidth(.79f).heightIn(min = 150.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Caption(source, accent)
            Spacer(Modifier.height(14.dp))
            Column {
                Text(song.title, color = White, fontSize = 29.sp, lineHeight = 32.sp,
                    letterSpacing = (-.9).sp, fontWeight = FontWeight.Black)
                if (song.artist.isNotBlank()) Caption(song.artist, White.copy(alpha = .82f))
                if (state.durationMs > 0L) {
                    Spacer(Modifier.height(6.dp))
                    val bpm = if (imported) "≈ ${state.bpm}" else state.bpm.toString()
                    Text("$bpm BPM  ·  ${durationClock(state.durationMs)}",
                        fontSize = 12.sp, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StageCover(mode: StageMode, theme: StageTheme, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val w = size.width * .53f
        val h = size.height
        val unit = minOf(w, h)
        if (unit <= 0f) return@drawWithCache onDrawBehind {}
        val backdrop = StageBackdrop(size, theme)
        val accent = stageColor(theme.accent)
        val offset = size.width - w
        val ballRadius = .087f * unit
        val ball = when (mode) {
            StageMode.Planar -> Offset(.77f * w, .62f * h - ballRadius)
            StageMode.Orbit -> Offset(.66f * w, .54f * h - ballRadius)
        }
        val light = ball - Offset(.33f * ballRadius, .36f * ballRadius)
        val ballFill = Brush.radialGradient(listOf(White, Coral, Color(0xff8d1b4e), DarkText), light, 1.5f * ballRadius)
        when (mode) {
            StageMode.Planar -> {
                // Paired rails trace the shape of the cascade.
                val rails = List(2) { i ->
                    val shift = i * .055f
                    Path().apply {
                        moveTo(-.05f * w, (.23f + shift) * h)
                        cubicTo(.20f * w, (.18f + shift) * h, .41f * w, (.85f + shift) * h,
                            .77f * w, (.62f + shift) * h)
                        lineTo(1.05f * w, (.44f + shift) * h)
                    }
                }
                val outline = Stroke(.034f * unit)
                val highlight = Stroke(.012f * unit)
                onDrawBehind {
                    backdrop.draw(this)
                    translate(left = offset) {
                        for (rail in rails) {
                            drawPath(rail, Line, style = outline)
                            drawPath(rail, accent, style = highlight)
                        }
                        drawCircle(ballFill, ballRadius, ball)
                    }
                }
            }
            StageMode.Orbit -> {
                // Suspended circular pads recede towards the top of the scene.
                val rim = Stroke(.013f * unit)
                onDrawBehind {
                    backdrop.draw(this)
                    translate(left = offset) {
                        for (i in 0..3) {
                            val x = (.50f + .27f * i - .095f * i * i) * w
                            val y = (.14f + .20f * i) * h
                            val radius = (.065f + .037f * i) * unit
                            drawLine(Line, Offset(x, 0f), Offset(x, y), strokeWidth = .009f * unit)
                            val top = Offset(x - radius, y - .32f * radius)
                            val pad = Size(2f * radius, .64f * radius)
                            drawOval(accent.copy(alpha = .24f), top + Offset(0f, .23f * radius), pad)
                            drawOval(accent, top, pad, style = rim)
                        }
                        drawCircle(ballFill, ballRadius, ball)
                    }
                }
            }
        }
    })
}

@Composable
private fun <T> Choices(label: String, values: List<T>, selected: T, title: (T) -> String, choose: (T) -> Unit,
    columns: Int = values.size,
) {
    val rows = remember(values, columns) { values.chunked(columns) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label, White)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (value in row) {
                        val active = value == selected
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.weight(1f).clip(shape).background(if (active) Lilac.copy(alpha = .16f) else Surface)
                            .border(1.dp, if (active) Lilac.copy(alpha = .65f) else Line, shape)
                            .selectable(active, role = Role.RadioButton, onClick = { if (!active) choose(value) })
                            .heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center) {
                            Text(title(value), color = if (active) Lilac else Muted, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongPicker(state: ScreenState, onDismiss: () -> Unit, select: (Song) -> Unit) {
    val songs = remember(state.songs) { state.songs.sortedByDescending { it.backingAsset != null } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 550.dp)
            .clip(RoundedCornerShape(22.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Мелодии", color = White, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Закрыть", color = Muted) }
            }
            Rule()
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
                items(songs, key = { it.id }) { song ->
                    val active = song.id == state.selectedSong.id
                    val ready = song.id in state.availableIds
                    Row(Modifier.fillMaxWidth().selectable(active, role = Role.RadioButton, onClick = { select(song) })
                        .padding(horizontal = 4.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = if (active) Lilac else White, fontSize = 16.sp,
                                lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                            if (song.artist.isNotBlank()) Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        val status = when {
                            !ready -> "Импорт"
                            song.id in state.importedIds -> "Своя запись"
                            else -> song.bundledDurationMs?.let(::durationClock)
                        }
                        if (status != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(status, color = if (ready) Mint else Muted, fontSize = 11.sp,
                                fontWeight = FontWeight.Medium)
                        }
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            Canvas(Modifier.size(8.dp)) { drawCircle(Lilac) }
                        }
                    }
                    Rule()
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
private fun ResultScreen(state: ScreenState, actions: Actions) {
    val stats = state.stats ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Mark()
        Heading("Результат")
        Text(state.title, color = Muted, fontSize = 17.sp)
        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Caption(if (state.newRecord) "Новый рекорд" else "Счёт", if (state.newRecord) Mint else Muted)
            Text(number(stats.score), color = Lilac, fontSize = 46.sp, lineHeight = 53.sp, fontWeight = FontWeight.Black)
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
            ResultRow("Лучшее комбо", "×${stats.maxCombo}", Lilac)
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
            drawLine(if (i == 2) Lilac else Mint,
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
    primary: Boolean = true, icon: IconShape? = null, enabled: Boolean = true, accent: Color = Lilac,
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
