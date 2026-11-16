package music.pinch

import music.pinch.core.FORGIVING_WINDOW_MS
import music.pinch.core.Chart
import music.pinch.core.GameStats

enum class Scene { Menu, Loading, CountIn, Playing, Paused, Resuming, Result, Error }
enum class StageMode(val label: String) { Orbit("Орбита"), Planar("Каскад X/Y") }
enum class Instrument(val label: String) { Piano("Фортепиано"), Mallet("Маримба"), Synth("Синтезатор") }

data class Song(val id: String, val title: String, val artist: String, val bundled: Chart? = null, val backingAsset: String? = null, val bundledRevision: Int = 1)

data class PanelFeedback(val noteAtMs: Long, val shownAtMs: Long, val hit: Boolean)

data class ScreenState(
    val songs: List<Song>,
    val selectedSong: Song,
    val chart: Chart? = null,
    val importedIds: Set<String> = emptySet(),
    val availableIds: Set<String> = emptySet(),
    val instrument: Instrument = Instrument.Piano,
    val solo: Boolean = false,
    val importing: Boolean = false,
    val notice: String? = null,
    val scene: Scene = Scene.Menu,
    val preview: Boolean = false,
    val stats: GameStats? = null,
    val reaction: String? = null,
    val reactionId: Int = 0,
    val countdown: Int? = null,
    val error: String? = null,
    val bestScore: Int? = null,
    val newRecord: Boolean = false,
    val volume: Float = 0.8f,
    val haptics: Boolean = false,
    val attempt: Long = 0,
    val panelFeedback: List<PanelFeedback> = emptyList(),
    val stageMode: StageMode = StageMode.Orbit,
) {
    val recordedAsset: String? get() = selectedSong.backingAsset.takeUnless { selectedSong.id in importedIds }
    val backingAsset: String? get() = recordedAsset.takeUnless { solo }
    val title: String get() = selectedSong.title
    val bpm: Int get() = chart?.bpm ?: 0
    val durationMs: Long get() = chart?.durationMs ?: 0
    val lastHitWindowMs: Long get() = (chart?.notes?.lastOrNull()?.atMs ?: 0) + FORGIVING_WINDOW_MS
}

interface Actions {
    fun selectSong(id: String)
    fun selectRecording()
    fun selectInstrument(value: Instrument)
    fun selectStageMode(value: StageMode)
    fun restoreBundled()
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
