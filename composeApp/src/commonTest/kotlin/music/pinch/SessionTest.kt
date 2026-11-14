package music.pinch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import music.pinch.core.Chart
import music.pinch.core.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val song = Song("test", "Тест", "Test", Chart("Тест", 120, 4000, listOf(
        Note("a", 2000, 72, 250, .5f), Note("b", 2500, 76, 250, .8f)
    )))
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun session(host: Host) = Session(host, scope, listOf(song, Song("custom","Другой трек","Test")))

    private class Host : Platform {
        val values = mutableMapOf("pace" to "Standard")
        override fun readValue(key: String) = values[key]
        override fun writeValue(key: String, value: String) { values[key] = value }
        override suspend fun pickAudio(): FloatArray? = null
        val bestScore: Int? get() = values["record.test.Standard.bundled"]?.toInt()
        override var volume = .5f
        override var haptics = false
        override fun feedback() {}
        val players = mutableListOf<Player>()
        val player get() = players.last()
        override fun newMedia(durationMs: Long, instrument: Instrument) = Player().also(players::add)
    }

    private class Player : GameMedia {
        var status = Playback(ready = false, playing = false, ended = false, positionMs = 0, observedUptimeMs = 10_000)
        val sounds = mutableListOf<Pair<Int, Float>>()
        var prepared = emptySet<Int>()
        override var onInterrupted: (() -> Unit)? = null
        var starts = 0
        var closes = 0
        override fun prepare(pitches: Set<Int>) { prepared = pitches }
        override fun snapshot() = status
        override fun play() { starts++; status = status.copy(playing = true) }
        override fun pause() { status = status.copy(playing = false) }
        override fun note(midi: Int, gain: Float) { sounds += midi to gain }
        override fun close() { closes++ }

        fun tick(position: Long, uptime: Long = 10_000 + position) {
            status = status.copy(positionMs = position, observedUptimeMs = uptime)
        }

        fun ready() { status = status.copy(ready = true) }
        fun end() { status = status.copy(ended = true, playing = false) }
    }

    @Test
    fun loadedChartConnectsTimestampedTouchToTheCorrectVoiceAndRecord() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        assertEquals(setOf(72, 76), player.prepared)
        session.observe()
        session.tap(12_000)
        assertEquals(0, player.starts)
        assertTrue(player.sounds.isEmpty())
        player.ready()
        session.observe()
        player.tick(2_300)
        session.observe()
        session.tap(12_000) // touch delivered 300 ms after physical down, still a Perfect
        session.tap(12_010) // duplicate down
        player.tick(2_500)
        session.observe()
        session.tap(12_500)
        assertEquals(listOf(72 to .25f, 76 to .4f), player.sounds)
        player.end()
        session.observe()
        assertEquals(Scene.Result, session.state.scene)
        assertEquals(66_667, host.bestScore)
        assertTrue(session.state.newRecord)
        assertEquals(1, player.closes)
        session.observe()
        assertEquals(1, player.closes)
    }

    @Test
    fun backgroundRequiresResumeAndAFrozenCountdown() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(2_000)
        session.observe()
        session.tap(12_000)
        player.onInterrupted!!.invoke()
        player.tick(2_000, 82_000)
        session.observe()
        assertEquals(Scene.Paused, session.state.scene)
        assertFalse(player.status.playing)
        session.tap(82_000)
        session.resume()
        session.observe()
        assertEquals(Scene.Resuming, session.state.scene)
        assertEquals(1, player.starts)
        player.tick(2_000, 83_500)
        session.observe()
        assertEquals(2, player.starts)
        player.tick(2_500, 84_000)
        session.observe()
        session.tap(84_000)
        assertEquals(listOf(72, 76), player.sounds.map { it.first })
        assertEquals(100_000, session.state.stats?.score)
    }

    @Test
    fun silenceAndPreviewAndRestartHaveSeparateLifetimes() {
        val host = Host()
        val session = session(host)
        session.start(true)
        val preview = host.player
        assertTrue(session.state.preview)
        assertEquals(setOf(72, 76), preview.prepared)
        preview.ready()
        session.observe()
        preview.tick(2_000)
        session.observe()
        session.observe()
        session.tap(12_000)
        preview.end()
        session.observe()
        assertEquals(Scene.Menu, session.state.scene)
        assertNull(host.bestScore)
        assertEquals(listOf(72 to .25f), preview.sounds)
        session.start(false)
        repeat(5) {
            val previous = host.player
            session.restart()
            assertEquals(1, previous.closes)
            assertNull(previous.onInterrupted)
        }
        host.player.ready()
        session.observe()
        host.player.end()
        session.observe()
        assertEquals(2, session.state.stats?.missed)
        assertEquals(0, host.bestScore)
        assertTrue(host.players.drop(1).all { it.sounds.isEmpty() && it.closes == 1 })
    }

    @Test
    fun interruptionDuringLoadingRequiresAnExplicitResumeAndIgnoresObsoleteCallbacks() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val obsoleteCallback = host.player.onInterrupted!!
        session.restart()
        val player = host.player
        obsoleteCallback()
        assertEquals(Scene.Loading, session.state.scene)
        player.onInterrupted!!.invoke()
        player.ready()
        session.observe()
        assertEquals(Scene.Paused, session.state.scene)
        assertEquals(0, player.starts)
        session.resume()
        session.observe()
        player.tick(0, 11_500)
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        assertEquals(1, player.starts)
    }

    @Test
    fun slowNativePlaybackUsesMediaPositionEvenWhenTheWallClockCrossesBothNotes() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(1_800, 11_800)
        session.observe()
        player.tick(2_000, 12_900) // 200 ms of progress in 1,100 ms of uptime
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        assertEquals(2_000L, session.state.positionMs)
        assertEquals(0, session.state.stats?.missed)
        session.tap(12_900)
        assertEquals(listOf(72 to .25f), player.sounds)
        assertEquals(1, session.state.stats?.perfect)
    }

    @Test
    fun playerClockCanCatchUpAfterResumeWithoutANativeSeek() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(500)
        session.observe()
        player.onInterrupted!!.invoke()
        player.tick(500, 30_000)
        session.resume()
        session.observe()
        player.tick(500, 31_500)
        session.observe()
        player.tick(600, 32_600) // first position reports after resume lag behind playback
        session.observe()
        player.tick(700, 33_700)
        session.observe()
        assertEquals(0, session.state.stats?.missed)
        player.tick(2_000, 33_750) // native clock catches up 1,300 ms in 50 ms
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        assertEquals(2_000L, session.state.positionMs)
        session.tap(33_750)
        assertEquals(listOf(72 to .25f), player.sounds)
        assertEquals(1, session.state.stats?.perfect)
        assertEquals(0, player.closes)
    }

    @Test
    fun nativeDiscontinuityDiscardsThePartialRunAndAllowsRetry() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(2_000)
        session.observe()
        session.tap(12_000)
        assertEquals(1, session.state.stats?.perfect)
        val reason = "Плеер сообщил о перемотке"
        player.status = player.status.copy(error = reason)
        session.observe()
        assertEquals(Scene.Error, session.state.scene)
        assertEquals(1, player.closes)
        assertTrue(session.state.error.orEmpty().contains(reason))
        assertNull(host.bestScore)
        session.restart()
        assertEquals(Scene.Loading, session.state.scene)
        assertEquals(0, session.state.stats?.perfect)
        host.player.ready()
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
    }

    @Test
    fun playbackFailureClearsMediaAndDoesNotStoreAPartialScore() {
        val host = Host()
        val session = session(host)
        session.start(false)
        host.player.ready()
        session.observe()
        host.player.tick(2_000)
        session.tap(12_000)
        host.player.status = host.player.status.copy(error = "decoder")
        session.observe()
        assertEquals(Scene.Error, session.state.scene)
        assertNull(host.bestScore)
        assertNull(host.player.onInterrupted)
        assertEquals(1, host.player.closes)
    }

    @Test
    fun unavailableSongAndSlowPreparationBothHaveAnExit() {
        val host = Host()
        val session = session(host)
        session.selectSong("custom")
        session.start(false)
        assertEquals(Scene.Menu, session.state.scene)
        assertTrue(host.players.isEmpty())
        session.selectSong("test")
        session.start(false)
        session.observe()
        host.player.tick(0, 25_001)
        session.observe()
        assertEquals(Scene.Error, session.state.scene)
        assertEquals(1, host.player.closes)
        session.menu()
        assertEquals(Scene.Menu, session.state.scene)
    }

    @Test
    fun relaxedHasRoomBetweenJumpsAndItsOwnRecord() {
        val host = Host()
        val session = session(host)
        session.selectPace(Pace.Relaxed)
        val chart = requireNotNull(session.state.chart)
        assertTrue(chart.notes.zipWithNext().all { (a,b) -> b.atMs - a.atMs >= 740 })
        session.start(false)
        host.player.ready()
        session.observe()
        host.player.tick(2155)
        session.tap(12155)
        assertEquals(1, session.state.stats?.okay)
        host.player.end()
        session.observe()
        assertEquals(20000, session.state.bestScore)
        session.menu()
        session.selectPace(Pace.Standard)
        assertNull(session.state.bestScore)
    }

    @Test
    fun importsRoundTripAndSurviveReopeningTheApp() {
        val host = Host()
        host.writeValue("chart.custom", encodeChart(requireNotNull(song.bundled)))
        val session = session(host)
        session.selectSong("custom")
        session.selectInstrument(Instrument.Mallet)
        val reopened = session(host)
        assertEquals("custom", reopened.state.selectedSong.id)
        assertEquals(Instrument.Mallet, reopened.state.instrument)
        assertTrue("custom" in reopened.state.availableIds)
        assertEquals(listOf(72,76), reopened.state.chart!!.notes.map { it.midi })
    }
}
