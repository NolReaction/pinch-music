package music.pinch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.echoTargetTimes
import music.pinch.ui.PlanarPath
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
        val values = mutableMapOf<String, String>()
        override fun readValue(key: String) = values[key]
        override fun writeValue(key: String, value: String) { values[key] = value }
        override suspend fun pickAudio(): FloatArray? = null
        val bestScore: Int? get() = values["record.v3.test.bundled"]?.toInt()
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
        val strikes = mutableListOf<Float>()
        var snapshots = 0
        var prepared = emptySet<Int>()
        override var onInterrupted: (() -> Unit)? = null
        var starts = 0
        var closes = 0
        var backing: String? = null
        var backingGain = 0f
        override fun prepare(pitches: Set<Int>, backingAsset: String?) {
            prepared = pitches
            backing = backingAsset
        }
        override fun setBackingVolume(volume: Float) { backingGain = volume }
        override fun uptimeMs() = status.observedUptimeMs
        override fun snapshot() = status.also { snapshots++ }
        override fun play() { starts++; status = status.copy(playing = true) }
        override fun pause() { status = status.copy(playing = false) }
        override fun note(midi: Int, gain: Float) { sounds += midi to gain }
        override fun strike(gain: Float) { strikes += gain }
        override fun close() { closes++ }

        fun tick(position: Long, uptime: Long = 10_000 + position) {
            status = status.copy(positionMs = position, observedUptimeMs = uptime)
        }

        fun ready() { status = status.copy(ready = true) }
        fun end() { status = status.copy(ended = true, playing = false) }
    }

    @Test
    fun planarLampSoundsFollowMediaCrossingsThroughResumeAndRestart() {
        val host = Host()
        host.writeValue("stage.mode", "obsolete")
        val session = Session(host, scope)
        assertEquals(StageMode.Orbit, session.state.stageMode)
        session.selectStageMode(StageMode.Planar)
        assertEquals(StageMode.Planar, Session(host, scope).state.stageMode)
        val cues = PlanarPath(requireNotNull(session.state.chart)).ledCues
        assertTrue(cues.size > 3)
        session.start(true)
        session.selectStageMode(StageMode.Orbit)
        assertEquals(StageMode.Planar, session.state.stageMode)
        val player = host.player
        player.ready()
        session.observe()
        assertEquals(cues.map { it.midi }.toSet(), player.prepared)
        val first = cues.first()
        val last = cues.last()
        player.tick(first.atMs - 1)
        session.observe()
        assertTrue(player.sounds.isEmpty())
        player.tick(first.atMs)
        session.observe()
        session.observe()
        session.pause()
        session.observe()
        session.resume()
        session.observe()
        player.tick(first.atMs, 10_000 + first.atMs + 1_500)
        session.observe()
        session.observe()
        assertEquals(listOf(first.midi to .10f), player.sounds)
        session.volume(.25f)
        player.tick(last.atMs, 10_000 + last.atMs + 1_500)
        session.observe()
        assertEquals(listOf(first.midi to .10f, last.midi to .05f), player.sounds)
        session.restart()
        host.player.ready()
        session.observe()
        host.player.tick(last.atMs + 500)
        session.observe()
        assertTrue(host.player.sounds.isEmpty())
        session.menu()
        session.selectStageMode(StageMode.Orbit)
        session.start(true)
        assertTrue(host.player.prepared.isEmpty())
    }

    @Test
    fun extraLampTapRingsOnceAndLeavesTheChartScoreAlone() {
        val host = Host()
        val chart = Chart("Полоса", 120, 5_000, listOf(
            Note("a", 500, 64, 400, .8f), Note("b", 3_300, 67, 400, .8f)
        ))
        val session = Session(host, scope, listOf(song.copy(bundled = chart, backingAsset = "demo.m4a")))
        session.selectStageMode(StageMode.Planar)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        session.tap(10_500)
        assertEquals(500L, session.state.lastPanelHitMs)
        val previousStats = session.state.stats
        val cue = PlanarPath(chart).ledCues.first { it.atMs in 900L..2_700L }
        player.tick(cue.atMs - 15)
        session.observe()
        player.sounds.clear()
        session.tap(10_000 + cue.atMs - 15)
        session.tap(10_000 + cue.atMs + 10)
        player.tick(cue.atMs)
        session.observe()
        assertEquals(listOf(cue.midi to .21f), player.sounds)
        assertEquals(cue.atMs, session.state.lastLedTapMs)
        assertEquals(500L, session.state.lastPanelHitMs)
        assertEquals(previousStats, session.state.stats)
        session.tap(13_300)
        player.end()
        session.observe()
        assertEquals(100_000, session.state.stats?.score)
        session.restart()
        assertNull(session.state.lastPanelHitMs)
        assertNull(session.state.lastLedTapMs)
    }

    @Test
    fun earlyLampTapCanAlsoScoreThePreviousGoldWithoutReplayingTheLamp() {
        val host = Host()
        val chart = Chart("Перекрытие", 120, 2_500, listOf(
            Note("a", 500, 64, 400, .8f), Note("b", 1_208, 67, 400, .8f)
        ))
        val session = Session(host, scope, listOf(song.copy(bundled = chart, backingAsset = "demo.m4a")))
        session.selectStageMode(StageMode.Planar)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        val cue = PlanarPath(chart).ledCues.first()
        player.tick(cue.atMs - 12)
        session.observe()
        session.tap(10_000 + cue.atMs - 12)
        session.tap(10_000 + cue.atMs - 6)
        player.tick(cue.atMs)
        session.observe()
        assertEquals(listOf(cue.midi to .21f), player.sounds)
        assertEquals(1, player.strikes.size)
        assertEquals(cue.atMs, session.state.lastLedTapMs)
        assertEquals(500L, session.state.lastPanelHitMs)
        assertEquals(1, session.state.stats?.combo)
        assertEquals(0, session.state.stats?.stray)
    }

    @Test
    fun loadedChartConnectsTimestampedTouchToTheCorrectVoiceAndRecord() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        assertEquals(setOf(72, 73, 76, 77), player.prepared)
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
        assertEquals(listOf(72, 76), player.sounds.map { it.first })
        assertEquals(.25f, player.sounds.first().second)
        assertEquals(.4f, player.sounds.last().second)
        player.end()
        session.observe()
        assertEquals(Scene.Result, session.state.scene)
        assertEquals(100_000, host.bestScore)
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
        assertEquals(2_000L, session.positionMs)
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
        assertEquals(2_000L, session.positionMs)
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
    fun nativeChartTimingAndNewScoresSurviveUpgradingFromEitherOldPace() {
        val host = Host()
        host.writeValue("pace", "Relaxed")
        host.writeValue("record.test.Relaxed.bundled", "73100")
        val session = session(host)
        assertEquals(song.bundled, session.state.chart)
        assertNull(session.state.bestScore)
        session.start(false)
        host.player.ready()
        session.observe()
        host.player.tick(2155)
        session.tap(12155)
        assertEquals(1, session.state.stats?.okay)
        host.player.end()
        session.observe()
        assertEquals(20000, session.state.bestScore)
        assertEquals("73100", host.readValue("record.test.Relaxed.bundled"))
        assertEquals(20000, session(host).state.bestScore)
    }

    @Test
    fun missedTargetsSoundOnceAndStaleFramesNeverPlayACluster() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        for (time in listOf(2_244L, 2_255L, 2_744L, 2_745L, 3_500L)) {
            player.tick(time)
            session.observe()
        }
        assertEquals(listOf(73, 77), player.sounds.map { it.first })
        assertTrue(player.sounds.all { it.second in 0.03f..0.16f })
        assertEquals(2, session.state.stats?.missed)
        session.restart()
        host.player.ready()
        session.observe()
        host.player.tick(3_500L)
        session.observe()
        assertTrue(host.player.sounds.isEmpty())
        assertEquals(2, session.state.stats?.missed)
    }

    @Test
    fun strayFeedbackUsesTheNearestTargetButSpamStillCountsWithoutRetriggeringAudio() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(2_000)
        session.tap(12_000)
        for (time in listOf(2_280L, 2_285L, 2_299L)) {
            player.tick(time)
            session.tap(10_000 + time)
        }
        assertEquals(listOf(72, 77), player.sounds.map { it.first })
        assertEquals(3, session.state.stats?.stray)
        player.tick(2_500)
        session.tap(12_500)
        assertEquals(listOf(72, 77, 76), player.sounds.map { it.first })
    }

    @Test
    fun stereoIsTheDefaultAndOnlyItsUnmodifiedChartRequestsTheBacking() {
        val host = Host()
        val stereo = song.copy(id = "stereo-love", backingAsset = "backing/stereo-love.m4a")
        val songs = listOf(song, stereo)
        val session = Session(host, scope, songs)
        assertEquals(stereo.id, session.state.selectedSong.id)
        listOf(true, false).forEach { preview ->
            session.start(preview)
            assertEquals(stereo.backingAsset, host.player.backing)
            assertEquals(host.volume, host.player.backingGain)
            session.volume(.3f)
            assertEquals(.3f, host.player.backingGain)
            session.menu()
        }
        session.selectSong(song.id)
        assertEquals(song.id, Session(host, scope, songs).state.selectedSong.id)
        session.start(false)
        assertNull(host.player.backing)
        session.menu()
        host.writeValue("selected.song", stereo.id)
        host.writeValue("chart.${stereo.id}", encodeChart(requireNotNull(song.bundled)))
        val imported = Session(host, scope, songs)
        assertEquals(stereo.id, imported.state.selectedSong.id)
        assertNull(imported.state.backingAsset)
        imported.start(false)
        assertNull(host.player.backing)
        imported.menu()
        imported.restoreBundled()
        imported.start(false)
        assertEquals(stereo.backingAsset, host.player.backing)
    }

    @Test
    fun entireFeaturedArrangementCanBePlayedMissedAndPreviewedAgainstTheSameTimeline() {
        val host = Host()
        val session = Session(host, scope)
        val chart = requireNotNull(session.state.chart)
        val notes = chart.notes.filter { it.playable }
        fun run(preview: Boolean, playEveryNote: Boolean): Player {
            session.start(preview)
            val player = host.player
            assertEquals("backing/stereo-love.m4a", player.backing)
            player.ready()
            session.observe()
            var nextDown = 0
            for (position in 0L..chart.durationMs step 16) {
                player.tick(position)
                session.observe()
                if (playEveryNote) {
                    while (nextDown < notes.size && notes[nextDown].atMs <= position) {
                        session.tap(10_000L + notes[nextDown++].atMs)
                    }
                }
            }
            player.end()
            session.observe()
            return player
        }
        val perfect = run(preview = false, playEveryNote = true)
        assertTrue(perfect.sounds.isEmpty())
        assertEquals(notes.size, perfect.strikes.size)
        assertTrue(perfect.strikes.all { it > 0f && it < host.volume })
        assertEquals(100_000, session.state.stats?.score)
        session.menu()
        val skipped = run(preview = false, playEveryNote = false)
        assertEquals(notes.size, session.state.stats?.missed)
        assertEquals(0, session.state.stats?.score)
        assertEquals(100_000, session.state.bestScore)
        assertEquals(notes.map { it.midi + 1 }, skipped.sounds.map { it.first })
        session.menu()
        val preview = run(preview = true, playEveryNote = false)
        assertTrue(preview.sounds.isEmpty() && preview.strikes.isEmpty())
        assertTrue(preview.prepared.isEmpty())
        assertEquals(Scene.Menu, session.state.scene)
    }

    @Test
    fun touchesUseTheLastAudioAnchorWithoutPollingTheDecoderOrChangingFrameTime() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        player.ready()
        session.observe()
        player.tick(2_470L, 18_000L)
        session.observe()
        val reads = player.snapshots
        player.tick(2_500L, 18_030L)
        session.tapNow()
        session.tap(18_032L)
        assertEquals(reads, player.snapshots)
        assertEquals(1, session.state.stats?.perfect)
        assertEquals(2_470L, session.positionMs)
    }

    @Test
    fun automaticBarCanBeTappedOnceWithoutPenaltyOrDoublingItsTune() {
        val host = Host()
        val chart = requireNotNull(song.bundled)
        val between = chart.notes.first().copy(id = "automatic", atMs = 2_390, playable = false)
        val part = chart.copy(notes = listOf(chart.notes.first(), between, chart.notes.last()))
        val session = Session(host, scope, listOf(song.copy(bundled = part, backingAsset = "full.m4a")))
        session.start(false)
        host.player.ready()
        session.observe()
        session.tap(12_000)
        session.tap(12_390)
        session.tap(12_394)
        assertEquals(1, session.state.stats?.combo)
        assertEquals(0, session.state.stats?.stray)
        session.tap(12_500)
        assertEquals(100_000, session.state.stats?.score)
        assertEquals(3, host.player.strikes.size)
        assertTrue(host.player.sounds.isEmpty())
    }

    @Test
    fun decoderFramesDoNotInvalidateMenusAndHudBetweenJudgements() {
        val host = Host()
        val session = session(host)
        session.start(true)
        val player = host.player
        player.ready()
        session.observe()
        val ui = session.state
        for (ms in 16L..800L step 16) {
            player.tick(ms)
            session.observe()
            assertTrue(ui === session.state)
            assertEquals(ms, session.positionMs)
        }
    }

    @Test
    fun previewOfUnbackedChordsPlaysAllSimultaneousVoicesOnce() {
        val host = Host()
        val chart = requireNotNull(song.bundled)
        val chord = song.copy(bundled = chart.copy(notes = listOf(chart.notes.first(),
            chart.notes.first().copy(id = "harmony", midi = 79), chart.notes.last())))
        val session = Session(host, scope, listOf(chord))
        session.start(true)
        host.player.ready()
        session.observe()
        host.player.tick(2_004)
        session.observe()
        session.observe()
        assertEquals(listOf(72, 79), host.player.sounds.map { it.first })
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

    @Test
    fun echoesAreOptionalAndSurvivePauseButResetForAnotherRun() {
        val host = Host()
        val session = Session(host, scope)
        val chart = requireNotNull(session.state.chart)
        val echoes = chart.echoTargetTimes()
        assertEquals(40, echoes.size)
        assertEquals(echoes.size, session.state.echoTotal)
        val firstEcho = echoes.min()
        for (collect in listOf(true, false)) {
            session.start(false)
            assertEquals(0, session.state.echoesCaught)
            assertNull(session.state.collectedEchoMs)
            val player = host.player
            player.ready()
            session.observe()
            var uptimeOffset = 10_000L
            for (note in chart.notes.filter { it.playable || it.atMs in echoes }) {
                val uptime = uptimeOffset + note.atMs
                player.tick(note.atMs, uptime)
                session.observe()
                if (note.playable || collect) session.tap(uptime)
                if (collect && note.atMs == firstEcho) {
                    session.tap(uptime + 8)
                    assertEquals(1, session.state.echoesCaught)
                    assertEquals(firstEcho, session.state.collectedEchoMs)
                    assertEquals("Эхо +1", session.state.reaction)
                    assertEquals(note.midi + 12, player.sounds.single().first)
                    session.pause()
                    session.resume()
                    session.observe()
                    uptimeOffset += 2_000L
                    player.tick(note.atMs, uptimeOffset + note.atMs)
                    session.observe()
                    assertEquals(1, session.state.echoesCaught)
                }
            }
            player.end()
            session.observe()
            val expected = if (collect) echoes.size else 0
            assertEquals(expected, session.state.echoesCaught)
            assertEquals(expected, player.sounds.size)
            assertEquals(100_000, session.state.stats?.score)
            assertEquals(159, session.state.stats?.maxCombo)
        }
        session.start(true)
        assertEquals(0, session.state.echoesCaught)
        assertNull(session.state.collectedEchoMs)
    }
}
