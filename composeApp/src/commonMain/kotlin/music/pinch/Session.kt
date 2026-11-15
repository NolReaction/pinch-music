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
import music.pinch.core.echoTargetTimes

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
        ?: songs.find { it.id == "stereo-love" && (it.bundled != null || it.id in imports) }
        ?: songs.firstOrNull { it.bundled != null || it.id in imports }
        ?: songs.first()
    var state by mutableStateOf(ScreenState(songs = songs, selectedSong = initialSong,
        instrument = Instrument.entries.find { it.name == platform.readValue("instrument") } ?: Instrument.Piano,
        volume = platform.volume, haptics = platform.haptics))
        private set
    var media by mutableStateOf<GameMedia?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    private var playingAnchor: Playback? = null
    private val displayClock = DecoderDisplayClock()
    private var game: RhythmGame? = null
    private var hasPlayed = false
    private var playRequestedAt = 0L
    private var waitingSince: Long? = null
    private var resumeAt: Long? = null
    private var reactionUntil = 0L
    private var automaticNotes: List<Note> = emptyList()
    private var nextAutomaticNote = 0
    private var recordKey = ""
    private var echoTargets = emptySet<Long>()
    private var lastMistakeAt: Long? = null
    private var lastMistakeTarget: String? = null

    init { refreshSong() }

    private fun refreshSong() {
        val song = state.selectedSong
        val imported = imports[song.id]
        val chart = imported ?: song.bundled
        echoTargets = chart?.echoTargetTimes().orEmpty()
        // The old records used stretched clocks. Leave those keys intact on upgraded devices.
        val arrangement = imported?.let { "import.${encodeChart(it).hashCode()}" } ?: "bundled"
        recordKey = "record.v3.${song.id}.$arrangement"
        state = state.copy(chart = chart, echoTotal = echoTargets.size, importedIds = imports.keys.toSet(), bestScore = platform.readValue(recordKey)?.toIntOrNull(),
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
        game = if (preview) null else RhythmGame(song, forgiving = true)
        positionMs = 0
        state = state.copy(scene = Scene.Loading, preview = preview, notice = null,
            countdown = null, reaction = null, error = null, newRecord = false, stats = game?.stats,
            echoesCaught = 0, collectedEchoMs = null)
        try {
            val player = platform.newMedia(song.durationMs, state.instrument)
            media = player
            player.onInterrupted = { if (media === player) pause() }
            val pitches = mutableSetOf<Int>()
            val backing = state.backingAsset
            automaticNotes = when {
                backing != null -> emptyList()
                preview -> song.notes
                else -> song.notes.filterNot { it.playable }
            }
            for (note in song.notes) {
                if (backing == null) pitches += note.midi
                if (!preview && note.playable) pitches += wrongPitch(note.midi)
                if (!preview && note.atMs in echoTargets) pitches += echoPitch(note.midi)
            }
            player.setBackingVolume(state.volume)
            player.prepare(pitches, backing)
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
                val missed = game?.advance(now - 32) ?: 0
                game?.takeMissCue(now)?.let { soundMistake(player, it, now, stray = false) }
                val leadRemaining = (game?.firstTargetMs ?: 0L) - now
                val beatMs = (60_000L / song.bpm).coerceAtLeast(1)
                val count = if (!state.preview && leadRemaining in 1..(4 * beatMs))
                    ((leadRemaining + beatMs - 1) / beatMs).toInt() else null
                positionMs = if (state.backingAsset == null) now
                    else displayClock.position(now, snap.observedUptimeMs, song.durationMs)
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
        displayClock.reset()
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
        // block on MediaPlayer/AVAudioPlayer queries merely to measure the intervening milliseconds.
        val at = snap.positionMs + (uptimeMs - snap.observedUptimeMs)
        val feedbackAt = maxOf(at, snap.positionMs)
        val engine = game ?: return
        when (val result = engine.tap(at)) {
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
        state = state.copy(stats = engine.stats)
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
        if (state.scene == Scene.Paused) state = state.copy(scene = Scene.Resuming, countdown = 3)
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
            echoesCaught = 0, collectedEchoMs = null)
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

    fun close() {
        val old = media
        media = null
        old?.onInterrupted = null
        old?.close()
        automaticNotes = emptyList()
        nextAutomaticNote = 0
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
