package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import music.pinch.Actions
import music.pinch.ScreenState

@Composable
internal fun ResultScreen(state: ScreenState, actions: Actions) {
    val stats = state.stats ?: return
    val reward = state.runReward
    val profile = state.profile
    val raised = profile.level > reward.startLevel
    val shape = RoundedCornerShape(20.dp)
    val quiet = StudioIvory.copy(alpha = .74f)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("Результат", color = quiet, fontSize = 14.sp)
                Text(state.title, color = StudioIvory, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp, modifier = Modifier.semantics { heading() })
            }
            Column(Modifier.fillMaxWidth().background(StudioIvory, shape).padding(16.dp)) {
                Text(if (state.newRecord && stats.score > 0) "Новый рекорд" else "Счёт", color = StudioGraphite, fontSize = 14.sp)
                Text(number(stats.score), color = StudioGraphite, fontWeight = FontWeight.Black, fontSize = 44.sp,
                    lineHeight = 49.sp, letterSpacing = (-1.8).sp)
                Spacer(Modifier.height(8.dp))
                val tenths = (stats.accuracy * 1000).roundToInt()
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    ResultMetric("Точность", "${tenths / 10},${tenths % 10} %", StudioGraphite)
                    ResultMetric("Лучшая серия", "×${stats.maxCombo}", StudioGraphite)
                }
            }
            Column(Modifier.fillMaxWidth().border(1.dp, StudioMint.copy(alpha = .38f), shape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (raised) "Новый уровень · ${profile.level}" else "Уровень ${profile.level}",
                        modifier = Modifier.weight(1f), color = StudioIvory, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("+${reward.totalXp} XP", color = StudioMint, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Canvas(Modifier.fillMaxWidth().height(4.dp).semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(profile.progress, 0f..1f)
                }) {
                    drawRect(StudioIvory.copy(alpha = .16f))
                    drawRect(StudioMint, size = Size(size.width * profile.progress, size.height))
                }
                Text("${profile.xpInLevel} / ${profile.xpForNextLevel} XP", color = quiet, fontSize = 12.sp)
            }
            Column {
                ResultRow("Идеально", stats.perfect.toString(), StudioMint)
                ResultRow("Хорошо", stats.good.toString())
                ResultRow("Попал", stats.okay.toString())
                ResultRow("Пропущено", stats.missed.toString())
                if (stats.stray > 0) ResultRow("Лишние нажатия", stats.stray.toString())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ActionButton("Ещё раз", actions::restart, Modifier.weight(1.1f), icon = IconShape.Play)
            ActionButton("В меню", actions::menu, Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
private fun ResultMetric(label: String, value: String, ink: Color) {
    Column {
        Text(label, color = ink.copy(alpha = .72f), fontSize = 12.sp, lineHeight = 16.sp)
        Text(value, color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
