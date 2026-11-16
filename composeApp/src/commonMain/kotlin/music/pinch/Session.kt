package music.pinch

import kotlinx.coroutines.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.RhythmGame
import music.pinch.core.TapResult
import music.pinch.core.cascadeChart
import music.pinch.core.cascadePreRollMs
import music.pinch.core.FORGIVING_WINDOW_MS
import kotlin.math.abs
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
    private companion object {
        // One UI-thread writer per legacy file, including across application windows.
        val restoring = mutableSetOf<String>()
    }
    private val catalog = PersonalCatalog(platform, songs)
    private class Selection(val source: Chart, val landings: Chart, val importHash: Int? = null)
    private val selections = mutableMapOf<String, Selection>()
    private var loadingScore: Job? = null
    private var importingSong: Job? = null
    private var startWhenReady = false
    private fun defaultSong() = catalog.songs.firstOrNull { it.id == "stereo-love" && it.hasBundled }
        ?: catalog.songs.firstOrNull { it.hasBundled && it.backingAsset != null } ?: catalog.songs.first()
    private val initialSong = platform.readValue("selected.song")?.let { saved -> catalog.songs.find { it.id == saved } }
        ?: defaultSong()
    var state by mutableStateOf(ScreenState(songs = catalog.songs, selectedSong = initialSong,
        stageMode = platform.readValue("stage.mode")?.let { name -> StageMode.entries.find { it.name == name } } ?: StageMode.Orbit,
        volume = platform.volume, haptics = platform.haptics, profile = storedProfile()))
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
    // Keep the selected course warm across Listen, Play and Restart. A change of song
    // or scene replaces it. The native player belongs only to the current attempt.
    private var courseTask: Deferred<Result<PreparedStage>>? = null
    private var pendingStart: Job? = null
    var stage by mutableStateOf<PreparedStage?>(null)
        private set
    private var recordKey = ""
    private var presented = false
    private var introMs = 0L
    private var transportStarted = false
    private var openingEnd: Long? = null
    private var pendingOpeningNote: Note? = null
    private var eofObservedAt: Long? = null
    private var eofElapsedAtPause = 0L

    init { refreshSong() }

    private fun storedProfile() = PlayerProfile.restore(platform.readValue(PlayerProfile.storageKey))

    private fun saveReward(reward: RunReward): PlayerProfile {
        val delta = reward.totalXp - state.runReward.totalXp
        if (delta <= 0) return state.profile
        // Sessions use the UI thread. Read the current total in case another window also earned XP.
        val saved = storedProfile()
        val base = if (saved.totalXp >= state.profile.totalXp) saved else state.profile
        val profile = base.add(delta)
        platform.writeValue(PlayerProfile.storageKey, profile.totalXp.toString())
        return profile
    }

    private fun menuAvailability(): ScreenState {
        val tracks = catalog.songs
        val personal = catalog.entries.mapTo(mutableSetOf()) { it.id }
        val recorded = catalog.entries.filter { it.hasRecording }.mapTo(mutableSetOf()) { it.id }
        return state.copy(songs = tracks, importedIds = personal, favoriteIds = catalog.favorites,
            recordedImportIds = recorded,
            availableIds = tracks.filter { it.hasBundled && it.backingAsset != null }.mapTo(recorded.toMutableSet()) { it.id })
    }

    private fun refreshSong() {
        loadingScore?.cancel()
        courseTask?.cancel()
        courseTask = null
        val song = state.selectedSong
        val cached = selections[song.id]
        if (cached != null) {
            installSelection(song, cached)
            return
        }
        state = menuAvailability().copy(chart = null, bestScore = null)
        val entry = catalog.entries.find { it.id == song.id }
        if (entry == null && song.id !in state.availableIds) return
        val eager = song.suppliedChart
        if (entry == null && eager != null) {
            installSelection(song, Selection(eager, eager.cascadeChart()))
            return
        }
        loadingScore = scope.launch {
            try {
                val selection = withContext(courseDispatcher) {
                    if (entry == null) {
                        val source = requireNotNull(song.bundled)
                        Selection(source, source.cascadeChart())
                    } else {
                        val raw = requireNotNull(platform.readValue(entry.chartKey))
                        val source = decodeChart(raw, song.title)
                        Selection(source, source.cascadeChart(), encodeChart(source).hashCode())
                    }
                }
                installSelection(song, selection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startWhenReady) fail("Не удалось подготовить мелодию. " + e.message.orEmpty())
                else state = state.copy(notice = e.message ?: "Не удалось подготовить мелодию.")
            }
        }
    }

    private fun installSelection(song: Song, selection: Selection) {
        selections[song.id] = selection
        val source = selection.source
        val chart = selection.landings
        val arrangement = when {
            selection.importHash != null -> "import.${selection.importHash}"
            song.bundledRevision > 1 -> "bundled.v${song.bundledRevision}"
            else -> "bundled"
        }
        val mode = state.stageMode
        val entry = catalog.entries.find { it.id == song.id }
        val recordOwner = entry?.storageId ?: song.id
        recordKey = "record.landings.v1.${mode.name}.$recordOwner.$arrangement"
        val previousRecord = entry?.takeIf { it.sameTargets }?.previousHash?.let {
            platform.readValue("record.landings.v1.${mode.name}.$recordOwner.import.$it")?.toIntOrNull()
        }
        val bestScore = listOfNotNull(platform.readValue(recordKey)?.toIntOrNull(), previousRecord).maxOrNull()
        state = menuAvailability().copy(chart = chart, bestScore = bestScore)
        if (!state.canPlay) return
        courseTask = scope.async(courseDispatcher) {
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
        if (startWhenReady) {
            startWhenReady = false
            startPrepared(state.preview)
        }
    }

    override fun selectSong(id: String) {
        if (state.scene != Scene.Menu || state.importing || id == state.selectedSong.id) return
        val song = state.songs.find { it.id == id } ?: return
        platform.writeValue("selected.song", id)
        state = state.copy(selectedSong = song, notice = null)
        refreshSong()
    }

    override fun selectStageMode(value: StageMode) {
        if (state.scene != Scene.Menu || state.stageMode == value) return
        platform.writeValue("stage.mode", value.name)
        state = state.copy(stageMode = value)
        if (state.chart != null || loadingScore?.isActive != true) refreshSong()
    }

    override fun toggleFavorite(id: String) {
        if (state.scene != Scene.Menu) return
        catalog.toggleFavorite(id)
        state = menuAvailability()
    }

    override fun removeImport(id: String) {
        if (state.scene != Scene.Menu || state.importing) return
        val entry = catalog.entries.find { it.id == id } ?: return
        val activeHash = selections[id]?.importHash ?: platform.readValue(entry.chartKey)?.hashCode()
        val hashes = setOfNotNull(activeHash, entry.previousHash)
        catalog.remove(entry)
        for (hash in hashes) for (mode in StageMode.entries) {
            platform.removeValue("record.landings.v1.${mode.name}.${entry.storageId}.import.$hash")
        }
        selections.remove(id)
        if (state.selectedSong.id == id) {
            val fallback = catalog.songs.find { it.id == entry.legacyId && it.hasBundled } ?: defaultSong()
            platform.writeValue("selected.song", fallback.id)
            state = menuAvailability().copy(selectedSong = fallback)
            refreshSong()
        } else state = menuAvailability()
        if (entry.hasRecording) platform.removeImportAudio(id)
    }

    override fun importAudio() = pickRecording(null)

    override fun restoreRecording() {
        val entry = catalog.entries.find { it.id == state.selectedSong.id && !it.hasRecording } ?: return
        pickRecording(entry)
    }

    private fun pickRecording(previous: PersonalSong?) {
        if (state.importing || state.scene != Scene.Menu) return
        if (previous != null && !restoring.add(previous.id)) return
        state = state.copy(importing = true, notice = null)
        importingSong = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val audio = platform.pickAudio() ?: return@launch
                val pending = previous ?: catalog.newEntry(audio.displayName)
                val cached = selections[pending.id]
                val (entry, raw, selection) = withContext(courseDispatcher) {
                    val chart = extractLeadChart(audio.samples, 11_025, pending.title)
                    val raw = encodeChart(chart)
                    val selection = Selection(chart, chart.cascadeChart(), raw.hashCode())
                    val original = previous?.let { old ->
                        cached ?: platform.readValue(old.chartKey)?.let { text ->
                            val source = decodeChart(text, old.title)
                            Selection(source, source.cascadeChart(), encodeChart(source).hashCode())
                        }
                    }
                    // Earlier importers shifted all onsets by a count-in. Reanalyse the actual file
                    // and keep the original chart even if the new transcription differs.
                    val restored = pending.copy(hasRecording = true,
                        previousHash = previous?.let { original?.importHash ?: raw.hashCode() },
                        sameTargets = original != null && sameTargets(original.landings, selection.landings))
                    Triple(restored, raw, selection)
                }
                if (previous != null && !catalog.awaitingFile(previous.id)) {
                    state = state.copy(notice = "Запись уже изменена в другом окне.")
                    return@launch
                }
                try {
                    check(platform.saveImportAudio(entry.id, audio)) { "Не удалось сохранить фонограмму записи." }
                    currentCoroutineContext().ensureActive()
                    check(previous == null || catalog.awaitingFile(previous.id)) { "Запись удалена из каталога." }
                    catalog.saveRecording(entry, raw)
                } catch (failure: Exception) {
                    platform.removeImportAudio(entry.id)
                    platform.removeValue(entry.chartKey)
                    throw failure
                }
                selections[entry.id] = selection
                platform.writeValue("selected.song", entry.id)
                state = menuAvailability().copy(selectedSong = entry.asSong())
                refreshSong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(notice = e.message ?: "Не удалось разобрать аудиофайл.")
            } finally {
                previous?.let { restoring.remove(it.id) }
                state = state.copy(importing = false)
            }
        }
    }

    private fun sameTargets(old: Chart, fresh: Chart): Boolean {
        if (old.notes.size != fresh.notes.size || old.notes.isEmpty()) return false
        val shift = fresh.notes.first().atMs - old.notes.first().atMs
        return old.notes.indices.all { i ->
            val a = old.notes[i]
            val b = fresh.notes[i]
            a.midi == b.midi && abs(b.atMs - a.atMs - shift) <= 35L
        }
    }

    override fun dismissNotice() { state = state.copy(notice = null) }

    override fun start(preview: Boolean) {
        if (state.scene != Scene.Loading) startAttempt(preview)
    }

    override fun restart() = startAttempt(state.preview)

    private fun startAttempt(preview: Boolean) {
        if (state.importing) return
        if (!state.canPlay) {
            state = state.copy(notice = if (state.needsSourceFile) "Выбери исходный файл этой записи." else "Фонограмма готовится.")
            return
        }
        close()
        val profile = storedProfile()
        state = state.copy(scene = Scene.Loading, attempt = state.attempt + 1, preview = preview, notice = null,
            countdown = null, error = null, newRecord = false, stats = null,
            panelFeedback = emptyList(), profile = profile, runReward = RunReward(startLevel = profile.level))
        if (state.chart == null || courseTask == null) {
            startWhenReady = true
            if (loadingScore?.isActive != true) refreshSong()
        } else startPrepared(preview)
    }

    private fun startPrepared(preview: Boolean) {
        val selection = selections[state.selectedSong.id] ?: return
        val song = selection.landings
        val course = courseTask ?: return
        introMs = if (preview) 0L else song.cascadePreRollMs()
        positionMs = -introMs
        try {
            val imported = if (state.selectedSong.id in state.recordedImportIds)
                requireNotNull(platform.importAudioTrack(state.selectedSong.id)) { "Не удалось открыть файл записи." }
            else null
            val player = platform.newMedia(song.durationMs, Instrument.Piano)
            media = player
            player.onInterrupted = { if (media === player) pause() }
            player.setBackingVolume(state.volume)
            player.prepare(emptySet(), state.backingAsset, imported, includeStrike = !preview)
            pendingStart = scope.launch {
                val result = course.await()
                if (media !== player) return@launch
                val ready = result.getOrElse {
                    courseTask = null
                    fail("Не удалось подготовить трассу. " + it.message.orEmpty())
                    return@launch
                }
                stage = ready
                game = if (preview) null else RhythmGame(song, forgiving = true)
                state = state.copy(stats = game?.stats)
            }
        } catch (e: Exception) {
            fail("Не удалось начать игру. " + e.message.orEmpty())
        }
    }

    override fun stagePresented(attempt: Long) {
        if (attempt == state.attempt && stage != null) presented = true
    }

    fun observe() {
        val player = media ?: return
        val snap = player.snapshot()
        if (snap.error != null) {
            fail("Не удалось воспроизвести мелодию. " + snap.error)
            return
        }
        // Once iOS releases a completed audio graph it can report ended and unprepared together.
        if (snap.ended && transportStarted && (state.scene == Scene.Playing || state.scene == Scene.Resuming)) {
            if (state.scene == Scene.Resuming) {
                eofObservedAt = snap.observedUptimeMs - eofElapsedAtPause
                playRequestedAt = snap.observedUptimeMs
                game?.resume()
                state = state.copy(scene = Scene.Playing, countdown = null)
            }
            val end = eofObservedAt ?: snap.observedUptimeMs.also { eofObservedAt = it }
            // Keep the input window for a short final note open after its last sample.
            if (!state.preview && snap.observedUptimeMs - end <= state.lastHitWindowMs - state.durationMs) {
                positionMs = state.durationMs
                playingAnchor = snap.copy(positionMs = state.durationMs, observedUptimeMs = end, stalled = false)
            } else finish()
            return
        }
        when (state.scene) {
            Scene.Loading, Scene.Resuming -> {
                if (!snap.ready || stage == null || !presented) {
                    val start = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                    if (snap.observedUptimeMs - start >= 15_000) {
                        fail(if (!snap.ready) "Не удалось подготовить мелодию." else "Не удалось открыть игровую сцену.")
                    }
                } else if (!transportStarted && introMs > 0) {
                    waitingSince = null
                    openingEnd = snap.observedUptimeMs + introMs
                    game?.resume()
                    state = state.copy(scene = Scene.CountIn, countdown = openingCount(introMs))
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
            Scene.CountIn -> {
                val remaining = (requireNotNull(openingEnd) - snap.observedUptimeMs).coerceIn(0L, introMs)
                positionMs = -remaining
                if (remaining == 0L) beginPlayback(player, snap)
                else {
                    val count = openingCount(remaining)
                    if (state.countdown != count) state = state.copy(countdown = count)
                }
            }
            Scene.Playing -> {
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
                // A small delivery margin lets a frame and a queued input of the same vsync settle.
                val confirmed = snap.confirmedPositionMs ?: now
                game?.advance(confirmed - 32)
                val missed = game?.takeMisses().orEmpty()
                val feedback = feedbackAfter(missed, null, now)
                val leadRemaining = (game?.firstTargetMs ?: 0L) - now
                val beatMs = (60_000L / song.bpm).coerceAtLeast(1)
                val count = if (!state.preview && leadRemaining in 1..(4 * beatMs))
                    ((leadRemaining + beatMs - 1) / beatMs).toInt() else null
                positionMs = snap.visualPosition(previousAnchor, positionMs, song.durationMs)
                val stats = game?.stats
                if (stats !== state.stats || count != state.countdown || feedback !== state.panelFeedback) {
                    state = state.copy(stats = stats, countdown = count, panelFeedback = feedback)
                }
            }
            else -> Unit
        }
    }

    /** Visuals may look ahead to scanout. Input and automatic audio always use the observed transport. */
    fun presentationPosition(targetUptimeMs: Long): Long = when (state.scene) {
        Scene.CountIn -> openingEnd?.let { (targetUptimeMs - it).coerceIn(-introMs, -1L) } ?: positionMs
        Scene.Playing -> playingAnchor?.presentAt(positionMs, targetUptimeMs, state.durationMs) ?: positionMs
        else -> positionMs
    }

    private fun openingCount(ms: Long): Int {
        val beat = (60_000L / state.bpm).coerceAtLeast(1)
        return ((ms + beat - 1) / beat).toInt()
    }

    private fun beginPlayback(player: GameMedia, snap: Playback) {
        waitingSince = null
        resumeAt = null
        hasPlayed = false
        transportStarted = true
        positionMs = positionMs.coerceAtLeast(0L)
        playRequestedAt = snap.observedUptimeMs
        game?.resume()
        state = state.copy(scene = Scene.Playing, countdown = null)
        player.play()
        // Anchor once as soon as the native player accepts play. Later frames track decoder drift.
        if (state.scene == Scene.Playing) {
            playingAnchor = player.snapshot().takeIf { it.playing }
            pendingOpeningNote?.let { soundHit(player, it) }
            pendingOpeningNote = null
        }
    }

    override fun tap(uptimeMs: Long) {
        if (state.preview || (state.scene != Scene.Playing && state.scene != Scene.CountIn)) return
        val player = media ?: return
        val engine = game ?: return
        val opening = state.scene == Scene.CountIn
        val at = if (opening) {
            (uptimeMs - (openingEnd ?: return)).coerceAtMost(0L)
        } else {
            val snap = playingAnchor ?: return
            if (uptimeMs < playRequestedAt) {
                val end = openingEnd ?: return
                // Touch delivered after play(), but physically made in the opening window.
                if (uptimeMs < end - FORGIVING_WINDOW_MS) return
                (uptimeMs - end).coerceAtMost(0L)
            } else if (snap.stalled) snap.positionMs else snap.positionMs + (uptimeMs - snap.observedUptimeMs)
        }
        val feedbackAt = maxOf(at, positionMs)
        val nearest = engine.nearestTarget(at)
        if (abs(at - nearest.atMs) > FORGIVING_WINDOW_MS && onRail(at)) return
        val result = engine.tap(at)
        val hit = result as? TapResult.Hit
        val feedback = feedbackAfter(engine.takeMisses(), hit?.note, feedbackAt)
        if (state.stats !== engine.stats || feedback !== state.panelFeedback) {
            val reward = if (hit != null) state.runReward.hit(hit.grade, engine.stats.combo) else state.runReward
            state = state.copy(stats = engine.stats, panelFeedback = feedback, runReward = reward,
                profile = saveReward(reward))
        }
        when (result) {
            is TapResult.Hit -> {
                if (opening) pendingOpeningNote = result.note else soundHit(player, result.note)
                if (state.haptics) platform.feedback()
            }
            TapResult.Stray, TapResult.Ignored -> Unit
        }
    }

    override fun tapNow() { media?.let { tap(it.uptimeMs()) } }

    private fun onRail(at: Long): Boolean = when (val current = stage) {
        is PreparedStage.Cascade -> current.path.onRail(at)
        is PreparedStage.Orbit -> current.path.onRail(at)
        null -> false
    }

    private fun feedbackAfter(missed: List<Note>, hit: Note?, at: Long): List<PanelFeedback> {
        if (missed.isEmpty() && hit == null) return state.panelFeedback
        val recent = ArrayList(state.panelFeedback)
        fun append(note: Note, scored: Boolean) {
            // Queued input can correct a miss which a display frame already reported.
            recent.removeAll { it.noteAtMs == note.atMs }
            if (recent.size == 4) recent.removeAt(0)
            recent.add(PanelFeedback(note.atMs, at, scored))
        }
        for (index in (missed.size - 4).coerceAtLeast(0) until missed.size) append(missed[index], false)
        if (hit != null) append(hit, true)
        return recent
    }

    private fun soundHit(player: GameMedia, note: Note) {
        player.strike(.20f * note.velocity * state.volume)
    }

    override fun pause() {
        if (state.scene !in setOf(Scene.Loading, Scene.CountIn, Scene.Playing, Scene.Resuming)) return
        // The nearest panel may still be inside its input window when the song resumes.
        // Keep its judgement so the HUD does not invite a second tap, and retire the transient effects.
        state = state.copy(scene = Scene.Paused, countdown = null,
            panelFeedback = state.panelFeedback.map { it.copy(effectsVisible = false) })
        game?.pause()
        media?.pause()
        if (transportStarted) {
            media?.snapshot()?.let { snap ->
                positionMs = snap.positionMs
                eofElapsedAtPause = eofObservedAt?.let { (snap.observedUptimeMs - it).coerceAtLeast(0) } ?: 0L
            }
        } else positionMs = -introMs
        openingEnd = null
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
        val reward = state.runReward.finish(final)
        val previous = state.bestScore
        val record = previous == null || final.score > previous
        if (record) platform.writeValue(recordKey, final.score.toString())
        val profile = saveReward(reward)
        close()
        positionMs = state.durationMs
        state = state.copy(scene = Scene.Result,
            countdown = null, stats = final, bestScore = if (record) final.score else previous, newRecord = record,
            runReward = reward, profile = profile)
    }

    override fun menu() {
        close()
        positionMs = 0
        state = state.copy(scene = Scene.Menu, preview = false, stats = null,
            error = null, countdown = null, newRecord = false,
            panelFeedback = emptyList(), runReward = RunReward(startLevel = state.profile.level))
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
        state = state.copy(scene = Scene.Error, error = message, countdown = null)
    }

    fun dispose() {
        importingSong?.cancel()
        loadingScore?.cancel()
        courseTask?.cancel()
        courseTask = null
        close()
    }

    private fun close() {
        startWhenReady = false
        presented = false
        transportStarted = false
        openingEnd = null
        pendingOpeningNote = null
        eofObservedAt = null
        eofElapsedAtPause = 0L
        pendingStart?.cancel()
        pendingStart = null
        val old = media
        media = null
        old?.onInterrupted = null
        old?.close()
        stage = null
        playingAnchor = null
        game = null
        waitingSince = null
        resumeAt = null
        hasPlayed = false
    }
}
