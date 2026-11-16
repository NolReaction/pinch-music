package music.pinch

import kotlinx.coroutines.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.Grade
import music.pinch.core.RhythmGame
import music.pinch.core.TapResult
import music.pinch.core.cascadeChart
import music.pinch.core.echoTargetTimes
import music.pinch.ui.MarblePath
import music.pinch.ui.PlanarPath
import music.pinch.ui.PreparedStage

/** Session commands, native events and frame observations all run on the UI thread. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Session(
    private val platform: Platform,
    private val scope: CoroutineScope,
    songs: List<Song> = songLibrary,
    private val courseDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : Actions {
    // Keep saved JSON until its song is selected. Opening the menu used to decode every import.
    private val savedCharts = songs.mapNotNull { song ->
        platform.readValue("chart.${song.id}")?.takeIf { it.isNotBlank() }?.let { song.id to it }
    }.toMap().toMutableMap()
    private val imports = mutableMapOf<String, Chart>()
    private val initialSong = platform.readValue("selected.song")?.let { saved -> songs.find { it.id == saved } }
        ?: songs.find { it.id == "stereo-love" && (it.bundled != null || it.id in savedCharts) }
        ?: songs.firstOrNull { it.bundled != null || it.id in savedCharts }
        ?: songs.first()
    var state by mutableStateOf(ScreenState(songs = songs, selectedSong = initialSong,
        instrument = platform.readValue("instrument")?.let { name -> Instrument.entries.find { it.name == name } } ?: Instrument.Piano,
        solo = platform.readValue("playback.solo") == "true",
        stageMode = platform.readValue("stage.mode")?.let { name -> StageMode.entries.find { it.name == name } } ?: StageMode.Orbit,
        volume = platform.volume, haptics = platform.haptics))
        private set
    var media by mutableStateOf<GameMedia?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    private var playingAnchor: Playback? = null
    private var game: RhythmGame? = null
    private var hasPlayed = false
    private var playRequestedAt = 0L
    private var waitingSince: Long? = null
    private var resumeAt: Long? = null
    private var reactionUntil = 0L
    private var automaticNotes: List<Note> = emptyList()
    private var nextAutomaticNote = 0
    private var sourceChart: Chart? = null
    // Keep the selected course warm across Listen, Play and Restart. A change of song
    // or scene replaces it. The native player belongs only to the current attempt.
    private var courseTask: Deferred<Result<PreparedStage>>? = null
    private var pendingStart: Job? = null
    var stage by mutableStateOf<PreparedStage?>(null)
        private set
    private var recordKey = ""
    private var echoTargets = emptySet<Long>()
    private var lastMistakeAt: Long? = null
    private var lastMistakeTarget: String? = null

    init { refreshSong() }

    private fun storedChart(song: Song): Chart? {
        imports[song.id]?.let { return it }
        val raw = savedCharts[song.id] ?: return null
        val chart = runCatching { decodeChart(raw, song.title) }.getOrNull()
        if (chart == null) {
            savedCharts.remove(song.id)
        } else {
            imports[song.id] = chart
            // Earlier releases keyed personal records by the canonical v1 representation.
            savedCharts[song.id] = encodeChart(chart)
        }
        return chart
    }

    private fun refreshSong() {
        val song = state.selectedSong
        val imported = storedChart(song)
        val source = imported ?: song.bundled
        sourceChart = source
        val chart = if (state.stageMode == StageMode.Planar) source?.cascadeChart() else source
        echoTargets = chart?.echoTargetTimes().orEmpty()
        val arrangement = when {
            imported != null -> "import.${savedCharts.getValue(song.id).hashCode()}"
            song.bundledRevision > 1 -> "bundled.v${song.bundledRevision}"
            else -> "bundled"
        }
        val version = if (state.stageMode == StageMode.Planar) "cascade.v1" else "v3"
        recordKey = "record.$version.${song.id}.$arrangement"
        state = state.copy(chart = chart, echoTotal = echoTargets.size, importedIds = savedCharts.keys.toSet(), bestScore = platform.readValue(recordKey)?.toIntOrNull(),
            availableIds = state.songs.filter { it.bundled != null || it.id in savedCharts }.map { it.id }.toSet())
        courseTask?.cancel()
        val mode = state.stageMode
        courseTask = if (source == null || chart == null) null else scope.async(courseDispatcher) {
            try {
                Result.success(when (mode) {
                    StageMode.Orbit -> PreparedStage.Orbit(MarblePath(chart))
                    StageMode.Planar -> PreparedStage.Cascade(PlanarPath(source, chart))
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun selectSong(id: String) {
        if (state.scene != Scene.Menu || state.importing || id == state.selectedSong.id) return
        val song = state.songs.find { it.id == id } ?: return
        platform.writeValue("selected.song", id)
        state = state.copy(selectedSong = song, notice = null)
        refreshSong()
    }

    override fun selectRecording() {
        if (state.scene != Scene.Menu || state.recordedAsset == null || !state.solo) return
        platform.writeValue("playback.solo", "false")
        state = state.copy(solo = false)
    }

    override fun selectInstrument(value: Instrument) {
        if (state.scene != Scene.Menu) return
        val solo = state.solo || state.recordedAsset != null
        if (value == state.instrument && solo == state.solo) return
        if (solo != state.solo) platform.writeValue("playback.solo", solo.toString())
        if (value != state.instrument) platform.writeValue("instrument", value.name)
        state = state.copy(instrument = value, solo = solo)
    }

    override fun selectStageMode(value: StageMode) {
        if (state.scene != Scene.Menu || state.stageMode == value) return
        platform.writeValue("stage.mode", value.name)
        state = state.copy(stageMode = value)
        refreshSong()
    }

    override fun restoreBundled() {
        val song = state.selectedSong
        if (state.scene != Scene.Menu || state.importing || song.bundled == null || song.id !in imports) return
        platform.writeValue("chart.${song.id}", "")
        imports.remove(song.id)
        savedCharts.remove(song.id)
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
                val saved = withContext(Dispatchers.Default) { encodeChart(chart) }
                platform.writeValue("chart.${song.id}", saved)
                savedCharts[song.id] = saved
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
        if (state.scene != Scene.Loading) startAttempt(preview)
    }

    override fun restart() = startAttempt(state.preview)

    private fun startAttempt(preview: Boolean) {
        val song = state.chart ?: return
        val source = sourceChart ?: return
        if (state.importing) return
        if (courseTask == null) refreshSong()
        val course = courseTask ?: return
        close()
        positionMs = 0
        state = state.copy(scene = Scene.Loading, preview = preview, notice = null,
            countdown = null, reaction = null, error = null, newRecord = false, stats = null,
            echoesCaught = 0, collectedEchoMs = null, lastLedTapMs = null)
        try {
            val player = platform.newMedia(song.durationMs, state.instrument)
            media = player
            player.onInterrupted = { if (media === player) pause() }
            val pitches = mutableSetOf<Int>()
            val backing = state.backingAsset
            automaticNotes = when {
                backing != null -> emptyList()
                state.stageMode == StageMode.Planar -> {
                    // A chord lands once. Its other pitches and any skipped ornaments still sound.
                    // Reserve the selected pitch for the player's tap even if two voices play it in unison.
                    val manual = if (preview) emptySet() else
                        song.notes.filter { it.playable }.mapTo(mutableSetOf()) { it.atMs to it.midi }
                    source.notes.distinctBy { it.atMs to it.midi }.filterNot { (it.atMs to it.midi) in manual }
                }
                preview -> source.notes
                else -> source.notes.filterNot { it.playable }
            }
            for (note in automaticNotes) pitches += note.midi
            for (note in song.notes) {
                if (backing == null) pitches += note.midi
                if (!preview && note.playable) pitches += wrongPitch(note.midi)
                if (!preview && note.atMs in echoTargets) pitches += echoPitch(note.midi)
            }
            player.setBackingVolume(state.volume)
            player.prepare(pitches, backing)
            pendingStart = scope.launch {
                val result = course.await()
                if (media !== player) return@launch
                val ready = result.getOrElse {
                    courseTask = null
                    fail("Не удалось подготовить трассу. " + it.message.orEmpty())
                    return@launch
                }
                stage = ready
                val lights = (ready as? PreparedStage.Cascade)?.path?.ledCues.orEmpty()
                game = if (preview) null else RhythmGame(song, forgiving = true, ledNotes = lights)
                state = state.copy(stats = game?.stats)
            }
        } catch (e: Exception) {
            fail("Не удалось начать игру. " + e.message.orEmpty())
        }
    }


    fun observe() {
        val player = media ?: return
        val snap = player.snapshot()
        if (snap.error != null) {
            fail("Не удалось воспроизвести мелодию. " + snap.error)
            return
        }
        when (state.scene) {
            Scene.Loading, Scene.Resuming -> {
                if (!snap.ready || stage == null) {
                    val start = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                    if (snap.observedUptimeMs - start >= 15_000) fail("Не удалось подготовить мелодию.")
                } else if (state.scene == Scene.Loading) {
                    beginPlayback(player, snap)
                } else {
                    val end = resumeAt ?: (snap.observedUptimeMs + 1500).also { resumeAt = it }
                    val remaining = end - snap.observedUptimeMs
                    if (remaining <= 0) beginPlayback(player, snap)
                    else {
                        val count = ((remaining + 499) / 500).toInt()
                        if (count != state.countdown) state = state.copy(countdown = count)
                    }
                }
            }
            Scene.Playing -> {
                if (snap.ended) {
                    finish()
                    return
                }
                if (!snap.playing) {
                    playingAnchor = null
                    if (hasPlayed) {
                        // Players can report a stopped clock for a frame just before their end event.
                        val since = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                        if (snap.observedUptimeMs - since > 160) pause()
                    } else if (snap.observedUptimeMs - playRequestedAt > 5000) fail("Не удалось запустить часы воспроизведения.")
                    return
                }
                waitingSince = null
                hasPlayed = true
                val previousAnchor = playingAnchor
                playingAnchor = snap
                val song = state.chart ?: return
                val now = snap.positionMs.coerceIn(0L, song.durationMs)
                if (nextAutomaticNote < automaticNotes.size) {
                    val first = nextAutomaticNote
                    while (nextAutomaticNote < automaticNotes.size && automaticNotes[nextAutomaticNote].atMs <= now) {
                        nextAutomaticNote++
                    }
                    // After a stall, play only the latest timely onset, including all voices in a chord.
                    if (nextAutomaticNote > first) {
                        val latest = automaticNotes[nextAutomaticNote - 1].atMs
                        if (now - latest <= 120) {
                            for (index in first until nextAutomaticNote) {
                                val note = automaticNotes[index]
                                if (note.atMs == latest) player.note(note.midi, note.velocity * state.volume)
                            }
                        }
                    }
                }
                // A small delivery margin lets a frame and a queued input of the same vsync settle.
                val confirmed = snap.confirmedPositionMs ?: now
                val missed = game?.advance(confirmed - 32) ?: 0
                game?.takeMissCue(confirmed)?.let { soundMistake(player, it, now, stray = false) }
                val leadRemaining = (game?.firstTargetMs ?: 0L) - now
                val beatMs = (60_000L / song.bpm).coerceAtLeast(1)
                val count = if (!state.preview && leadRemaining in 1..(4 * beatMs))
                    ((leadRemaining + beatMs - 1) / beatMs).toInt() else null
                positionMs = snap.visualPosition(previousAnchor, positionMs, song.durationMs)
                val reaction = state.reaction.takeIf { now < reactionUntil }
                val stats = game?.stats
                if (stats !== state.stats || count != state.countdown || reaction != state.reaction) {
                    state = state.copy(stats = stats, countdown = count, reaction = reaction)
                }
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
        // Anchor once as soon as the native player accepts play. Later frames track decoder drift.
        if (state.scene == Scene.Playing) playingAnchor = player.snapshot().takeIf { it.playing }
    }

    override fun tap(uptimeMs: Long) {
        if (state.scene != Scene.Playing || state.preview) return
        val player = media ?: return
        val snap = playingAnchor ?: return
        if (uptimeMs < playRequestedAt) return
        // Pointer timestamps and the audio anchor share an uptime origin. Touch dispatch must not
        // block on native audio queries merely to measure the intervening milliseconds.
        val at = if (snap.stalled) snap.positionMs else snap.positionMs + (uptimeMs - snap.observedUptimeMs)
        val feedbackAt = maxOf(at, snap.positionMs)
        val engine = game ?: return
        val result = engine.tap(at)
        when (result) {
            is TapResult.Led -> {
                // The ornament is already audible in the backing or the automatic voices.
                if (state.backingAsset != null) player.strike(.22f * state.volume)
                if (state.haptics) platform.feedback()
            }
            is TapResult.Automatic -> {
                val note = result.note
                val gain = note.velocity * state.volume
                if (note.atMs in echoTargets) {
                    player.note(echoPitch(note.midi), .22f * gain)
                    state = state.copy(echoesCaught = state.echoesCaught + 1, collectedEchoMs = note.atMs)
                    showReaction("Эхо +1", feedbackAt)
                } else if (state.backingAsset != null) {
                    player.strike(.26f * gain)
                }
                if (state.haptics) platform.feedback()
            }
            is TapResult.Hit -> {
                val gain = result.note.velocity * state.volume
                if (state.backingAsset == null) player.note(result.note.midi, gain)
                else player.strike(.46f * gain)
                if (state.haptics) platform.feedback()
                val label = when (result.grade) {
                    Grade.Perfect -> "Идеально"
                    Grade.Good -> "Хорошо"
                    Grade.Okay -> if (result.errorMs < 0) "Попал · чуть раньше" else "Попал · чуть позже"
                }
                showReaction(label, feedbackAt)
            }
            TapResult.Stray -> {
                soundMistake(player, engine.nearestTarget(at), feedbackAt, stray = true)
                showReaction("Мимо", feedbackAt)
            }
            TapResult.Ignored -> return
        }
        state = state.copy(stats = engine.stats, lastLedTapMs = result.led?.atMs ?: state.lastLedTapMs)
    }

    override fun tapNow() { media?.let { tap(it.uptimeMs()) } }

    private fun soundMistake(player: GameMedia, target: Note, now: Long, stray: Boolean) {
        // Repeated bad taps still affect score. Keep them from masking the song, including
        // a stray tap delivered in the same beat as that target's automatic miss.
        val previous = lastMistakeAt
        if (previous != null && now - previous < 120 && (stray || lastMistakeTarget == target.id)) return
        val gain = if (state.backingAsset != null) .15f else if (stray) .27f else .36f
        player.note(wrongPitch(target.midi), gain * target.velocity * state.volume)
        lastMistakeAt = now
        lastMistakeTarget = target.id
    }

    private fun showReaction(text: String, at: Long) {
        reactionUntil = at + 420
        state = state.copy(reaction = text, reactionId = state.reactionId + 1)
    }

    override fun pause() {
        if (state.scene !in setOf(Scene.Loading, Scene.Playing, Scene.Resuming)) return
        state = state.copy(scene = Scene.Paused, countdown = null, reaction = null)
        game?.pause()
        media?.pause()
        media?.snapshot()?.let { positionMs = it.positionMs }
        playingAnchor = null
        hasPlayed = false
        resumeAt = null
        waitingSince = null
    }

    override fun resume() {
        if (state.scene != Scene.Paused) return
        state = state.copy(scene = Scene.Resuming, countdown = 3)
        media?.prepareResume()
    }

    private fun finish() {
        if (state.preview) { menu(); return }
        val final = game?.finish() ?: return
        val previous = state.bestScore
        val record = previous == null || final.score > previous
        if (record) platform.writeValue(recordKey, final.score.toString())
        close()
        positionMs = state.durationMs
        state = state.copy(scene = Scene.Result, reaction = null,
            countdown = null, stats = final, bestScore = if (record) final.score else previous, newRecord = record)
    }

    override fun menu() {
        close()
        positionMs = 0
        state = state.copy(scene = Scene.Menu, preview = false, stats = null,
            error = null, countdown = null, reaction = null, newRecord = false,
            echoesCaught = 0, collectedEchoMs = null, lastLedTapMs = null)
    }

    override fun volume(value: Float) {
        platform.volume = value.coerceIn(0f, 1f)
        state = state.copy(volume = platform.volume)
        media?.setBackingVolume(platform.volume)
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

    fun dispose() {
        courseTask?.cancel()
        courseTask = null
        close()
    }

    private fun close() {
        pendingStart?.cancel()
        pendingStart = null
        val old = media
        media = null
        old?.onInterrupted = null
        old?.close()
        automaticNotes = emptyList()
        nextAutomaticNote = 0
        stage = null
        playingAnchor = null
        lastMistakeAt = null
        lastMistakeTarget = null
        game = null
        waitingSince = null
        resumeAt = null
        hasPlayed = false
        reactionUntil = 0
    }
}


// Lean one semitone off the expected pitch, including at the MIDI range boundary.
private fun wrongPitch(midi: Int): Int = if (midi == 127) 126 else midi + 1

private fun echoPitch(midi: Int) = if (midi <= 115) midi + 12 else midi - 12
