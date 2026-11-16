package music.pinch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import music.pinch.Actions
import music.pinch.ScreenState
import music.pinch.Song
import kotlin.math.roundToInt

@Composable
internal fun StudioDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 15.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(20.dp)
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight).clip(shape)
                .background(StudioGraphite).border(1.dp, Line, shape).padding(horizontal = 17.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = StudioIvory, fontSize = 25.sp, lineHeight = 30.sp,
                        letterSpacing = (-.65).sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    StudioIconButton(StudioIcon.Close, "Закрыть", dismiss)
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

private enum class CatalogTab(val label: String) { All("Все"), Favorites("Избранное"), Personal("Мои") }

@Composable
internal fun StudioCatalog(state: ScreenState, actions: Actions, dismiss: () -> Unit, select: (Song) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(CatalogTab.All) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val songs = remember(state.songs, state.favoriteIds, state.importedIds, state.availableIds, tab) {
        when (tab) {
            CatalogTab.All -> state.songs.sortedByDescending { it.id in state.availableIds }
            CatalogTab.Favorites -> state.songs.filter { it.id in state.favoriteIds }
            CatalogTab.Personal -> state.songs.filter { it.id in state.importedIds }.asReversed()
        }
    }
    val target = state.songs.find { it.id == deleting }
    if (target != null) {
        StudioDialog("Удалить запись?", { deleting = null }) {
            Text("«${target.title}» исчезнет из каталога. Исходный файл останется на устройстве.",
                color = StudioIvory, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            ActionButton("Удалить", { actions.removeImport(target.id); deleting = null })
            Spacer(Modifier.height(8.dp))
            ActionButton("Отмена", { deleting = null }, primary = false)
        }
        return
    }
    StudioDialog("Каталог", dismiss) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in CatalogTab.entries) {
                val active = tab == item
                Box(Modifier.weight(if (item == CatalogTab.Favorites) 1.4f else 1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Surface else StudioGraphite)
                    .selectable(active, role = Role.Tab, onClick = { tab = item })
                    .heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text(item.label, color = if (active) StudioMint else Muted, fontSize = 14.sp,
                        textAlign = TextAlign.Center, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (songs.isEmpty()) {
            Text(if (tab == CatalogTab.Favorites) "Сохраняй песни сердечком в каталоге." else "Здесь будут твои записи.",
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
        } else key(tab) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
                items(songs, key = { it.id }) { song ->
                    val active = song.id == state.selectedSong.id
                    val favorite = song.id in state.favoriteIds
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (active) Surface else StudioGraphite), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).selectable(active, role = Role.RadioButton, onClick = { select(song) })
                            .heightIn(min = 64.dp).padding(start = 5.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RecordSleeve(stageTheme(song.id), Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)), thumbnail = true)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = if (active) StudioMint else StudioIvory, fontSize = 16.sp,
                                    lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val detail = when {
                                    song.id in state.availableIds -> song.artist
                                    song.id in state.importedIds -> "Нужен исходный файл"
                                    else -> "Фонограмма готовится"
                                }
                                if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp,
                                    lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (tab != CatalogTab.Personal) song.bundledDurationMs?.let {
                                Text(durationClock(it), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 3.dp))
                            }
                        }
                        Box(Modifier.size(44.dp, 48.dp).clip(RoundedCornerShape(8.dp))
                            .toggleable(favorite, role = Role.Checkbox, onValueChange = { actions.toggleFavorite(song.id) })
                            .semantics { contentDescription = (if (favorite) "Из избранного: " else "В избранное: ") + song.title },
                            contentAlignment = Alignment.Center) {
                            StudioGlyph(if (favorite) StudioIcon.FilledHeart else StudioIcon.Heart,
                                if (favorite) StudioCoral else Muted, Modifier.size(19.dp))
                        }
                        if (tab == CatalogTab.Personal) {
                            Box(Modifier.size(44.dp, 48.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { deleting = song.id }
                                .semantics { contentDescription = "Удалить запись: " + song.title }, contentAlignment = Alignment.Center) {
                                StudioGlyph(StudioIcon.Remove, Muted, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        if (tab == CatalogTab.Personal) {
            Spacer(Modifier.height(8.dp))
            ActionButton("Добавить запись", { dismiss(); actions.importAudio() }, primary = false)
        }
    }
}

@Composable
internal fun StudioSettings(state: ScreenState, actions: Actions, dismiss: () -> Unit) {
    StudioDialog("Настройки", dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var value by remember(state.volume) { mutableFloatStateOf(state.volume) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Громкость", color = StudioIvory, modifier = Modifier.weight(1f))
                    Text("${(value * 100).roundToInt()} %", color = StudioCoral, fontFamily = FontFamily.Monospace)
                }
                Slider(value = value, onValueChange = { value = it },
                    onValueChangeFinished = { if (state.volume != value) actions.volume(value) },
                    modifier = Modifier.semantics { contentDescription = "Громкость" })
            }
            Rule()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Тактильный отклик", color = StudioIvory, modifier = Modifier.weight(1f))
                Switch(checked = state.haptics, onCheckedChange = actions::haptics,
                    modifier = Modifier.semantics { contentDescription = "Тактильный отклик" })
            }
            ActionButton("Готово", dismiss)
        }
    }
}

@Composable
internal fun StudioProfile(state: ScreenState, dismiss: () -> Unit) {
    val profile = state.profile
    StudioDialog("Мой уровень", dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(67.dp).background(Surface, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    StudioGlyph(StudioIcon.Medal, StudioMint, Modifier.size(41.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Уровень ${profile.level}", color = StudioIvory, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                    Text("Всего ${profile.totalXp} XP", color = Muted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(17.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("До уровня ${profile.level + 1}", color = Muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Text("${profile.xpInLevel} / ${profile.xpForNextLevel} XP", color = StudioMint,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(9.dp))
            XpTrack(profile.progress, Modifier.fillMaxWidth().height(5.dp))
            Spacer(Modifier.height(14.dp))
            Caption("Попадания и серии приносят опыт.")
            Spacer(Modifier.height(18.dp))
            Text("Пламя серии", color = StudioIvory, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlameLevel(1, 0, profile.flameTier, Modifier.weight(1f))
                FlameLevel(3, 1, profile.flameTier, Modifier.weight(1f))
                FlameLevel(6, 2, profile.flameTier, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlameLevel(level: Int, tier: Int, activeTier: Int, modifier: Modifier) {
    val active = tier == activeTier
    val locked = tier > activeTier
    val status = when { active -> "активно"; locked -> "закрыто"; else -> "открыто" }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.background(Surface, shape).border(1.dp, if (active) StudioMint else Line, shape)
        .clearAndSetSemantics { contentDescription = "Пламя серии, уровень $level, $status" }
        .padding(horizontal = 5.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StreakFlame(combo = 8, tier = tier, positionMs = { 0L },
                modifier = Modifier.size(30.dp, 35.dp).alpha(if (locked) .65f else 1f))
            Spacer(Modifier.height(5.dp))
            Text("Ур. $level", color = if (active) StudioMint else Muted, fontSize = 12.sp,
                lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (active || locked) StudioGlyph(if (locked) StudioIcon.Lock else StudioIcon.Check,
            if (locked) Muted else StudioMint, Modifier.align(Alignment.TopEnd).size(12.dp))
    }
}
