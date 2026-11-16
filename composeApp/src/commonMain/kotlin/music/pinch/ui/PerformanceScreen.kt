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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import music.pinch.resources.Res
import music.pinch.resources.studio_marble
import org.jetbrains.compose.resources.imageResource
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
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


private val HeaderShade = Brush.verticalGradient(listOf(Color(0xe6151a1b), Color(0xbb151a1b), Color(0x00151a1b)))

@Composable
internal fun PerformanceScreen(state: ScreenState, actions: Actions, positionMs: () -> Long, stage: PreparedStage?,
    orbitAtlas: Result<ImageBitmap>?,
) {
    Box(Modifier.fillMaxSize()) {
        val countingIn = state.scene == Scene.CountIn
        val live = state.scene == Scene.Playing || countingIn
        val theme = stageTheme(state.selectedSong.id)
        val interactive = live && !state.preview
        val feedback = when {
            !interactive -> emptyList()
            countingIn -> remember(state.panelFeedback) { state.panelFeedback.map { it.copy(effectsVisible = false) } }
            else -> state.panelFeedback
        }
        if (stage != null) {
            key(state.attempt) {
                val attempt = state.attempt
                val presented = remember(actions, attempt) { { actions.stagePresented(attempt) } }
                when (stage) {
                    is PreparedStage.Orbit -> if (orbitAtlas != null) {
                        val atlas = orbitAtlas.getOrNull() ?: imageResource(Res.drawable.studio_marble)
                        MarbleStage(stage.meshes, positionMs, Modifier.matchParentSize(), feedback = feedback, showWindow = interactive,
                            theme = theme, atlas = atlas, onPresented = presented)
                    }
                    is PreparedStage.Cascade -> PlanarStage(stage, positionMs, Modifier.matchParentSize(),
                        animate = live, feedback = feedback, showWindow = interactive, theme = theme, onPresented = presented)
                }
            }
            if (interactive) Box(Modifier.matchParentSize().rhythmInput(actions))
        }
        if (live) {
            SessionHeader(state, actions, positionMs)
            if (countingIn) Box(Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 77.dp)) {
                Countdown(state.countdown, "Начало")
            }
            if (!state.preview && state.scene == Scene.Playing) {
                Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 10.dp)) {
                    ScoreStrip(state, positionMs)
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
                        CircularProgressIndicator(Modifier.size(26.dp), color = StudioCoral, strokeWidth = 2.dp)
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
private fun SessionHeader(state: ScreenState, actions: Actions, positionMs: () -> Long) {
    Column(Modifier.fillMaxWidth().background(HeaderShade)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        .padding(start = 20.dp, end = 14.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.title, color = StudioIvory, fontSize = 20.sp, lineHeight = 23.sp,
                    letterSpacing = (-.5).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val duration = state.durationMs
                val seconds by remember(positionMs, duration) {
                    derivedStateOf { positionMs().coerceIn(0L, duration) / 1_000L }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${clock(seconds)} / ${durationClock(duration)}", color = StudioIvory.copy(alpha = .84f),
                        fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    if (state.preview) Text("Прослушивание", color = StudioIvory.copy(alpha = .74f),
                        fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.preview) {
                IconButton(onClick = actions::menu, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "В меню" }) {
                    Canvas(Modifier.size(18.dp)) {
                        drawLine(StudioIvory, Offset(2f,2f), Offset(size.width-2f,size.height-2f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(StudioIvory, Offset(size.width-2f,2f), Offset(2f,size.height-2f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            IconButton(onClick = actions::pause, modifier = Modifier.size(48.dp).semantics { contentDescription = "Пауза" }) {
                Box(Modifier.size(38.dp).background(StudioGraphite.copy(alpha = .75f), CircleShape),
                    contentAlignment = Alignment.Center) { Symbol(IconShape.Pause, StudioIvory) }
            }
        }
        Canvas(Modifier.fillMaxWidth().padding(end = 5.dp).height(2.dp).graphicsLayer()) {
            val ratio = (positionMs().toFloat() / state.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            drawRect(StudioIvory.copy(alpha = .27f))
            drawRect(StudioCoral, size = Size(size.width * ratio, size.height))
        }
    }
}

internal fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun durationClock(durationMs: Long): String = clock((durationMs + 500L) / 1000L)

@Composable
private fun Countdown(count: Int?, label: String) {
    val value = count?.toString() ?: "…"
    Box(Modifier.size(57.dp).background(StudioGraphite.copy(alpha = .92f), CircleShape)
        .border(1.dp, StudioCoral, CircleShape).clearAndSetSemantics { contentDescription = "$label, $value" },
        contentAlignment = Alignment.Center) {
        Text(value, fontFamily = FontFamily.Monospace, color = StudioIvory, fontWeight = FontWeight.Bold, fontSize = 28.sp)
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
private fun ScoreStrip(state: ScreenState, positionMs: () -> Long) {
    val stats = state.stats ?: return
    val combo = stats.combo
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.background(StudioGraphite.copy(alpha = .94f), shape)
        .border(1.dp, StudioIvory.copy(alpha = .19f), shape)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.widthIn(min = 73.dp)) {
            Text("Счёт", color = StudioIvory.copy(alpha = .76f), fontSize = 10.sp, lineHeight = 12.sp)
            Text(number(stats.score), color = StudioIvory, fontSize = 20.sp, lineHeight = 22.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(23.dp).width(1.dp).background(StudioIvory.copy(alpha = .22f)))
        Row(Modifier.clearAndSetSemantics { contentDescription = "Серия $combo" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StreakFlame(combo, state.profile.flameTier, positionMs, Modifier.size(25.dp, 30.dp).graphicsLayer())
            Text("×$combo", modifier = Modifier.widthIn(min = 30.dp), color = if (combo >= 3) StudioCoral else StudioIvory, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}
