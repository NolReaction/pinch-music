package music.pinch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import music.pinch.core.Chart
import music.pinch.core.Grade
import music.pinch.core.RhythmGame
import music.pinch.core.TapResult

/** Session commands, native events and frame observations all run on the UI thread. */
internal class Session(private val platform: Platform) : Actions {
    var state by mutableStateOf(ScreenState(bestScore = platform.bestScore, volume = platform.volume, haptics = platform.haptics))
        private set
    var media by mutableStateOf<GameMedia?>(null)
        private set
    private var chart: Chart? = null
    private var game: RhythmGame? = null
    private var hasPlayed = false
    private var playRequestedAt = 0L
    private var waitingSince: Long? = null
    private var resumeAt: Long? = null
    private var reactionUntil = 0L

    override fun start(preview: Boolean) {
        close()
        state = ScreenState(scene = Scene.Loading, preview = preview, bestScore = platform.bestScore,
            volume = platform.volume, haptics = platform.haptics)
        try {
            val song = decodeDemoChart(platform.chartJson())
            chart = song
            game = if (preview) null else RhythmGame(song)
            state = state.copy(title = song.title, durationMs = song.durationMs, bpm = song.bpm,
                lastHitWindowMs = song.notes.last().atMs + 120, stats = game?.stats)
            val player = platform.newMedia(preview)
            media = player
            player.onInterrupted = { if (media === player) pause() }
            player.prepare(if (preview) emptySet() else song.notes.map { it.midi }.toSet())
            player.setPreviewVolume(state.volume)
        } catch (e: Exception) {
            fail("Не удалось открыть демо. " + e.message.orEmpty())
        }
    }

    override fun restart() = start(state.preview)

    fun observe() {
        val player = media ?: return
        val snap = player.snapshot()
        if (snap.error != null) {
            fail("Не удалось воспроизвести демо. " + snap.error)
            return
        }
        when (state.scene) {
            Scene.Loading, Scene.Resuming -> {
                if (!snap.ready) {
                    val start = waitingSince ?: snap.observedUptimeMs.also { waitingSince = it }
                    if (snap.observedUptimeMs - start >= 15_000) fail("Плеер не смог подготовить видео и звук.")
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
                    } else if (snap.observedUptimeMs - playRequestedAt > 5000) fail("Видеоплеер не начал воспроизведение.")
                    return
                }
                waitingSince = null
                // Decode stalls and clock corrections are normal. Only the native player
                // can identify a seek; it reports a discontinuity through snapshot.error.
                hasPlayed = true
                val song = chart ?: return
                val now = snap.positionMs.coerceIn(0L, song.durationMs)
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
        val previous = platform.bestScore
        val record = previous == null || final.score > previous
        if (record) platform.bestScore = final.score
        close()
        state = state.copy(scene = Scene.Result, positionMs = state.durationMs, reaction = null,
            countdown = null, stats = final, bestScore = platform.bestScore, newRecord = record)
    }

    override fun menu() {
        close()
        state = ScreenState(volume = platform.volume, haptics = platform.haptics, bestScore = platform.bestScore)
    }

    override fun volume(value: Float) {
        platform.volume = value.coerceIn(0f, 1f)
        state = state.copy(volume = platform.volume)
        media?.setPreviewVolume(state.volume)
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
        chart = null
        game = null
        waitingSince = null
        resumeAt = null
        hasPlayed = false
        reactionUntil = 0
    }
}
