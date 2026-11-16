package music.pinch

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.Dispatchers
import music.pinch.ui.PreparedStage
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
    )), backingAsset = "backing/test.m4a")
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun session(host: Host, songs: List<Song> = listOf(song, Song("custom","Другой трек","Test"))) =
        Session(host, scope, songs, Dispatchers.Unconfined)

    private class Host : Platform {
        val values = mutableMapOf<String, String>()
        override fun readValue(key: String) = values[key]
        override fun writeValue(key: String, value: String) { values[key] = value }
        override fun removeValue(key: String) { values.remove(key) }
        var picked: PickedAudio? = null
        override suspend fun pickAudio() = picked
        val recordings = mutableSetOf<String>()
        var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun saveImportAudio(id: String, audio: PickedAudio): Boolean {
            if (audio.recording == null) return false
            recordings.add(id)
            saveGate?.await()
            return true
        }
        override fun removeImportAudio(id: String) { recordings.remove(id) }
        override fun importAudioTrack(id: String) = if (id in recordings) ArrangementTrack("/user/$id.wav") else null
        override suspend fun arrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack =
            error("A session must never render an alternative soundtrack")
        val bestScore: Int? get() = values["record.landings.v1.Orbit.test.bundled"]?.toInt()
        override var volume = .5f
        override var haptics = false
        override fun feedback() {}
        val players = mutableListOf<Player>()
        val player get() = players.last()
        override fun newMedia(durationMs: Long, instrument: Instrument): Player {
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
        var includeStrike = false
        var backingGain = 0f
        override fun prepare(pitches: Set<Int>, backingAsset: String?, arrangement: ArrangementTrack?, includeStrike: Boolean) {
            prepared = pitches
            this.includeStrike = includeStrike
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

    @Test fun aPartialRunKeepsItsHitsThroughPauseAndProcessRecreation() {
        val host = Host()
        host.values["record.landings.v1.Orbit.test.bundled"] = "65000"
        val run = session(host)
        assertEquals(0L, run.state.profile.totalXp)
        assertEquals(65_000, run.state.bestScore)
        run.start(preview = false)
        run.readyToPlay(host.player)
        host.player.tick(2000)
        run.observe()
        run.tapNow()
        run.tap(12_001) // Second down on the already scored panel.
        assertEquals(5L, run.state.profile.totalXp)
        assertEquals(0, run.state.runReward.finishXp)
        val judged = run.state.panelFeedback.single { it.hit }
        assertTrue(judged.effectsVisible)
        run.pause()
        assertTrue(run.state.panelFeedback.none { it.effectsVisible })
        assertEquals(judged.copy(effectsVisible = false), run.state.panelFeedback.single { it.hit })
        run.tap(12_500)
        run.resume()
        run.observe()
        host.player.tick(2000, 13_500)
        run.observe()
        assertEquals(Scene.Playing, run.state.scene)
        run.tapNow() // Resume on the same panel.
        assertEquals(judged.copy(effectsVisible = false), run.state.panelFeedback.single { it.hit })
        assertEquals(1, run.state.stats?.combo)
        run.tap(13_744) // In the gap.
        run.dispose()
        val next = session(host)
        assertEquals(5L, next.state.profile.totalXp)
        assertEquals(65_000, next.state.bestScore)
        assertEquals(0, next.state.runReward.totalXp)
    }

    @Test fun aFinishedReplayAwardsNewHitsAndOneFinishBonusWhilePreviewAndMissesEarnNothing() {
        val host = Host()
        val course = Song("streak", "Серия", "", Chart("Серия", 120, 7000, List(10) { i ->
            Note("n$i", 1000L + 500L * i, 70, 120, .8f)
        }), backingAsset = "backing/streak.m4a")
        val run = session(host, listOf(course))
        fun start(preview: Boolean) {
            run.start(preview)
            run.readyToPlay(host.player)
        }
        fun finish() {
            host.player.end()
            run.observe()
            run.observe()
        }
        start(preview = true)
        run.tap(11_000)
        finish()
        assertEquals(0L, run.state.profile.totalXp)
        start(preview = false)
        finish()
        assertEquals(0, run.state.runReward.totalXp)
        assertNull(host.values[PlayerProfile.storageKey])
        repeat(2) { previousRuns ->
            start(preview = false)
            assertEquals(if (previousRuns == 0) 1 else 2, run.state.runReward.startLevel)
            for (i in 0 until 10) {
                val at = 1000L + 500L * i + when (i) { 5 -> 100; 9 -> 150; else -> 0 }
                host.player.tick(at)
                run.observe()
                run.tapNow()
                run.tap(host.player.uptimeMs() + 1)
                if (previousRuns == 0 && i == 7) assertEquals(2, run.state.profile.level)
            }
            finish()
            assertEquals(Scene.Result, run.state.scene)
            assertEquals(47, run.state.runReward.hitXp)
            assertEquals(4, run.state.runReward.streakXp)
            assertEquals(5, run.state.runReward.finishXp)
            assertEquals(56L * (previousRuns + 1), run.state.profile.totalXp)
        }
        assertEquals(3, run.state.profile.level)
        assertEquals(1, run.state.profile.flameTier)
    }

    @Test
    fun menuMetadataAndImmediatePlayDoNotBuildUnselectedSongsOrStartAnObsoleteAttempt() {
        val work = ArrayDeque<Runnable>()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        }
        val built = mutableListOf<String>()
        fun arranged(id: String) = Song.arranged(id, id, "", 120, 4000, backingAsset = "backing/$id.m4a") {
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
        session.start(true)
        session.readyToPlay(host.player)
        host.player.tick(2000)
        session.observe()
        assertEquals(backing.backingAsset, host.player.backing)
        assertTrue(host.player.prepared.isEmpty() && host.player.sounds.isEmpty())
        session.menu()
        session.selectSong(revised.id)
        assertEquals(46000, session.state.bestScore)
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
    fun onlyVisibleKeysAreScoredWhileTheSameMasterCarriesTheWholeChord() {
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
        assertTrue(player.sounds.isEmpty())
        assertEquals(2, player.strikes.size)
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
        assertEquals(song.backingAsset, preview.backing)
        assertTrue(preview.prepared.isEmpty() && preview.sounds.isEmpty())
        session.menu()
        session.selectStageMode(StageMode.Orbit)
        assertEquals(raw.cascadeChart(), session.state.chart)
        assertEquals(50_000, session.state.bestScore)
        session.selectStageMode(StageMode.Planar)
        assertEquals(100_000, session.state.bestScore)
    }

    @Test
    fun aLateHitHasOnlyOneNeutralStrikeAndNeverRetriggersTheRecordedChord() {
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
        assertEquals(1, player.strikes.size)
        assertTrue(player.sounds.isEmpty())
        player.tick(1380)
        session.observe()
        assertEquals(1, player.strikes.size)
        assertTrue(player.sounds.isEmpty())
        session.menu()
        session.start(true)
        val preview = host.player
        session.readyToPlay(preview)
        for (time in chart.notes.map { it.atMs }.distinct()) {
            preview.tick(time)
            session.observe()
        }
        assertEquals(song.backingAsset, preview.backing)
        assertTrue(preview.sounds.isEmpty())
        session.dispose()
    }

    @Test
    fun staleTimbreAndSoloSettingsCannotSwitchTheSoundtrackInEitherMode() {
        val host = Host()
        host.values["playback.solo"] = "true"
        host.values["instrument"] = "Synth"
        val session = session(host)
        for (preview in listOf(false, true)) {
            session.start(preview)
            val player = host.player
            assertEquals(song.backingAsset, player.backing)
            assertNull(player.generated)
            assertTrue(player.prepared.isEmpty())
            assertEquals(!preview, player.includeStrike)
            session.readyToPlay(player)
            player.tick(2000)
            session.tapNow()
            session.observe()
            assertTrue(player.sounds.isEmpty())
            session.menu()
        }
        session.dispose()
    }

    @Test
    fun timestampedTouchesScoreOnceAndMixOnlyQuietNeutralStrikes() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        assertTrue(player.prepared.isEmpty())
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
        assertTrue(player.sounds.isEmpty())
        assertEquals(2, player.strikes.size)
        assertTrue(player.strikes.all { it > 0 && it < host.volume })
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
        assertTrue(player.sounds.isEmpty())
        assertEquals(2, player.strikes.size)
        assertEquals(100_000, session.state.stats?.score)
    }

    @Test
    fun silenceAndPreviewAndRestartHaveSeparateLifetimes() {
        val host = Host()
        val session = session(host)
        session.start(true)
        val preview = host.player
        assertTrue(session.state.preview)
        assertEquals(song.backingAsset, preview.backing)
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
        assertEquals(1, player.strikes.size)
        assertTrue(player.sounds.isEmpty())
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
        assertEquals(1, player.strikes.size)
        assertTrue(player.sounds.isEmpty())
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

    private fun pickedMelody(): PickedAudio {
        val pitches = listOf(60, 65, 62, 67, 69, 64)
        val pcm = FloatArray(11_025 * 3) { i ->
            val part = (i * 2 / 11_025).coerceAtMost(pitches.lastIndex)
            val t = (i / 11_025.0) - part * .5
            val hz = 440.0 * kotlin.math.exp((pitches[part] - 69) * kotlin.math.ln(2.0) / 12.0)
            if (t < .37) (.45 * kotlin.math.sin(2 * kotlin.math.PI * hz * t)).toFloat() else 0f
        }
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, sample ->
            val value = (sample * 32767).toInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        return ImportRecording(11_025, 1, bytes).picked("Бит.01.wav")
    }

    @Test fun ownSongsAndFavoritesSurviveRestartAndDeletionKeepsTheOtherTake() {
        val host = Host()
        val game = session(host)
        host.picked = pickedMelody()
        game.importAudio()
        val first = game.state.selectedSong
        assertEquals("Бит.01", first.title)
        assertTrue(first.id in game.state.importedIds)
        game.toggleFavorite(first.id)
        game.toggleFavorite(song.id)
        game.importAudio()
        val second = game.state.selectedSong
        assertEquals("Бит.01 (2)", second.title)
        assertTrue(first.id != second.id)

        val reopened = session(host)
        assertEquals(second.id, reopened.state.selectedSong.id)
        assertEquals(setOf(song.id, first.id), reopened.state.favoriteIds)
        reopened.selectSong(first.id)
        reopened.start(preview = true)
        assertTrue(reopened.state.canPlay)
        assertEquals("/user/${first.id}.wav", host.player.generated)
        assertFalse(host.player.includeStrike)
        reopened.menu()
        reopened.start(preview = false)
        assertEquals("/user/${first.id}.wav", host.player.generated)
        assertTrue(host.player.includeStrike && host.player.prepared.isEmpty())
        reopened.menu()
        reopened.removeImport(first.id)

        val final = session(host)
        assertEquals(song.id, final.state.selectedSong.id)
        assertEquals(setOf(second.id), final.state.importedIds)
        assertEquals(setOf(song.id), final.state.favoriteIds)
        assertEquals(setOf(second.id), host.recordings)
        assertNull(host.readValue("chart.${first.id}"))
        assertTrue(host.readValue("chart.${second.id}") != null)
        final.selectSong(second.id)
        final.start(false)
        assertEquals("/user/${second.id}.wav", host.player.generated)
        assertTrue(host.player.includeStrike)
        final.readyToPlay(host.player)
    }

    @Test fun cancellingDuringFileSaveLeavesTheCatalogAndStorageClean() {
        val host = Host()
        val game = session(host)
        host.picked = pickedMelody()
        host.saveGate = kotlinx.coroutines.CompletableDeferred()
        game.importAudio()
        assertTrue(game.state.importing)
        assertEquals(1, host.recordings.size)
        game.dispose()
        assertTrue(host.recordings.isEmpty())
        assertTrue(host.values.keys.none { it.startsWith("chart.import-") })
        assertTrue(session(host).state.importedIds.isEmpty())
    }

    @Test fun damagedMetadataUsesTheMirrorWithoutRewritingItOnLaunch() {
        val host = Host()
        val game = session(host)
        host.picked = pickedMelody()
        game.importAudio()
        val id = game.state.selectedSong.id
        game.toggleFavorite(id)
        host.values["library.personal.v1"] = "{bad"
        val recovered = session(host)
        assertEquals("{bad", host.readValue("library.personal.v1"))
        assertEquals(setOf(id), recovered.state.favoriteIds)
        assertEquals(id, recovered.state.selectedSong.id)
        assertTrue(recovered.state.canPlay)
        recovered.toggleFavorite(id)
        assertEquals("{bad", host.readValue("library.personal.v1.damaged"))
        assertTrue(session(host).state.favoriteIds.isEmpty())
        assertEquals(setOf(id), host.recordings)

        host.values["library.personal.v1"] = "broken"
        host.values["library.personal.v1.recovery"] = "also broken"
        assertEquals(song.id, session(host).state.selectedSong.id)
        assertEquals("broken", host.readValue("library.personal.v1"))
        assertTrue(host.readValue("chart.$id") != null && id in host.recordings)
    }

    @Test fun migrationPreservesPreviousImportedRecordAndBuiltInIndependently() {
        val host = Host()
        val raw = encodeChart(requireNotNull(song.bundled))
        host.values["chart.${song.id}"] = raw
        host.values["selected.song"] = song.id
        host.values["record.landings.v1.Orbit.${song.id}.import.${raw.hashCode()}"] = "12345"
        host.values["record.landings.v1.Orbit.${song.id}.bundled"] = "910"
        val game = session(host)
        val imported = game.state.selectedSong.id
        assertTrue(imported in game.state.importedIds)
        assertEquals(12345, game.state.bestScore)
        game.selectSong(song.id)
        assertEquals(910, game.state.bestScore)
        game.removeImport(imported)
        assertFalse(imported in session(host).state.importedIds)
        assertEquals(910, session(host).state.bestScore)
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
    fun missedRecordedNotesNeverAddASecondPitchToTheMusic() {
        val host = Host()
        val session = session(host, listOf(song.copy(backingAsset = "full.m4a")))
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        for (time in listOf(2_244L, 2_255L, 2_744L, 2_745L, 3_500L)) {
            player.tick(time)
            session.observe()
        }
        assertTrue(player.sounds.isEmpty() && player.strikes.isEmpty())
        assertTrue(player.prepared.isEmpty())
        assertEquals(2, session.state.stats?.missed)
        session.restart()
        session.readyToPlay(host.player)
        host.player.tick(3_500L)
        session.observe()
        assertTrue(host.player.sounds.isEmpty())
        assertEquals(2, session.state.stats?.missed)
    }

    @Test
    fun strayTapsAreSilentAndDebounceARepeatedDown() {
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
        assertTrue(player.sounds.isEmpty())
        assertEquals(1, player.strikes.size)
        assertEquals(1, session.state.stats?.stray)
        player.tick(2_500)
        session.tap(12_500)
        assertTrue(player.sounds.isEmpty())
        assertEquals(2, player.strikes.size)
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
        assertEquals(song.backingAsset, host.player.backing)
        session.menu()
        // A pre-library install stored the imported chart under the selected built-in ID.
        host.removeValue("library.personal.v1")
        host.removeValue("library.personal.v1.recovery")
        host.writeValue("selected.song", stereo.id)
        host.writeValue("chart.${stereo.id}", encodeChart(requireNotNull(song.bundled)))
        val imported = session(host, songs)
        val personalId = imported.state.selectedSong.id
        assertTrue(personalId in imported.state.importedIds)
        assertTrue(personalId != stereo.id)
        assertNull(imported.state.backingAsset)
        val players = host.players.size
        imported.start(true)
        assertTrue(imported.state.needsSourceFile)
        assertEquals(Scene.Menu, imported.state.scene)
        assertEquals(players, host.players.size)
        imported.menu()
        imported.selectSong(stereo.id)
        assertTrue(personalId in imported.state.importedIds)
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
        assertTrue(skipped.sounds.isEmpty() && skipped.strikes.isEmpty())
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
            assertEquals(1, player.strikes.size)
            assertTrue(player.sounds.isEmpty())
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
        assertEquals(1, host.player.strikes.size)
        assertTrue(host.player.sounds.isEmpty())
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
    fun visibleContactOnUpcomingRefreshDoesNotMoveInputOrReplayTheNoteWhenPaused() {
        val host = Host()
        val session = session(host)
        session.start(false)
        val player = host.player
        session.readyToPlay(player)
        player.tick(1_983)
        session.observe()
        val reads = player.snapshots
        assertEquals(2_000L, session.presentationPosition(12_000))
        assertEquals(1_983L, session.positionMs)
        assertEquals(reads, player.snapshots)
        session.tap(12_000)
        assertEquals(1, session.state.stats?.perfect)
        session.pause()
        assertEquals(1_983L, session.presentationPosition(20_000))
        session.resume()
        assertEquals(1_983L, session.presentationPosition(21_500))
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
    fun aBundledChartWithAnUnreleasedMasterStaysInTheLibraryButCannotPlay() {
        val host = Host()
        val song = song.copy(backingAsset = null)
        val game = session(host, listOf(song))
        game.toggleFavorite(song.id)
        assertTrue(song.id in session(host, listOf(song)).state.favoriteIds)
        assertFalse(game.state.canPlay)
        assertFalse(game.state.needsSourceFile)
        for (preview in listOf(true, false)) {
            game.start(preview)
            assertEquals(Scene.Menu, game.state.scene)
            assertEquals("Фонограмма готовится.", game.state.notice)
            assertTrue(host.players.isEmpty())
        }
    }

    @Test
    fun importsRoundTripAndSurviveReopeningTheApp() {
        val host = Host()
        host.writeValue("chart.custom", encodeChart(requireNotNull(song.bundled)))
        val session = session(host)
        val id = session.state.importedIds.single()
        session.selectSong(id)
        val reopened = session(host)
        assertEquals(id, reopened.state.selectedSong.id)
        assertTrue(reopened.state.needsSourceFile)
        assertFalse(id in reopened.state.availableIds)
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
    fun restoringFromADifferentFileKeepsIdentityAndOldDataWithoutCreditingItsScore() {
        val host = Host()
        val raw = encodeChart(requireNotNull(song.bundled))
        host.values["chart.test"] = raw
        host.values["selected.song"] = "test"
        host.values["record.landings.v1.Orbit.test.import.${raw.hashCode()}"] = "63450"
        val game = session(host)
        val id = game.state.selectedSong.id
        assertEquals("import-legacy-test", id)
        game.toggleFavorite(id)
        game.restoreRecording() // Picker cancelled
        assertTrue(game.state.needsSourceFile)
        host.picked = pickedMelody()
        host.saveGate = kotlinx.coroutines.CompletableDeferred()
        game.restoreRecording()
        game.dispose() // Cancel after the file write, before publishing the index
        assertEquals(raw, host.values["chart.test"])
        assertTrue(host.recordings.isEmpty())
        assertTrue(id in session(host).state.favoriteIds)
        host.saveGate = null
        val retry = session(host)
        retry.restoreRecording()
        assertEquals(setOf(id), retry.state.importedIds)
        assertTrue(retry.state.canPlay)
        assertEquals(raw, host.values["chart.test"])
        assertTrue(requireNotNull(host.values["chart.restored.$id"]) != raw)
        val reopened = session(host)
        assertNull(reopened.state.bestScore)
        assertEquals("63450", host.values["record.landings.v1.Orbit.test.import.${raw.hashCode()}"])
        assertEquals(id, reopened.state.selectedSong.id)
        assertTrue(id in reopened.state.favoriteIds)
        for (preview in listOf(true, false)) {
            reopened.start(preview)
            assertEquals("/user/$id.wav", host.player.generated)
            assertNull(host.player.backing)
            reopened.menu()
        }
        reopened.removeImport(id)
        assertNull(host.values["chart.test"])
        assertNull(host.values["chart.restored.$id"])
    }
    @Test
    fun restoringTheSameTargetsKeepsTheRecordEvenWithAnOlderCountIn() {
        val host = Host()
        val audio = pickedMelody()
        val detected = extractLeadChart(audio.samples, 11_025, song.title)
        val original = detected.copy(durationMs = detected.durationMs + 2_000,
            notes = detected.notes.map { it.copy(atMs = it.atMs + 2_000) })
        val raw = encodeChart(original)
        host.values["chart.test"] = raw
        host.values["selected.song"] = "test"
        host.values["record.landings.v1.Orbit.test.import.${raw.hashCode()}"] = "78000"
        val game = session(host)
        host.picked = audio
        game.restoreRecording()
        assertTrue(game.state.canPlay)
        assertEquals(78000, game.state.bestScore)
        assertEquals(78000, session(host).state.bestScore)
        assertEquals(raw, host.values["chart.test"])
    }
}
