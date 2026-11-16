package music.pinch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

@Composable
internal fun StudioCatalog(state: ScreenState, dismiss: () -> Unit, select: (Song) -> Unit) {
    val songs = remember(state.songs) { state.songs.sortedByDescending { it.backingAsset != null } }
    StudioDialog("Каталог", dismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
            items(songs, key = { it.id }) { song ->
                val active = song.id == state.selectedSong.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (active) Surface else StudioGraphite)
                    .selectable(active, role = Role.RadioButton, onClick = { select(song) })
                    .heightIn(min = 64.dp).padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RecordSleeve(stageTheme(song.id), Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)), thumbnail = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = if (active) StudioMint else StudioIvory, fontSize = 16.sp,
                            lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                        if (song.artist.isNotBlank()) Text(song.artist, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    val status = when {
                        song.id !in state.availableIds -> "Импорт"
                        song.id in state.importedIds -> "Своя запись"
                        else -> song.bundledDurationMs?.let(::durationClock)
                    }
                    if (status != null) Text(status, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (active) {
                        Spacer(Modifier.width(5.dp))
                        StudioGlyph(StudioIcon.Check, StudioMint, Modifier.size(16.dp))
                    }
                }
            }
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
