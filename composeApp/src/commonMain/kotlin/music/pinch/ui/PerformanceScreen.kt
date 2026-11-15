package music.pinch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import music.pinch.core.Chart
import music.pinch.stereoLoveSectionAt

@Composable
internal fun PerformanceScreen(state: ScreenState, actions: Actions, chart: Chart?) {
    Column(Modifier.fillMaxSize()) {
        SessionHeader(state, actions)
        val progress = (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(3.dp).background(Line)) {
            Spacer(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Lilac))
        }

        val interactive = state.scene == Scene.Playing && !state.preview
        val playable = if (interactive) Modifier.rhythmInput(actions) else Modifier
        Column(Modifier.fillMaxWidth().weight(1f).then(playable)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Surface)) {
                if (chart != null) MarbleStage(chart, state.positionMs, Modifier.matchParentSize())
                if (state.scene == Scene.Loading || state.scene == Scene.Resuming || state.scene == Scene.Paused) {
                    val dim = if (state.scene == Scene.Paused) .72f else .32f
                    Box(Modifier.matchParentSize().background(Ink.copy(alpha = dim)), contentAlignment = Alignment.Center) {
                        when (state.scene) {
                            Scene.Paused -> PauseSheet(actions)
                            Scene.Loading -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(26.dp), color = Lilac, strokeWidth = 2.dp)
                                Caption("Готовим мелодию", White)
                                TextButton(onClick = actions::menu) { Text("В меню", color = White) }
                            }
                            Scene.Resuming -> Countdown(state.countdown, "Продолжаем")
                            else -> Unit
                        }
                    }
                } else if (state.countdown != null && !state.preview) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp)) {
                        Countdown(state.countdown, "До старта")
                    }
                }
            }
            if (state.preview) PreviewControls(state, actions) else PlayControls(state)
        }
    }
}

private fun Modifier.rhythmInput(actions: Actions): Modifier =
    semantics {
        role = Role.Button
        onClick(label = "Сыграть ноту на панели") { actions.tapNow(); true }
    }.pointerInput(actions) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                // Each panel plays once per down event, including alternating fingers.
                val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                event.changes.forEach { if (it.changedToDown()) it.consume() }
                actions.tap(down.uptimeMillis)
            }
        }
    }

@Composable
private fun SessionHeader(state: ScreenState, actions: Actions) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp).heightIn(min = 62.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            val section = if (state.selectedSong.id == "stereo-love" && state.backingAsset != null)
                stereoLoveSectionAt(state.positionMs) else null
            val label = when {
                state.preview && section != null -> "Слушаем · $section"
                state.preview -> "Прослушивание"
                else -> section
            }
            if (label != null) Text(label, color = Lilac, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.title, color = White, fontSize = 19.sp, lineHeight = 22.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val seconds = state.positionMs.coerceIn(0L, state.durationMs) / 1000L
        Text("${clock(seconds)} / ${durationClock(state.durationMs)}", color = Muted,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (state.scene == Scene.Playing || state.scene == Scene.Resuming) {
            IconButton(onClick = actions::pause, modifier = Modifier.semantics { contentDescription = "Пауза" }) {
                Symbol(IconShape.Pause, White)
            }
        } else Spacer(Modifier.width(6.dp))
    }
}

internal fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal fun durationClock(durationMs: Long): String = clock((durationMs + 500L) / 1000L)

@Composable
private fun Countdown(count: Int?, label: String) {
    Row(Modifier.background(Ink.copy(alpha = .9f), RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
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
private fun PreviewControls(state: ScreenState, actions: Actions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Прослушивание", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (state.scene == Scene.Playing) Caption("Панели играют автоматически")
        }
        TextButton(onClick = actions::menu) { Text("В меню", color = Lilac) }
    }
}

@Composable
private fun PlayControls(state: ScreenState) {
    val tail = state.positionMs > state.lastHitWindowMs
    val hit = when (state.reaction) {
        "Идеально", "Хорошо", "Попал" -> true
        else -> false
    }
    val accent = when {
        tail -> Muted
        state.reaction == null -> Lilac
        hit -> Mint
        else -> Coral
    }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state.reactionId) {
        if (state.reaction != null) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(190))
        }
    }
    Column(Modifier.fillMaxWidth().background(lerp(Ink, accent, .1f * pulse.value)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("Комбо  ×${state.stats?.combo ?: 0}")
            Caption("Счёт  ${number(state.stats?.score ?: 0)}")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(29.dp)) {
                val radius = size.minDimension * .47f
                drawCircle(accent.copy(alpha = .5f), radius, style = Stroke(1.dp.toPx()))
                drawCircle(accent, radius * (.4f + .22f * pulse.value))
            }
            Column {
                Text(when {
                    tail -> "Конец мелодии"
                    state.reaction != null -> state.reaction
                    else -> "Нажимай в момент удара"
                }, color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                // Keep HUD height stable when a judgement replaces the hint. The world
                // projection uses the remaining space, so a collapsing footer would shift the ball.
                if (!tail) {
                    Caption(if (state.reaction != null && !hit) "Прозвучала фальшивая нота" else "Касайся любой точки сцены")
                } else Spacer(Modifier.height(20.dp))
            }
        }
    }
}
