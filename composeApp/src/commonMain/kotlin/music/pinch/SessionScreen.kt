package music.pinch

import music.pinch.core.FORGIVING_WINDOW_MS
import music.pinch.core.Chart
import music.pinch.core.GameStats

enum class Scene { Menu, Loading, Playing, Paused, Resuming, Result, Error }
enum class Instrument(val label: String) { Piano("Фортепиано"), Mallet("Маримба"), Synth("Синтезатор") }

data class Song(val id: String, val title: String, val artist: String, val bundled: Chart? = null, val backingAsset: String? = null)

data class ScreenState(
    val songs: List<Song>,
    val selectedSong: Song,
    val chart: Chart? = null,
    val importedIds: Set<String> = emptySet(),
    val availableIds: Set<String> = emptySet(),
    val instrument: Instrument = Instrument.Piano,
    val importing: Boolean = false,
    val notice: String? = null,
    val scene: Scene = Scene.Menu,
    val preview: Boolean = false,
    val positionMs: Long = 0,
    val stats: GameStats? = null,
    val reaction: String? = null,
    val reactionId: Int = 0,
    val countdown: Int? = null,
    val error: String? = null,
    val bestScore: Int? = null,
    val newRecord: Boolean = false,
    val volume: Float = 0.8f,
    val haptics: Boolean = false
) {
    val backingAsset: String? get() = selectedSong.backingAsset.takeUnless { selectedSong.id in importedIds }
    val title: String get() = selectedSong.title
    val bpm: Int get() = chart?.bpm ?: 0
    val durationMs: Long get() = chart?.durationMs ?: 0
    val lastHitWindowMs: Long get() = (chart?.notes?.lastOrNull()?.atMs ?: 0) + FORGIVING_WINDOW_MS
}

interface Actions {
    fun selectSong(id: String)
    fun selectInstrument(value: Instrument)
    fun restoreBundled()
    fun importAudio()
    fun dismissNotice()
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
