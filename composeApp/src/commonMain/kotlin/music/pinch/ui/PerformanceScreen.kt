package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import music.pinch.Actions
import music.pinch.Scene
import music.pinch.ScreenState
import music.pinch.stereoLoveSectionAt

private val HeaderShade = Brush.verticalGradient(listOf(Color(0xe3141220), Color(0x00141220)))
private val FooterShade = Brush.verticalGradient(listOf(Color(0x00141220), Color(0xdf141220)))

@Composable
internal fun PerformanceScreen(state: ScreenState, actions: Actions, positionMs: () -> Long, stage: PreparedStage?) {
    Box(Modifier.fillMaxSize()) {
        val countingIn = state.scene == Scene.CountIn
        val live = state.scene == Scene.Playing || countingIn
        val theme = stageTheme(state.selectedSong.id)
        val accent = stageColor(theme.accent)
        if (stage != null) {
            key(state.attempt) {
                val attempt = state.attempt
                val presented = remember(actions, attempt) { { actions.stagePresented(attempt) } }
                when (stage) {
                    is PreparedStage.Orbit -> MarbleStage(stage.path, positionMs, Modifier.matchParentSize(),
                        feedback = state.panelFeedback, theme = theme, onPresented = presented)
                    is PreparedStage.Cascade -> PlanarStage(stage, positionMs, Modifier.matchParentSize(),
                        animate = live, feedback = state.panelFeedback, theme = theme, onPresented = presented)
                }
            }
            if (live && !state.preview) Box(Modifier.matchParentSize().rhythmInput(actions))
        }
        if (live) {
            SessionHeader(state, actions, positionMs, accent)
            if (countingIn) Box(Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 68.dp)) {
                Countdown(state.countdown, "Начинаем")
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(FooterShade)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 18.dp, end = 14.dp, top = 22.dp, bottom = 10.dp)) {
                if (state.preview) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Демонстрация · шарик играет сам", modifier = Modifier.weight(1f), color = White,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = actions::menu) { Text("В меню", color = White) }
                    }
                } else {
                    PlayControls(state, positionMs, accent)
                }
            }
        } else {
            Box(Modifier.matchParentSize().background(Ink.copy(alpha = if (state.scene == Scene.Paused) .72f else .42f))
                .safeDrawingPadding(), contentAlignment = Alignment.Center) {
                when (state.scene) {
                    Scene.Paused -> PauseSheet(actions)
                    Scene.Resuming -> {
                        Countdown(state.countdown, "Продолжаем")
                        TextButton(onClick = actions::pause, modifier = Modifier.align(Alignment.TopEnd)) {
                            Text("Пауза", color = White)
                        }
                    }
                    Scene.Loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(26.dp), color = Lilac, strokeWidth = 2.dp)
                        Caption("Готовим мелодию", White)
                        TextButton(onClick = actions::menu) { Text("В меню", color = White) }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun Modifier.rhythmInput(actions: Actions): Modifier =
    semantics {
        role = Role.Button
        contentDescription = "Игровая сцена"
        onClick(label = "Ударить по планке") { actions.tapNow(); true }
    }.pointerInput(actions) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) {
                    if (change.changedToDown()) {
                        change.consume()
                        actions.tap(change.uptimeMillis)
                    }
                }
            }
        }
    }

@Composable
private fun SessionHeader(state: ScreenState, actions: Actions, positionMs: () -> Long, accent: Color) {
    Column(Modifier.fillMaxWidth().background(HeaderShade)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        .padding(start = 18.dp, end = 14.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.title, color = White, fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.backingAsset != null && state.selectedSong.id == "stereo-love") {
                    val section by remember(positionMs) { derivedStateOf { stereoLoveSectionAt(positionMs()) } }
                    Text(section, color = accent, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1)
                } else if (state.recordedAsset != null && state.backingAsset == null) {
                    Text("${state.instrument.label} · соло", color = accent, fontSize = 12.sp,
                        lineHeight = 15.sp, maxLines = 1)
                }
            }
            SongTime(state.durationMs, positionMs)
            IconButton(onClick = actions::pause, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "Пауза" }) {
                Box(Modifier.size(34.dp).background(Ink.copy(alpha = .54f), CircleShape), contentAlignment = Alignment.Center) {
                    Symbol(IconShape.Pause, White)
                }
            }
        }
        val duration = state.durationMs.coerceAtLeast(1L)
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val ratio = (positionMs().toFloat() / duration).coerceIn(0f, 1f)
            drawRect(White.copy(alpha = .18f))
            drawRect(accent, size = Size(size.width * ratio, size.height))
        }
    }
}

@Composable
private fun SongTime(durationMs: Long, positionMs: () -> Long) {
    val seconds by remember(positionMs, durationMs) {
        derivedStateOf { positionMs().coerceIn(0L, durationMs) / 1_000L }
    }
    Text("${clock(seconds)} / ${durationClock(durationMs)}", color = White,
        fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
}

internal fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun durationClock(durationMs: Long): String = clock((durationMs + 500L) / 1000L)

@Composable
private fun Countdown(count: Int?, label: String) {
    Row(Modifier.background(Ink.copy(alpha = .88f), RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(label, White)
        Text(count?.toString() ?: "…", fontFamily = FontFamily.Monospace, color = Lilac,
            fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun PauseSheet(actions: Actions) {
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(.86f).verticalScroll(rememberScrollState())
        .background(Ink, RoundedCornerShape(22.dp)).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Heading("Пауза")
        ActionButton("Продолжить", actions::resume, icon = IconShape.Play)
        ActionButton("Начать заново", actions::restart, primary = false)
        TextButton(onClick = actions::menu, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("В меню", color = Muted)
        }
    }
}

@Composable
private fun PlayControls(state: ScreenState, positionMs: () -> Long, accent: Color) {
    val lastWindow = state.lastHitWindowMs
    val tail by remember(positionMs, lastWindow) { derivedStateOf { positionMs() > lastWindow } }
    val reaction = state.reaction
    val text = when {
        reaction != null -> reaction
        state.countdown != null && state.scene != Scene.CountIn -> "Первое приземление · ${state.countdown}"
        tail -> "Конец мелодии"
        else -> "Касайся экрана при каждом приземлении"
    }
    val color = when {
        reaction == null -> White
        reaction == "Пропуск" || reaction == "Мимо" -> stageColor(0xFF7878)
        else -> Mint
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, modifier = Modifier.weight(1f), color = color, fontSize = 15.sp,
            lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text("Счёт  ${number(state.stats?.score ?: 0)}", color = White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Комбо  ×${state.stats?.combo ?: 0}", color = accent, fontSize = 12.sp)
        }
    }
}
