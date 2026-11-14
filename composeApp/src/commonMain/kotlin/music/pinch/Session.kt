package music.pinch

import kotlinx.coroutines.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import music.pinch.core.Chart
import music.pinch.core.Grade
import music.pinch.core.RhythmGame
import music.pinch.core.TapResult

/** Session commands, native events and frame observations all run on the UI thread. */
internal class Session(
    private val platform: Platform,
    private val scope: CoroutineScope,
    songs: List<Song> = songLibrary,
) : Actions {
    private val imports = songs.mapNotNull { song ->
        platform.readValue("chart.${song.id}")?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { decodeChart(raw, song.title) }.getOrNull()?.let { song.id to it }
        }
    }.toMap().toMutableMap()
    private val initialSong = songs.find { it.id == platform.readValue("selected.song") }
        ?: songs.first { it.bundled != null || it.id in imports }
    var state by mutableStateOf(ScreenState(songs = songs, selectedSong = initialSong,
        instrument = Instrument.entries.find { it.name == platform.readValue("instrument") } ?: Instrument.Piano,
        pace = Pace.entries.find { it.name == platform.readValue("pace") } ?: Pace.Relaxed,
        volume = platform.volume, haptics = platform.haptics))
        private set
    var media by mutableStateOf<GameMedia?>(null)
        private set
    private var game: RhythmGame? = null
    private var hasPlayed = false
    private var playRequestedAt = 0L
    private var waitingSince: Long? = null
    private var resumeAt: Long? = null
    private var reactionUntil = 0L
    private var nextPreviewNote = 0
    private var recordKey = ""

    init { refreshSong() }

    private fun refreshSong() {
        val song = state.selectedSong
        val imported = imports[song.id]
        val chart = (imported ?: song.bundled)?.atPace(state.pace)
        // A new arrangement of the same song has its own record.
        val arrangement = imported?.let { encodeChart(it).hashCode().toString() } ?: "bundled"
        recordKey = "record.${song.id}.${state.pace.name}.$arrangement"
        state = state.copy(chart = chart, importedIds = imports.keys.toSet(), bestScore = platform.readValue(recordKey)?.toIntOrNull(),
            availableIds = state.songs.filter { it.bundled != null || it.id in imports }.map { it.id }.toSet())
    }

    override fun selectSong(id: String) {
        if (state.scene != Scene.Menu || state.importing) return
        val song = state.songs.find { it.id == id } ?: return
        platform.writeValue("selected.song", id)
        state = state.copy(selectedSong = song, notice = null)
        refreshSong()
    }

    override fun selectInstrument(value: Instrument) {
        if (state.scene != Scene.Menu) return
        platform.writeValue("instrument", value.name)
        state = state.copy(instrument = value)
    }

    override fun selectPace(value: Pace) {
        if (state.scene != Scene.Menu) return
        platform.writeValue("pace", value.name)
        state = state.copy(pace = value)
        refreshSong()
    }

    override fun restoreBundled() {
        val song = state.selectedSong
        if (state.scene != Scene.Menu || state.importing || song.bundled == null || song.id !in imports) return
        platform.writeValue("chart.${song.id}", "")
        imports.remove(song.id)
        refreshSong()
    }

    override fun importAudio() {
        if (state.importing || state.scene != Scene.Menu) return
        val song = state.selectedSong
        state = state.copy(importing = true, notice = null)
        scope.launch {
            try {
                val pcm = platform.pickAudio() ?: return@launch
                val chart = withContext(Dispatchers.Default) { extractLeadChart(pcm, 11025, song.title) }
                platform.writeValue("chart.${song.id}", encodeChart(chart))
                imports[song.id] = chart
                refreshSong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(notice = e.message ?: "Не удалось разобрать аудиофайл.")
            } finally {
                state = state.copy(importing = false)
            }
        }
    }

    override fun dismissNotice() { state = state.copy(notice = null) }

    override fun start(preview: Boolean) {
        val song = state.chart ?: return
        if (state.importing) return
        close()
        game = if (preview) null else RhythmGame(song, forgiving = state.pace == Pace.Relaxed)
        state = state.copy(scene = Scene.Loading, preview = preview, positionMs = 0, notice = null,
            countdown = null, reaction = null, error = null, newRecord = false, stats = game?.stats)
        try {
            val player = platform.newMedia(song.durationMs, state.instrument)
            media = player
            player.onInterrupted = { if (media === player) pause() }
            player.prepare(song.notes.map { it.midi }.toSet())
        } catch (e: Exception) {
            fail("Не удалось начать игру. " + e.message.orEmpty())
        }
    }

    override fun restart() = start(state.preview)

    fun observe() {
        val player = media ?: return
        val snap = player.snapshot()
        if (snap.error != null) {
            fail("Не удалось воспроизвести мелодию. " + snap.error)
            return
        }
        when (state.scene) {
            Scene.Loading, Scene.Resuming -> {
                if (!snap.ready) {
                    val start = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                    if (snap.observedUptimeMs - start >= 15_000) fail("Не удалось подготовить звуки инструмента.")
                } else if (state.scene == Scene.Loading) {
                    beginPlayback(player, snap)
                } else {
                    val end = resumeAt ?: (snap.observedUptimeMs + 1500).also { resumeAt = it }
                    val remaining = end - snap.observedUptimeMs
                    if (remaining <= 0) beginPlayback(player, snap)
                    else state = state.copy(countdown = ((remaining + 499) / 500).toInt())
                }
            }
            Scene.Playing -> {
                if (snap.ended) {
                    finish()
                    return
                }
                if (!snap.playing) {
                    if (hasPlayed) {
                        // Players can report a stopped clock for a frame just before their end event.
                        val since = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                        if (snap.observedUptimeMs - since > 160) pause()
                    } else if (snap.observedUptimeMs - playRequestedAt > 5000) fail("Не удалось запустить часы воспроизведения.")
                    return
                }
                waitingSince = null
                hasPlayed = true
                val song = state.chart ?: return
                val now = snap.positionMs.coerceIn(0L, song.durationMs)
                if (state.preview) {
                    while (nextPreviewNote < song.notes.size && song.notes[nextPreviewNote].atMs <= now) {
                        val note = song.notes[nextPreviewNote++]
                        if (now - note.atMs <= 180) player.note(note.midi, note.velocity * state.volume)
                    }
                }
                // A small delivery margin lets a frame and a queued input of the same vsync settle.
                val missed = game?.advance(now - 32) ?: 0
                val leadRemaining = song.notes.first().atMs - now
                val count = if (!state.preview && leadRemaining > 0)
                    ((leadRemaining + 499) / 500).toInt() else null
                state = state.copy(positionMs = now, stats = game?.stats, countdown = count,
                    reaction = state.reaction.takeIf { now < reactionUntil })
                if (missed > 0) showReaction("Пропуск", now)
            }
            else -> Unit
        }
    }

    private fun beginPlayback(player: GameMedia, snap: Playback) {
        waitingSince = null
        resumeAt = null
        hasPlayed = false
        playRequestedAt = snap.observedUptimeMs
        game?.resume()
        state = state.copy(scene = Scene.Playing, countdown = null)
        player.play()
    }

    override fun tap(uptimeMs: Long) {
        if (state.scene != Scene.Playing || state.preview) return
        val player = media ?: return
        val snap = player.snapshot()
        if (snap.error != null || !snap.playing || snap.ended || uptimeMs < playRequestedAt) return
        // Down and snapshot use the same platform monotonic epoch. Playback rate is fixed at 1.
        val at = snap.positionMs - (snap.observedUptimeMs - uptimeMs).coerceAtLeast(0L)
        val engine = game ?: return
        when (val result = engine.tap(at)) {
            is TapResult.Hit -> {
                player.note(result.note.midi, result.note.velocity * state.volume)
                if (state.haptics) platform.feedback()
                val label = when (result.grade) {
                    Grade.Perfect -> "Идеально"
                    Grade.Good -> "Хорошо"
                    Grade.Okay -> "Попал"
                }
                showReaction(label, snap.positionMs)
            }
            TapResult.Stray -> showReaction("Мимо", snap.positionMs)
            TapResult.Ignored -> return
        }
        state = state.copy(stats = engine.stats)
    }

    override fun tapNow() { media?.snapshot()?.let { tap(it.observedUptimeMs) } }

    private fun showReaction(text: String, at: Long) {
        reactionUntil = at + 420
        state = state.copy(reaction = text, reactionId = state.reactionId + 1)
    }

    override fun pause() {
        if (state.scene !in setOf(Scene.Loading, Scene.Playing, Scene.Resuming)) return
        state = state.copy(scene = Scene.Paused, countdown = null, reaction = null)
        game?.pause()
        media?.pause()
        media?.snapshot()?.let { state = state.copy(positionMs = it.positionMs) }
        hasPlayed = false
        resumeAt = null
        waitingSince = null
    }

    override fun resume() {
        if (state.scene == Scene.Paused) state = state.copy(scene = Scene.Resuming, countdown = 3)
    }

    private fun finish() {
        if (state.preview) { menu(); return }
        val final = game?.finish() ?: return
        val previous = state.bestScore
        val record = previous == null || final.score > previous
        if (record) platform.writeValue(recordKey, final.score.toString())
        close()
        state = state.copy(scene = Scene.Result, positionMs = state.durationMs, reaction = null,
            countdown = null, stats = final, bestScore = if (record) final.score else previous, newRecord = record)
    }

    override fun menu() {
        close()
        state = state.copy(scene = Scene.Menu, positionMs = 0, preview = false, stats = null,
            error = null, countdown = null, reaction = null, newRecord = false)
    }

    override fun volume(value: Float) {
        platform.volume = value.coerceIn(0f, 1f)
        state = state.copy(volume = platform.volume)
    }

    override fun haptics(value: Boolean) {
        platform.haptics = value
        state = state.copy(haptics = value)
        if (value) platform.feedback()
    }

    private fun fail(message: String) {
        close()
        state = state.copy(scene = Scene.Error, error = message, countdown = null, reaction = null)
    }

    fun close() {
        val old = media
        media = null
        old?.onInterrupted = null
        old?.close()
        nextPreviewNote = 0
        game = null
        waitingSince = null
        resumeAt = null
        hasPlayed = false
        reactionUntil = 0
    }
}
