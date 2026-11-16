package music.pinch.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import music.pinch.*

@Composable
internal fun TapReferenceScreen(state: ScreenState, actions: Actions, positionMs: () -> Long) {
    val ref = state.reference ?: return
    val duration = ref.durationMs
    var fromMs by rememberSaveable(ref.version) { mutableLongStateOf(0L) }
    var selected by rememberSaveable(ref.takes.size) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Сверить ритм", color = StudioIvory, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Caption(state.title)
            }
            StudioIconButton(StudioIcon.Close, "В меню", actions::menu)
        }
        if (!ref.recording) {
            if (state.canChooseReference) {
                val gameLabel = "Игровая инструментальная версия"
                Text(if (ref.usingFile) ref.fileName.orEmpty() else gameLabel, color = StudioIvory,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (ref.fileName != null) {
                        TextButton(enabled = !ref.choosing, onClick = { actions.useReferenceRecording(!ref.usingFile) }) {
                            Text(if (ref.usingFile) "К игровой версии" else "К своей записи", color = StudioMint)
                        }
                    }
                    TextButton(enabled = !ref.choosing, onClick = actions::chooseReferenceRecording) {
                        Text(if (ref.choosing) "Открываем файл…" else if (ref.fileName == null) "Выбрать свою версию" else "Другой файл",
                            color = StudioMint)
                    }
                }
                if (ref.usingFile) {
                    var confirming by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirming = true }, enabled = !ref.choosing) { Text("Убрать копию записи", color = Muted) }
                    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
                        title = { Text("Убрать копию записи?") },
                        text = { Text("Исходный файл и сохранённые замеры останутся.") },
                        confirmButton = { TextButton(onClick = { confirming = false; actions.removeReferenceRecording() }) { Text("Убрать") } },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Отмена") } })
                }
                Rule()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Начать с ${clock(fromMs / 1000)}", color = StudioIvory, modifier = Modifier.weight(1f))
                TextButton(onClick = { fromMs = (duration - 30_000).coerceAtLeast(0) }, enabled = !ref.choosing) {
                    Text("Финал", color = StudioMint)
                }
            }
            Slider(value = fromMs.toFloat(), onValueChange = { fromMs = it.toLong().coerceIn(0, duration - 1) },
                enabled = !ref.choosing, valueRange = 0f..(duration - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.semantics { contentDescription = "Начало фрагмента" })
            ActionButton("Записать отметки", { actions.recordReference(fromMs) }, enabled = !ref.choosing)
            Caption("Нажимай в такт тем звукам, на которые должен приземляться шарик.")
        } else {
            val elapsed by remember(positionMs) { derivedStateOf { positionMs() / 1000L } }
            Text("${clock(elapsed)} / ${durationClock(duration)}", color = StudioIvory, fontSize = 21.sp)
            val enabled = ref.listening
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp))
                .background(if (enabled) StudioMint else Surface)
                .semantics {
                    role = Role.Button
                    contentDescription = "Отметить удар"
                    if (enabled) onClick { actions.markReference(null); true } else disabled()
                }.pointerInput(enabled, actions) {
                    if (enabled) awaitPointerEventScope {
                        while (true) {
                            for (down in awaitPointerEvent().changes) {
                                if (down.changedToDown()) {
                                    down.consume()
                                    actions.markReference(down.uptimeMillis)
                                }
                            }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                Text(if (enabled) "В такт · ${ref.count}" else "Готовим звук…",
                    color = if (enabled) DarkText else Muted, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            ActionButton("Завершить", actions::stopReference, primary = false)
        }
        ref.message?.let { Caption(it, StudioCoral) }
        if (!ref.recording && ref.takes.isNotEmpty()) {
            Rule()
            var expanded by remember { mutableStateOf(false) }
            val take = ref.takes.getOrNull(selected) ?: ref.takes.first()
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text("Замер ${ref.takes.size - selected} · ${timeOf(take.startMs)} — ${timeOf(take.endMs)}  ▾", color = StudioMint)
                }
                DropdownMenu(expanded, { expanded = false }) {
                    ref.takes.forEachIndexed { i, item ->
                        DropdownMenuItem(text = { Text("№${ref.takes.size - i} · ${timeOf(item.startMs)}–${timeOf(item.endMs)} · ${item.sourceName}") },
                            onClick = { selected = i; expanded = false })
                    }
                }
            }
            key(take) { ReferenceResult(state, actions, take) }
        }
    }
}

@Composable
private fun ReferenceResult(state: ScreenState, actions: Actions, take: TapTake) {
    val ref = requireNotNull(state.reference)
    var offsetText by rememberSaveable { mutableStateOf(take.offsetMs.toString()) }
    val offset = offsetText.toLongOrNull()?.takeIf { it in -state.durationMs..take.endMs }
    var editing by rememberSaveable { mutableStateOf(false) }
    val panels = remember(state.chart) { state.chart?.notes?.map { it.atMs }.orEmpty() }
    val comparison = remember(take, panels, offset) { compareTaps(take, panels, offset ?: take.offsetMs) }
    Caption(take.sourceName)
    ResultRow("Отметки рядом с плиткой", "${comparison.matched.size} / ${take.taps.size}")
    val delta = comparison.medianMs
    if (delta != null) Caption(when {
        delta > 0 -> "Обычно нажатие на $delta мс позже плитки."
        delta < 0 -> "Обычно нажатие на ${-delta} мс раньше плитки."
        else -> "Медианное расхождение — 0 мс."
    })
    if (panels.isEmpty()) Caption("У выбранного трека пока нет готовой карты.")
    val missed = comparison.tapsOnly
    val extra = comparison.panelsOnly
    if (missed.isNotEmpty()) Caption("Отметки без плитки (${missed.size}) · ${times(missed)}")
    if (extra.isNotEmpty()) Caption("Плитки без отметки (${extra.size}) · ${times(extra)}")
    val repeated = remember(ref.takes, take, panels, offset) {
        repeatedTapDifferences(take, ref.takes, panels, comparison, offset ?: take.offsetMs)
    }
    if (repeated != null) {
        if (repeated.tapsWithoutPanels.isNotEmpty()) Caption("В двух замерах есть удар без плитки · ${times(repeated.tapsWithoutPanels)}", StudioMint)
        if (repeated.panelsWithoutTaps.isNotEmpty()) Caption("В двух замерах пропущены плитки · ${times(repeated.panelsWithoutTaps)}", StudioMint)
    }
    Caption("Окно сравнения ±$TAP_TOLERANCE_MS мс.")
    TextButton(onClick = { editing = !editing }) {
        Text(if (editing) "Свернуть настройку" else "Сдвиг карты: ${offset ?: take.offsetMs} мс", color = StudioMint)
    }
    if (editing) {
        OutlinedTextField(value = offsetText, onValueChange = { offsetText = it }, singleLine = true,
            label = { Text("Сдвиг карты, мс") }, isError = offset == null, modifier = Modifier.fillMaxWidth())
        Caption("Если вступления версий различаются, укажи разницу. Положительное число сдвигает плитки позже.")
        TextButton(enabled = offset != null && offset != take.offsetMs,
            onClick = { offset?.let { actions.adjustReference(take, it) } }) { Text("Сохранить сдвиг", color = StudioMint) }
    }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(take, offset) { mutableStateOf(false) }
    ActionButton(if (copied) "Скопировано" else "Скопировать все отметки", {
        clipboard.setText(AnnotatedString(tapReport(state.selectedSong, take, ref.chartVersion, comparison, offset ?: take.offsetMs)))
        copied = true
    }, primary = false, enabled = offset != null)
}

private fun timeOf(ms: Long) = "${clock(ms / 1000)}.${(ms % 1000).toString().padStart(3, '0')}"
private fun times(ms: List<Long>): String = ms.take(5).joinToString(" · ", transform = ::timeOf) +
    if (ms.size > 5) " …" else ""
