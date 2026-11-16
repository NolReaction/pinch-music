package music.pinch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import music.pinch.Actions
import music.pinch.ScreenState
import music.pinch.StageMode

private enum class StudioPage { Catalog, Settings, Profile }
private val CardShape = RoundedCornerShape(15.dp)
private val ControlShape = RoundedCornerShape(12.dp)

@Composable
internal fun StudioMenu(state: ScreenState, actions: Actions) {
    var page by rememberSaveable { mutableStateOf<StudioPage?>(null) }
    val close = { page = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            BeatMark(Modifier.size(32.dp))
            Spacer(Modifier.width(7.dp))
            Text("В такт", color = StudioIvory, fontSize = 27.sp, lineHeight = 30.sp,
                letterSpacing = (-1.25).sp, fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f).semantics { heading() })
            LevelButton(state, onClick = { page = StudioPage.Profile })
            Spacer(Modifier.width(4.dp))
            StudioIconButton(StudioIcon.Settings, "Настройки", onClick = { page = StudioPage.Settings })
        }
        Spacer(Modifier.height(14.dp))
        SongHero(state, onClick = { page = StudioPage.Catalog })
        Spacer(Modifier.height(10.dp))
        when {
            state.importing -> Row(Modifier.fillMaxWidth().heightIn(min = 54.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = StudioCoral, strokeWidth = 2.dp)
                Text("Разбираем мелодию…", color = StudioIvory)
            }
            state.canPlay -> PlaybackActions(actions)
            state.needsSourceFile -> {
                ActionButton("Выбрать исходный файл", actions::restoreRecording, accent = StudioCoral)
                Spacer(Modifier.height(7.dp))
                Caption("Карта сохранена. Для игры нужна исходная запись.")
            }
            else -> Caption("Фонограмма готовится")
        }
        if (state.canMeasure && !state.importing) {
            androidx.compose.material3.TextButton(onClick = actions::openReference) {
                Text("Сверить ритм", color = StudioMint, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(21.dp))
        SectionLabel("Сцена")
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (mode in StageMode.entries) {
                StageChoice(mode.label, mode == state.stageMode, Modifier.weight(1f),
                    if (mode == StageMode.Orbit) StudioIcon.Orbit else StudioIcon.Cascade) {
                    if (mode != state.stageMode) actions.selectStageMode(mode)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Rule()
        if (!state.importing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(ControlShape).clickable(role = Role.Button, onClick = actions::importAudio)
                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    StudioGlyph(StudioIcon.Import, Muted, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать свою запись", color = Muted, fontSize = 13.sp)
                }
                state.bestScore?.let { Text("Рекорд  " + number(it), color = StudioMint, fontSize = 12.sp) }
            }
        }
    }
    when (page) {
        StudioPage.Catalog -> StudioCatalog(state, actions, close) { song ->
            close()
            if (song.id != state.selectedSong.id) actions.selectSong(song.id)
        }
        StudioPage.Settings -> StudioSettings(state, actions, close)
        StudioPage.Profile -> StudioProfile(state, close)
        null -> Unit
    }
    state.notice?.let { notice ->
        StudioDialog("Сообщение", actions::dismissNotice) {
            Text(notice, color = StudioIvory)
            Spacer(Modifier.height(16.dp))
            ActionButton("Понятно", actions::dismissNotice)
        }
    }
}

@Composable
private fun LevelButton(state: ScreenState, onClick: () -> Unit) {
    val profile = state.profile
    Row(Modifier.clip(ControlShape).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StudioGlyph(StudioIcon.Medal, StudioMint, Modifier.size(19.dp))
        Column {
            Text("Уровень ${profile.level}", color = StudioMint, fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            XpTrack(profile.progress, Modifier.width(53.dp).height(3.dp))
        }
    }
}

@Composable
internal fun XpTrack(progress: Float, modifier: Modifier) {
    Box(modifier.progressSemantics(progress).clip(CircleShape).background(Line)) {
        Spacer(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(StudioMint))
    }
}

@Composable
internal fun StudioIconButton(icon: StudioIcon, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).background(Surface, CircleShape).border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center) {
            StudioGlyph(icon, StudioIvory, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SongHero(state: ScreenState, onClick: () -> Unit) {
    val song = state.selectedSong
    Box(Modifier.fillMaxWidth().clip(CardShape)
        .clickable(enabled = !state.importing, role = Role.Button, onClickLabel = "Выбрать мелодию", onClick = onClick)) {
        RecordSleeve(stageTheme(song.id), Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth(.633f).heightIn(min = 199.dp)
            .padding(start = 20.dp, top = 15.dp, bottom = 17.dp, end = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StudioGlyph(StudioIcon.Catalogue, SleeveType, Modifier.size(16.dp))
                Text("Каталог", color = SleeveType, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                StudioGlyph(StudioIcon.Chevron, SleeveType, Modifier.size(11.dp))
            }
            Spacer(Modifier.height(19.dp))
            Text(song.title, color = SleeveType, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                maxLines = if (song.id in state.importedIds) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, letterSpacing = (-1.65).sp,
                fontSize = if (song.title.length < 17) 35.sp else 29.sp,
                lineHeight = if (song.title.length < 17) 36.sp else 31.sp)
            if (song.artist.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(song.artist, color = SleeveType, fontSize = 13.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(14.dp))
            val details = listOfNotNull(
                state.bpm.takeIf { it > 0 }?.let { if (song.id in state.importedIds) "≈$it BPM" else "$it BPM" },
                state.durationMs.takeIf { it > 0 }?.let(::durationClock),
            )
            if (details.isNotEmpty()) Text(details.joinToString("   /   "), color = SleeveType,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlaybackActions(actions: Actions) {
    @Composable fun Listen(modifier: Modifier = Modifier) {
        Row(modifier.clip(ControlShape).background(Surface).border(1.dp, Line, ControlShape)
            .clickable(role = Role.Button, onClick = { actions.start(true) })
            .heightIn(min = 52.dp).padding(horizontal = 7.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StudioGlyph(StudioIcon.Headphones, StudioIvory, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Послушать", color = StudioIvory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (LocalDensity.current.fontScale > 1.45f) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ActionButton("Играть", { actions.start(false) }, icon = IconShape.Play, accent = StudioCoral)
            Listen(Modifier.fillMaxWidth())
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton("Играть", { actions.start(false) }, Modifier.weight(1.15f).heightIn(min = 52.dp),
            icon = IconShape.Play, accent = StudioCoral)
        Listen(Modifier.weight(1f))
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun StageChoice(label: String, active: Boolean, modifier: Modifier, icon: StudioIcon, choose: () -> Unit) {
    val text = if (active) StudioMint else StudioIvory
    Row(modifier.clip(ControlShape).background(if (active) Color(0xff23332e) else Surface)
        .border(1.dp, if (active) Color(0xff688d79) else Line, ControlShape)
        .selectable(active, role = Role.Tab, onClick = choose)
        .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        StudioGlyph(icon, text, Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = text, fontSize = 15.sp, lineHeight = 18.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center)
    }
}
