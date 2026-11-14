package music.pinch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import music.pinch.GameMedia
import music.pinch.Scene
import music.pinch.ScreenState

@Composable
internal fun PerformanceScreen(state: ScreenState, actions: Actions, media: GameMedia?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep the media view at the same composition location in every session state.
        // Only the controls below it change. Native video surfaces can sit above Compose overlays.
        val padHeight = (maxHeight * .18f).coerceIn(94.dp, 148.dp)
        val panelLimit = maxHeight * .53f
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SessionHeader(state, actions)
            val progress = (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                Spacer(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Amber))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xff091221)),
                contentAlignment = Alignment.Center) {
                key(media) {
                    media?.Video(Modifier.aspectRatio(9f / 16f))
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state.scene) {
                Scene.Loading -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Mint, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Подготовка трека…", color = White, modifier = Modifier.weight(1f))
                        TextButton(onClick = actions::menu) { Text("В меню", color = Muted) }
                    }
                }
                Scene.Paused -> {
                    Column(Modifier.fillMaxWidth().heightIn(max = panelLimit).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Caption("Пауза", Mint)
                        ActionButton("Продолжить", actions::resume, icon = IconShape.Play)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("Начать заново", actions::restart, Modifier.weight(1f), primary = false)
                            ActionButton("В меню", actions::menu, Modifier.weight(1f), primary = false)
                        }
                    }
                }
                Scene.Resuming -> {
                    Row(Modifier.fillMaxWidth().heightIn(min = padHeight), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Text("Продолжаем через ", color = Muted, fontSize = 18.sp)
                        Text(state.countdown?.toString() ?: "…", color = Amber, fontSize = 32.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Scene.Playing -> {
                    if (state.preview) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Эталонное исполнение", color = White, fontWeight = FontWeight.Bold)
                                Caption("Слушай мелодию и следи за шариком.")
                            }
                            TextButton(onClick = actions::menu) { Text("В меню", color = Mint) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Caption("Комбо  ×${state.stats?.combo ?: 0}")
                            Caption("Счёт  ${number(state.stats?.score ?: 0)}")
                        }
                        TouchPad(state, actions, Modifier.fillMaxWidth().height(padHeight))
                    }
                }
                else -> Unit
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SessionHeader(state: ScreenState, actions: Actions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            if (state.preview) Caption("Прослушивание", Mint)
            Text(state.title, color = White, fontSize = 20.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val seconds = state.positionMs.coerceIn(0L, state.durationMs) / 1000L
        val end = state.durationMs / 1000L
        Text("${clock(seconds)} / ${clock(end)}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (state.scene == Scene.Playing || state.scene == Scene.Resuming) {
            IconButton(onClick = actions::pause, modifier = Modifier.semantics { contentDescription = "Пауза" }) {
                Symbol(IconShape.Pause, White)
            }
        }
    }
}

private fun clock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun TouchPad(state: ScreenState, actions: Actions, modifier: Modifier) {
    val latestActions by rememberUpdatedState(actions)
    val tail = state.positionMs > state.lastHitWindowMs
    val hit = when (state.reaction) {
        "Идеально", "Хорошо", "Попал" -> true
        else -> false
    }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state.reactionId) {
        if (state.reaction != null) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(190))
        }
    }
    val accent = when {
        tail -> Muted
        state.reaction == null -> Amber
        hit -> Mint
        else -> Color(0xffff9b90)
    }
    val shape = RoundedCornerShape(18.dp)
    val padTitle = when {
        tail -> "Конец мелодии"
        state.reaction != null -> state.reaction
        state.countdown != null -> state.countdown.toString()
        else -> "В такт"
    }
    // Leave the touch detector mounted across count-in, reaction and score changes.
    Row(modifier.clip(shape).background(lerp(Surface, accent, pulse.value * .22f))
        .border(1.dp, accent.copy(alpha = if (state.reaction == null) .5f else 1f), shape)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!tail) onClick(label = "Сыграть ноту") { latestActions.tapNow(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = true)
                first.consume()
                latestActions.tap(first.uptimeMillis)
                // No repeat on hold or extra fingers; re-arm only when every finger is up.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { if (it.pressed) it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(padTitle, color = accent, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp)
            if (!tail) Caption("Нажимай, когда шарик касается клавиши")
        }
        if (!tail) {
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.size(34.dp)) {
                drawCircle(accent.copy(alpha = .4f), size.minDimension * .47f, style = Stroke(1.dp.toPx()))
                drawCircle(accent, size.minDimension * .26f, style = Stroke(1.5.dp.toPx()))
                drawCircle(accent, size.minDimension * .09f, center = Offset(size.width / 2, size.height / 2))
            }
        }
    }
}
