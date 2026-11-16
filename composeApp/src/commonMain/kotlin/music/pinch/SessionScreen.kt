package music.pinch

import music.pinch.core.FORGIVING_WINDOW_MS
import music.pinch.core.Chart
import music.pinch.core.GameStats

enum class Scene { Menu, Loading, CountIn, Playing, Paused, Resuming, Result, Error }
enum class StageMode(val label: String) { Orbit("Орбита"), Planar("Каскад X/Y") }
data class PanelFeedback(val noteAtMs: Long, val shownAtMs: Long, val hit: Boolean, val effectsVisible: Boolean = true)

data class ScreenState(
    val songs: List<Song>,
    val selectedSong: Song,
    val chart: Chart? = null,
    val importedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
    val recordedImportIds: Set<String> = emptySet(),
    val availableIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val notice: String? = null,
    val scene: Scene = Scene.Menu,
    val preview: Boolean = false,
    val stats: GameStats? = null,
    val countdown: Int? = null,
    val error: String? = null,
    val bestScore: Int? = null,
    val newRecord: Boolean = false,
    val volume: Float = 0.8f,
    val haptics: Boolean = false,
    val attempt: Long = 0,
    val panelFeedback: List<PanelFeedback> = emptyList(),
    val stageMode: StageMode = StageMode.Orbit,
    val profile: PlayerProfile = PlayerProfile(),
    val runReward: RunReward = RunReward(startLevel = profile.level),
) {
    val backingAsset: String? get() = selectedSong.backingAsset.takeUnless { selectedSong.id in importedIds }
    val needsSourceFile: Boolean get() = selectedSong.id in importedIds && selectedSong.id !in recordedImportIds
    val canPlay: Boolean get() = selectedSong.id in availableIds
    val title: String get() = selectedSong.title
    val bpm: Int get() = chart?.bpm ?: selectedSong.bundledBpm.takeUnless { selectedSong.id in importedIds } ?: 0
    val durationMs: Long get() = chart?.durationMs ?: selectedSong.bundledDurationMs.takeUnless { selectedSong.id in importedIds } ?: 0L
    val lastHitWindowMs: Long get() = (chart?.notes?.lastOrNull()?.atMs ?: 0) + FORGIVING_WINDOW_MS
}

interface Actions {
    fun selectSong(id: String)
    fun selectStageMode(value: StageMode)
    fun removeImport(id: String)
    fun toggleFavorite(id: String)
    fun restoreRecording()
    fun importAudio()
    fun dismissNotice()
    fun stagePresented(attempt: Long)
    fun start(preview: Boolean)
    fun pause()
    fun resume()
    fun restart()
    fun menu()
    fun tap(uptimeMs: Long)
    fun tapNow()
    fun volume(value: Float)
    fun haptics(value: Boolean)
}
