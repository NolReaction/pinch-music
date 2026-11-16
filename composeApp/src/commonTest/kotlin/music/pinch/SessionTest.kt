package music.pinch

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.Dispatchers
import music.pinch.ui.PreparedStage
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.cascadeChart
import music.pinch.core.cascadePreRollMs
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
    private fun session(host: Host, songs: List<Song> = listOf(song, Song("custom","Другой трек","Test"))) =
        Session(host, scope, songs, Dispatchers.Unconfined)

    private class Host : Platform {
        val values = mutableMapOf<String, String>()
        override fun readValue(key: String) = values[key]
        override fun writeValue(key: String, value: String) { values[key] = value }
        override suspend fun pickAudio(): FloatArray? = null
        var renderGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val renders = mutableListOf<Chart>()
        val renderInstruments = mutableListOf<Instrument?>()
        val renderBundleIds = mutableListOf<String?>()
        override suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack {
            renderGate?.await()
            renders += chart
            renderInstruments += previewInstrument
            renderBundleIds += bundledId
            return ArrangementTrack("/cache/${previewInstrument?.name ?: "band"}.wav")
        }
        val bestScore: Int? get() = values["record.landings.v1.Orbit.test.bundled"]?.toInt()
        override var volume = .5f
        override var haptics = false
        override fun feedback() {}
        val players = mutableListOf<Player>()
        val player get() = players.last()
        var lastInstrument: Instrument? = null
        override fun newMedia(durationMs: Long, instrument: Instrument): Player {
            lastInstrument = instrument
            return Player(durationMs).also(players::add)
        }
    }

    private class Player(private val durationMs: Long) : GameMedia {
        var status = Playback(ready = false, playing = false, ended = false, positionMs = 0, observedUptimeMs = 10_000)
        val sounds = mutableListOf<Pair<Int, Float>>()
        val strikes = mutableListOf<Float>()
        var snapshots = 0
        var prepared = emptySet<Int>()
        override var onInterrupted: (() -> Unit)? = null
        var starts = 0
        var closes = 0
        var backing: String? = null
        var generated: String? = null
        val durations = mutableListOf<Int>()
        var backingGain = 0f
        override fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack?) {
            prepared = pitches
            backing = backingAsset
            generated = arrangement?.path
        }
        override fun setBackingVolume(volume: Float) { backingGain = volume }
        override fun uptimeMs() = status.observedUptimeMs
        override fun snapshot() = status.also { snapshots++ }
        override fun play() { starts++; status = status.copy(playing = true) }
        override fun pause() { status = status.copy(playing = false) }
        override fun note(midi: Int, gain: Float, durationMs: Int) {
            sounds += midi to gain
            durations += durationMs
        }
        override fun strike(gain: Float) { strikes += gain }
        override fun close() { closes++ }

        fun tick(position: Long, uptime: Long = 10_000 + position) {
            status = status.copy(positionMs = position, observedUptimeMs = uptime)
        }

        fun ready() { status = status.copy(ready = true) }
        fun end() {
            status = status.copy(ended = true, playing = false, positionMs = durationMs,
                observedUptimeMs = status.observedUptimeMs + (durationMs - status.positionMs))
        }
    }

    // Keep playback time zero anchored at 10,000 in tests that deliver timestamped taps.
    private fun Session.readyToPlay(player: Player) {
        val roll = if (state.preview) 0L else requireNotNull(state.chart).cascadePreRollMs()
        val start = player.uptimeMs()
        if (roll > 0) player.tick(0, start - roll)
        player.ready()
        stagePresented(state.attempt)
        observe()
        if (state.scene == Scene.CountIn) {
            player.tick(0, start)
            observe()
        }
        assertEquals(Scene.Playing, state.scene)
    }

    @Test
    fun menuMetadataAndImmediatePlayDoNotBuildUnselectedSongsOrStartAnObsoleteAttempt() {
        val work = ArrayDeque<Runnable>()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        }
        val built = mutableListOf<String>()
        fun arranged(id: String) = Song.arranged(id, id, "", 120, 4000) {
            built += id
            requireNotNull(song.bundled).copy(title = id)
        }
        val host = Host()
        val session = Session(host, scope, listOf(arranged("first"), arranged("second")), worker)
        assertEquals(setOf("first", "second"), session.state.availableIds)
        assertEquals(120, session.state.bpm)
        assertEquals(4000L, session.state.durationMs)
        assertEquals(listOf(4000L, 4000L), session.state.songs.map { it.bundledDurationMs })
        assertTrue(built.isEmpty())

        session.start(preview = false)
        assertEquals(Scene.Loading, session.state.scene)
        session.menu()
        session.selectSong("second")
        session.start(preview = true)
        session.start(preview = false)
        while (work.isNotEmpty()) work.removeFirst().run()
        assertEquals(listOf("second"), built)
        assertEquals(1, host.players.size)
        assertTrue(session.state.preview)
        assertEquals("second", session.state.chart?.title)
        assertTrue(session.stage is PreparedStage.Orbit)
        session.dispose()
    }

    @Test
    fun menuAndPauseRespondWhileTheCourseIsBuildingAndAReplayReusesIt() {
        val work = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        }
        val host = Host()
        val second = song.copy(id = "other", title = "Другая")
        val session = Session(host, scope, listOf(song, second), dispatcher)
        session.selectStageMode(StageMode.Planar)
        session.start(preview = true)
        val obsolete = host.player
        session.start(preview = false)
        assertEquals(1, host.players.size)
        assertTrue(session.state.preview)
        obsolete.ready()
        session.observe()
        assertEquals(Scene.Loading, session.state.scene)
        assertNull(session.stage)
        session.menu()
        session.selectSong(second.id)
        session.start(preview = false)
        val active = host.player
        active.ready()
        active.onInterrupted?.invoke()
        while (work.isNotEmpty()) work.removeFirst().run()
        assertEquals(Scene.Paused, session.state.scene)
        val prepared = requireNotNull(session.stage)
        assertEquals(0, obsolete.starts)
        assertEquals(0, active.starts)
        assertEquals(1, obsolete.closes)
        session.stagePresented(session.state.attempt)
        session.resume()
        session.observe()
        active.tick(0, 12_000)
        session.observe()
        assertEquals(1, active.starts)
        session.restart()
        assertTrue(session.stage === prepared)
        assertTrue(work.isEmpty())
        session.dispose()
    }

    @Test
    fun revisedBuiltInScoreAndFixedBackingHaveIndependentSettings() {
        val host = Host()
        val backing = song.copy(id = "stereo-love", backingAsset = "backing/stereo-love.m4a")
        val revised = song.copy(bundledRevision = 2)
        host.values["record.v3.test.bundled.v2"] = "99999"
        host.values["record.landings.v1.Orbit.test.bundled"] = "91000"
        host.values["record.landings.v1.Orbit.test.bundled.v2"] = "46000"
        val session = session(host, listOf(backing, revised))
        assertEquals(backing.backingAsset, session.state.backingAsset)
        session.selectInstrument(Instrument.Mallet)
        assertNull(session.state.backingAsset)
        session.start(true)
        session.readyToPlay(host.player)
        host.player.tick(2000)
        session.observe()
        assertEquals(Instrument.Mallet, host.lastInstrument)
        assertEquals("/cache/Mallet.wav", host.player.generated)
        assertTrue(host.player.prepared.isEmpty() && host.player.sounds.isEmpty())
        session.menu()
        session.selectRecording()
        session.start(true)
        assertEquals(backing.backingAsset, host.player.backing)
        session.menu()
        session.selectSong(revised.id)
        assertEquals(46000, session.state.bestScore)
        session.selectInstrument(Instrument.Synth)
        session.selectSong(backing.id)
        assertEquals(backing.backingAsset, session.state.backingAsset)
        assertEquals("Synth", host.values["instrument"])
        session.dispose()
    }

    @Test
    fun recordedCascadePreviewUsesOnlyTheBackingThroughGuidesAndResume() {
        val host = Host()
        val session = session(host, songLibrary)
        session.selectStageMode(StageMode.Planar)
        session.start(true)
        session.selectStageMode(StageMode.Orbit)
        assertEquals(StageMode.Planar, session.state.stageMode)
        val path = (session.stage as PreparedStage.Cascade).path
        val guides = path.panels.filter { it.railSamples.isNotEmpty() }
        assertTrue(guides.isNotEmpty())
        val player = host.player
        assertEquals("backing/stereo-love.m4a", player.backing)
        assertTrue(player.prepared.isEmpty())
        session.readyToPlay(player)
        val first = path.attacks[guides.first().firstAttack].atMs
        player.tick(first + 200)
        session.observe()
        session.pause()
        session.resume()
        session.observe()
        player.tick(first + 200, 11_700 + first)
        session.observe()
        player.tick(path.attacks[guides.last().lastAttack].atMs)
        session.observe()
        assertTrue(player.sounds.isEmpty() && player.strikes.isEmpty())
    }

    @Test
    fun rollingBetweenKeysIsSilentAndScoresOnlyTheNextLanding() {
        val host = Host()
        val session = session(host, songLibrary)
        session.selectStageMode(StageMode.Planar)
        session.start(false)
        val course = (session.stage as PreparedStage.Cascade).path
        val railTime = course.attacks.zipWithNext().firstNotNullOf { (from, to) ->
            (from.atMs + 220..to.atMs - 220 step 50).firstOrNull { course.frame(it).rollingPanel != null }
        }
        val next = course.attacks.first { it.atMs > railTime }
        val player = host.player
        session.readyToPlay(player)
        player.tick(railTime)
        session.observe()
        val feedback = session.state.panelFeedback
        val stats = session.state.stats
        player.sounds.clear()
        session.tap(10_000 + railTime)
        session.tap(10_008 + railTime)
        assertEquals(feedback, session.state.panelFeedback)
        assertEquals(stats, session.state.stats)
        assertTrue(player.strikes.isEmpty() && player.sounds.isEmpty())
        player.tick(next.atMs)
        session.observe()
        session.tap(10_000 + next.atMs)
        assertEquals(next.atMs, session.state.panelFeedback.last().noteAtMs)
        assertTrue(session.state.panelFeedback.last().hit)
        assertEquals(1, player.strikes.size)
        session.restart()
        assertTrue(session.state.panelFeedback.isEmpty())
    }

    @Test
    fun skippedMelodyAndChordVoicesSoundOnceAndOnlyVisibleKeysAreScored() {
        val raw = Chart("Тест", 120, 3000, listOf(
            Note("target", 500, 72, 200, .9f), Note("unison", 500, 72, 40, .4f),
            Note("harmony", 500, 64, 200, .5f, false), Note("turn", 630, 74, 60, .4f),
            Note("end", 1600, 67, 300, .9f)
        ))
        val host = Host()
        host.writeValue("record.v3.test.bundled", "90000")
        host.writeValue("record.landings.v1.Orbit.test.bundled", "50000")
        val session = session(host, listOf(song.copy(bundled = raw)))
        session.selectStageMode(StageMode.Planar)
        assertNull(session.state.bestScore)
        val score = requireNotNull(session.state.chart)
        assertEquals(listOf("target", "end"), score.notes.map { it.id })
        session.start(false)
        assertEquals(score.notes.map { it.atMs }, (session.stage as PreparedStage.Cascade).path.attacks.map { it.atMs })
        val player = host.player
        session.readyToPlay(player)
        for (time in listOf(500L, 630L, 1600L)) {
            player.tick(time)
            session.observe()
            if (time != 630L) session.tap(10_000 + time)
        }
        assertEquals(listOf(72, 64, 74, 67), player.sounds.map { it.first })
        player.end()
        session.observe()
        assertEquals(2, session.state.stats?.maxCombo)
        assertEquals(100_000, session.state.bestScore)
        session.menu()
        session.start(true)
        val preview = host.player
        session.readyToPlay(preview)
        for (time in listOf(500L, 630L, 1600L)) {
            preview.tick(time)
            session.observe()
        }
        assertEquals("/cache/Piano.wav", preview.generated)
        assertTrue(preview.prepared.isEmpty() && preview.sounds.isEmpty())
        session.menu()
        session.selectStageMode(StageMode.Orbit)
        assertEquals(raw.cascadeChart(), session.state.chart)
        assertEquals(50_000, session.state.bestScore)
        session.selectStageMode(StageMode.Planar)
        assertEquals(100_000, session.state.bestScore)
    }

    @Test
    fun aLateHitPlaysOneShortenedChordWithoutTheNearbyAutomaticAttack() {
        val chart = Chart("Тест", 120, 4000, listOf(
            Note("before", 905, 71, 50, .5f, false),
            Note("key", 1000, 72, 400, .9f), Note("unison", 1000, 72, 90, .3f, false),
            Note("harmony", 1000, 64, 260, .6f, false), Note("repeat", 1090, 72, 50, .6f, false),
            Note("turn", 1120, 74, 50, .4f, false), Note("phrase", 1380, 67, 240, .7f, false),
            Note("end", 2000, 74, 300, .8f),
        ), authoredLandings = true)
        val host = Host()
        val session = session(host, listOf(song.copy(bundled = chart)))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        for (time in listOf(905L, 1000L, 1090L, 1120L, 1130L)) {
            player.tick(time)
            session.observe()
        }
        assertTrue(player.sounds.isEmpty())
        session.tapNow()
        session.tapNow()
        assertEquals(listOf(72, 64), player.sounds.map { it.first })
        assertEquals(listOf(270, 130), player.durations)
        player.tick(1380)
        session.observe()
        assertEquals(listOf(72, 64, 67), player.sounds.map { it.first })
        session.menu()
        session.start(true)
        val preview = host.player
        session.readyToPlay(preview)
        for (time in chart.notes.map { it.atMs }.distinct()) {
            preview.tick(time)
            session.observe()
        }
        assertEquals(chart, host.renders.last())
        assertEquals("/cache/Piano.wav", preview.generated)
        assertTrue(preview.sounds.isEmpty())
        session.dispose()
    }

    @Test
    fun generatedBandIsIndependentOfThePlayersLeadAndIsReusedAcrossTimbres() {
        val chart = requireNotNull(song.bundled).copy(accompaniment = listOf(
            BackingNote(0, 48, 400, .6f, BackingVoice.Bass),
        ))
        val host = Host()
        val session = session(host, listOf(song.copy(bundled = chart, backingAsset = "full.m4a")))
        assertTrue(host.renders.isEmpty())
        session.selectInstrument(Instrument.Mallet)
        for (preview in listOf(false, true)) {
            session.start(preview)
            val player = host.player
            assertEquals(if (preview) "/cache/Synth.wav" else "/cache/band.wav", player.generated)
            assertNull(player.backing)
            assertFalse(48 in player.prepared)
            session.readyToPlay(player)
            player.tick(2000)
            session.observe()
            assertTrue(player.sounds.isEmpty())
            session.menu()
            session.selectInstrument(Instrument.Synth)
        }
        assertEquals(listOf(chart, chart), host.renders)
        assertEquals(listOf(null, Instrument.Synth), host.renderInstruments)
        assertEquals(listOf<String?>(song.id, song.id), host.renderBundleIds)
        session.selectRecording()
        session.start(true)
        assertEquals("full.m4a", host.player.backing)
        assertNull(host.player.generated)
        assertTrue(host.player.prepared.isEmpty())
        session.dispose()
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
        session.readyToPlay(player)
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
        session.readyToPlay(player)
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
        assertEquals("/cache/Piano.wav", preview.generated)
        assertTrue(preview.prepared.isEmpty())
        session.readyToPlay(preview)
        preview.tick(2_000)
        session.observe()
        session.observe()
        session.tap(12_000)
        preview.end()
        session.observe()
        assertEquals(Scene.Menu, session.state.scene)
        assertNull(host.bestScore)
        assertTrue(preview.sounds.isEmpty())
        session.start(false)
        repeat(5) {
            val previous = host.player
            session.restart()
            assertEquals(1, previous.closes)
            assertNull(previous.onInterrupted)
        }
        session.readyToPlay(host.player)
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
        session.stagePresented(session.state.attempt)
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
        session.readyToPlay(player)
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
        session.readyToPlay(player)
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
        session.readyToPlay(player)
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
        session.readyToPlay(host.player)
        assertEquals(Scene.Playing, session.state.scene)
    }

    @Test
    fun playbackFailureClearsMediaAndDoesNotStoreAPartialScore() {
        val host = Host()
        val session = session(host)
        session.start(false)
        session.readyToPlay(host.player)
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
        session.readyToPlay(host.player)
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
    fun recordedMissCuesSoundOnceAndStaleFramesNeverPlayACluster() {
        val host = Host()
        val session = session(host, listOf(song.copy(backingAsset = "full.m4a")))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        for (time in listOf(2_244L, 2_255L, 2_744L, 2_745L, 3_500L)) {
            player.tick(time)
            session.observe()
        }
        assertEquals(listOf(73, 77), player.sounds.map { it.first })
        assertTrue(player.sounds.all { it.second in 0.03f..0.16f })
        assertEquals(2, session.state.stats?.missed)
        session.restart()
        session.readyToPlay(host.player)
        host.player.tick(3_500L)
        session.observe()
        assertTrue(host.player.sounds.isEmpty())
        assertEquals(2, session.state.stats?.missed)
    }

    @Test
    fun strayFeedbackInSampledModeIsVisualAndDebouncesARepeatedDown() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.tick(2_000)
        session.tap(12_000)
        for (time in listOf(2_280L, 2_285L, 2_299L)) {
            player.tick(time)
            session.tap(10_000 + time)
        }
        assertEquals(listOf(72), player.sounds.map { it.first })
        assertEquals(1, session.state.stats?.stray)
        player.tick(2_500)
        session.tap(12_500)
        assertEquals(listOf(72, 76), player.sounds.map { it.first })
    }

    @Test
    fun stereoIsTheDefaultAndOnlyItsUnmodifiedChartRequestsTheBacking() {
        val host = Host()
        val stereo = song.copy(id = "stereo-love", backingAsset = "backing/stereo-love.m4a")
        val songs = listOf(song, stereo)
        val session = session(host, songs)
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
        assertEquals(song.id, session(host, songs).state.selectedSong.id)
        session.start(false)
        assertNull(host.player.backing)
        session.menu()
        host.writeValue("selected.song", stereo.id)
        host.writeValue("chart.${stereo.id}", encodeChart(requireNotNull(song.bundled)))
        val imported = session(host, songs)
        assertEquals(stereo.id, imported.state.selectedSong.id)
        assertNull(imported.state.backingAsset)
        imported.start(true)
        assertNull(host.player.backing)
        assertNull(host.renderBundleIds.last())
        assertEquals("/cache/Piano.wav", host.player.generated)
        assertTrue(host.player.prepared.isEmpty())
        imported.menu()
        imported.restoreBundled()
        imported.start(false)
        assertEquals(stereo.backingAsset, host.player.backing)
    }

    @Test
    fun entireFeaturedArrangementCanBePlayedMissedAndPreviewedAgainstTheSameTimeline() {
        val host = Host()
        val session = session(host, songLibrary)
        val chart = requireNotNull(session.state.chart)
        val notes = chart.notes
        fun run(preview: Boolean, playEveryNote: Boolean): Player {
            session.start(preview)
            val player = host.player
            assertEquals("backing/stereo-love.m4a", player.backing)
            session.readyToPlay(player)
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
        session.readyToPlay(player)
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
    fun firstDownbeatAcceptsEarlyDownOnlyAfterTheCurrentStageIsVisible() {
        val chart = requireNotNull(song.bundled)
        val zero = chart.notes.first().copy(atMs = 0)
        val songAtZero = song.copy(bundled = chart.copy(notes = listOf(zero, chart.notes.last())))
        for (mode in StageMode.entries) {
            val host = Host()
            val session = session(host, listOf(songAtZero))
            session.selectStageMode(mode)
            session.start(false)
            val obsolete = session.state.attempt
            session.restart()
            val player = host.player
            player.ready()
            session.stagePresented(obsolete)
            session.observe()
            session.tapNow()
            assertEquals(Scene.Loading, session.state.scene)
            assertEquals(0, session.state.stats?.perfect)
            assertTrue(player.sounds.isEmpty())
            session.stagePresented(session.state.attempt)
            session.observe()
            val roll = requireNotNull(session.state.chart).cascadePreRollMs()
            val zeroUptime = player.uptimeMs() + roll
            assertEquals(Scene.CountIn, session.state.scene)
            assertEquals(-roll, session.positionMs)
            assertEquals(0, player.starts)
            player.tick(0, zeroUptime - 195)
            session.observe()
            session.tapNow()
            assertEquals(listOf(PanelFeedback(0, -195, true)), session.state.panelFeedback)
            assertEquals(1, session.state.stats?.okay)
            assertTrue(player.sounds.isEmpty())
            player.tick(0, zeroUptime)
            session.observe()
            session.tapNow()
            assertEquals(Scene.Playing, session.state.scene)
            assertEquals(1, player.starts)
            assertEquals(listOf(zero.midi), player.sounds.map { it.first })
            assertEquals(1, session.state.stats?.combo)
            session.dispose()
        }
    }

    @Test
    fun aDownQueuedDuringCountInCanArriveAfterTheFirstAudioFrame() {
        val host = Host()
        val chart = requireNotNull(song.bundled)
        val zero = chart.notes.first().copy(atMs = 0)
        val session = session(host, listOf(song.copy(bundled = chart.copy(notes = listOf(zero, chart.notes.last())))))
        session.start(false)
        session.readyToPlay(host.player)
        session.tap(9_804)
        assertEquals(0, session.state.stats?.combo)
        session.tap(9_955)
        assertEquals(1, session.state.stats?.perfect)
        assertEquals(listOf(zero.midi), host.player.sounds.map { it.first })
    }

    @Test
    fun aShortLastNoteKeepsItsLateWindowAfterAudioEof() {
        val host = Host()
        val last = Note("last", 3_990L, 72, 10, .8f)
        val session = session(host, listOf(song.copy(bundled = Chart("Тест", 120, 4_000L, listOf(last)))))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.end()
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        player.tick(4_000, 14_185)
        session.observe()
        session.tapNow()
        assertEquals(1, session.state.stats?.okay)
        assertEquals(last.atMs, session.state.panelFeedback.last().noteAtMs)
        player.tick(4_000, 14_186)
        session.observe()
        assertEquals(Scene.Result, session.state.scene)
        assertEquals(0, session.state.stats?.missed)
    }

    @Test
    fun interruptionAtTheVeryEndFinishesEvenAfterTheBackingWasReleased() {
        for (preview in listOf(false, true)) {
            val host = Host()
            val session = session(host, listOf(song.copy(backingAsset = "full.m4a")))
            session.start(preview)
            val player = host.player
            session.readyToPlay(player)
            player.end()
            player.onInterrupted!!.invoke()
            player.status = player.status.copy(ready = false)
            session.resume()
            session.observe()
            assertEquals(if (preview) Scene.Menu else Scene.Result, session.state.scene)
            assertEquals(1, player.starts)
        }
    }

    @Test
    fun pausingDuringTheFinalWindowPreservesItsRemainingTime() {
        val host = Host()
        val last = Note("last", 3990, 72, 10, .8f)
        val session = session(host, listOf(song.copy(bundled = Chart("Тест", 120, 4000, listOf(last)))))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.end()
        session.observe()
        player.tick(4000, 14_045)
        session.pause()
        player.tick(4000, 88_000)
        player.status = player.status.copy(ready = false)
        session.resume()
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        player.tick(4000, 88_140)
        session.tapNow()
        assertEquals(1, session.state.stats?.okay)
        player.tick(4000, 88_141)
        session.observe()
        assertEquals(Scene.Result, session.state.scene)
    }

    @Test
    fun bothScenesScoreAnUnaccentedPhysicalKeyOnceAndApplyTheSameTimingWindow() {
        val raw = requireNotNull(song.bundled)
        val soft = raw.notes.first().copy(id = "soft", atMs = 2_700, playable = false)
        val last = raw.notes.last().copy(atMs = 3_300)
        val part = raw.copy(notes = listOf(raw.notes.first(), soft, last))
        for (mode in StageMode.entries) {
            val host = Host()
            val session = session(host, listOf(song.copy(bundled = part, backingAsset = "full.m4a")))
            session.selectStageMode(mode)
            session.start(false)
            val player = host.player
            session.readyToPlay(player)
            for ((at, error) in listOf(2_000L to 0L, 2_700L to -195L, 3_300L to 195L)) {
                player.tick(at + error)
                session.observe()
                session.tap(10_000 + at + error)
                session.tap(10_004 + at + error)
                assertEquals(at, session.state.panelFeedback.last().noteAtMs)
                assertTrue(session.state.panelFeedback.last().hit)
            }
            assertEquals(3, session.state.stats?.combo)
            assertEquals(2, session.state.stats?.okay)
            assertEquals(0, session.state.stats?.stray)
            assertEquals(3, player.strikes.size)
            assertTrue(player.sounds.isEmpty())
            session.dispose()
        }
    }

    @Test
    fun estimatedDisplayFramesCannotExpireANoteAheadOfAConfirmedDecoderSample() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.tick(2_232)
        player.status = player.status.copy(confirmedPositionMs = 2_184)
        session.observe()
        assertEquals(2_232L, session.positionMs)
        assertEquals(0, session.state.stats?.missed)
        player.status = player.status.copy(positionMs = 2_184, confirmedPositionMs = null, stalled = true)
        session.observe()
        session.tap(14_000)
        assertEquals(1, session.state.stats?.okay)
        assertEquals(0, session.state.stats?.missed)
    }

    @Test
    fun decoderFramesDoNotInvalidateMenusAndHudBetweenJudgements() {
        val host = Host()
        val session = session(host)
        session.start(true)
        val player = host.player
        session.readyToPlay(player)
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
        val session = session(host, listOf(chord))
        session.start(true)
        session.readyToPlay(host.player)
        host.player.tick(2_004)
        session.observe()
        session.observe()
        assertEquals(listOf(72, 79, 76), host.renders.last().notes.map { it.midi })
        assertEquals("/cache/Piano.wav", host.player.generated)
        assertTrue(host.player.sounds.isEmpty())
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
    fun aSkippedFrameMarksTheExactMissedKeysAndADelayedDownCorrectsOnlyItsOwnKey() {
        val host = Host()
        val notes = (0 until 6).map { Note("n$it", 1_000L + 500L * it, 60 + it, 120, .7f) }
        val session = session(host, listOf(song.copy(bundled = Chart("Тест", 120, 5_000L, notes))))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.tick(3_240)
        session.observe()
        assertEquals(5, session.state.stats?.missed)
        assertEquals(listOf(1_500L, 2_000L, 2_500L, 3_000L), session.state.panelFeedback.map { it.noteAtMs })
        assertTrue(session.state.panelFeedback.none { it.hit })
        assertTrue(player.sounds.isEmpty())
        session.tap(13_000)
        assertEquals(4, session.state.stats?.missed)
        assertEquals(listOf(3_000L), session.state.panelFeedback.filter { it.hit }.map { it.noteAtMs })
        assertEquals(listOf(1_500L, 2_000L, 2_500L), session.state.panelFeedback.filterNot { it.hit }.map { it.noteAtMs })
        session.pause()
        session.resume()
        session.observe()
        player.tick(3_240, 14_740)
        session.observe()
        assertEquals(4, session.state.stats?.missed)
        session.restart()
        assertTrue(session.state.panelFeedback.isEmpty())
    }
    @Test
    fun slowAudioRenderDoesNotConsumeThePlayerTimeoutAndCancellationCannotStartPlayback() {
        val chart = requireNotNull(song.bundled).copy(accompaniment = listOf(
            BackingNote(0, 48, 400, .6f, BackingVoice.Bass),
        ))
        val host = Host()
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        host.renderGate = hold
        val session = session(host, listOf(song.copy(bundled = chart)))
        session.start(preview = false)
        val abandoned = host.player
        session.stagePresented(session.state.attempt)
        session.observe()
        abandoned.tick(0, 40_000)
        session.observe()
        assertEquals(Scene.Loading, session.state.scene)
        assertTrue(session.state.renderingAudio)
        assertEquals(0, abandoned.snapshots)
        session.menu()
        session.start(preview = false)
        val active = host.player
        session.stagePresented(session.state.attempt)
        session.observe()
        active.tick(0, 60_000)
        session.observe()
        hold.complete(Unit)
        assertNull(abandoned.generated)
        assertEquals(0, abandoned.starts)
        session.observe()
        assertFalse(session.state.renderingAudio)
        assertEquals(Scene.Loading, session.state.scene)
        active.tick(0, 74_999)
        active.ready()
        session.observe()
        assertEquals(Scene.Playing, session.state.scene)
        assertEquals(1, active.starts)
        session.dispose()
    }

}
