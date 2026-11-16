package music.pinch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingVoice
import music.pinch.core.Grade
import music.pinch.core.RhythmGame
import music.pinch.core.TapResult
import music.pinch.core.cascadeChart

class SongLibraryTest {
    @Test fun everySongKeepsItsOwnTempoAndSingleFingerTargets() {
        val bpms = mapOf("on-the-floor" to 130, "stereo-love" to 127, "gypsy-woman" to 120,
            "levels" to 126, "stay" to 170, "turn-down-for-what" to 100, "yeah" to 105,
            "the-final-countdown" to 118)
        assertEquals(bpms.keys, songLibrary.map { it.id }.toSet())
        assertEquals(bpms.size, songLibrary.size)
        songLibrary.forEach { song ->
            val chart = requireNotNull(song.bundled)
            assertEquals(song.title, chart.title)
            assertEquals(bpms[song.id], chart.bpm)
            val targets = chart.notes.filter { it.playable }
            assertEquals(0L, targets.first().atMs, song.title)
            val minimumGap = if (song.backingAsset != null) 15_000L / chart.bpm else 225L
            assertTrue(targets.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= minimumGap }, song.title)
        }
    }

    @Test fun stereoLoveSpansTheWholeSongOnOneMusicalGrid() {
        val chart = requireNotNull(stereoLoveSong.bundled)
        assertEquals("backing/stereo-love.m4a", stereoLoveSong.backingAsset)
        assertEquals(184_000L, chart.durationMs)
        assertEquals(523, chart.notes.size)
        assertEquals(chart.notes, chart.cascadeChart().notes)
        assertTrue(chart.notes.all { it.playable })
        assertEquals(0L, chart.notes.first().atMs)
        assertTrue(chart.notes.zipWithNext().all { (a, b) -> b.atMs > a.atMs })
        val lastManualMs = chart.notes.last { it.playable }.atMs
        val resolutionMs = chart.notes.last().atMs
        assertEquals(resolutionMs, lastManualMs)
        assertEquals("Кода", stereoLoveSectionAt(resolutionMs))
        val twoBarsMs = 8L * 60_000 / chart.bpm
        assertTrue(chart.durationMs - resolutionMs in 1L..twoBarsMs)
        for (note in chart.notes) {
            val tick = note.atMs * chart.bpm / 15_000.0
            assertTrue(abs(tick - tick.roundToInt()) < 0.005, "Off-grid note at ${note.atMs} ms")
        }
        assertEquals("Вступление", stereoLoveSectionAt(0))
        assertEquals("Аккордеон", stereoLoveSectionAt(34_016))
        assertEquals("Куплет", stereoLoveSectionAt(55_000))
        assertEquals("Кода", stereoLoveSectionAt(chart.durationMs))
    }

    @Test fun stereoLoveKeepsEveryReedArticulationAndTheBellAnswersBetweenLeadAttacks() {
        val chart = requireNotNull(stereoLoveSong.bundled)
        val byId = chart.cascadeChart().notes.associateBy { it.id }
        fun key(tick: Int) = byId.getValue("stereo-$tick")

        // Each time this accordion bar comes around, the three G#5s, both F#5s
        // and all three E5s sound separately, including the formerly skipped middle blows.
        for (bar in listOf(20, 24, 60, 68, 92)) {
            val offsets = listOf(3, 4, 5, 7, 8, 9, 13, 14, 15)
            assertEquals(listOf(80, 80, 80, 80, 78, 78, 76, 76, 76),
                offsets.map { key(bar * 16 + it).midi })
        }
        val bellBars = (59..95 step 2).toList()
        for (bar in bellBars) {
            val pitch = when (bar) {
                59, 67, 91, 95 -> 75 // simultaneous D#5 lead wins over E5 bell
                else -> if (bar % 4 == 1) 75 else 76
            }
            assertEquals(pitch, key(bar * 16 + 6).midi, "bell answer in bar $bar")
        }
        val bellAlone = bellBars.filter { it !in listOf(59, 67, 91, 95) }.map { key(it * 16 + 6) }
        assertEquals(15, bellAlone.size)
        assertTrue(bellAlone.all { it.velocity < .5f })
        assertEquals(508, chart.notes.size - bellAlone.size)
    }

    @Test fun everyFullArrangementScoresItsWrittenLandingsExactlyOnce() {
        val songs = songLibrary.filter { it.backingAsset != null }
        assertEquals(setOf("on-the-floor", "stereo-love", "gypsy-woman", "levels"), songs.map { it.id }.toSet())
        for (song in songs) {
            val score = requireNotNull(song.bundled)
            assertEquals("backing/${song.id}.m4a", song.backingAsset)
            assertEquals(score.durationMs, song.bundledDurationMs)
            assertTrue(score.authoredLandings, song.id)
            val route = score.cascadeChart()
            assertEquals(score.notes.filter { it.playable }, route.notes, song.id)
            val game = RhythmGame(route, forgiving = true)
            for (note in route.notes) {
                assertEquals(TapResult.Hit(note, Grade.Perfect, 0), game.tap(note.atMs), song.id)
                assertEquals(TapResult.Ignored, game.tap(note.atMs + 8), song.id)
            }
            val stats = game.finish()
            assertEquals(route.notes.size, stats.perfect, song.id)
            assertEquals(route.notes.size, stats.maxCombo, song.id)
            assertEquals(100_000, stats.score, song.id)
        }
    }

    @Test fun savedChartsRememberAutoContactsAndLoadEarlierSingleFingerCharts() {
        val stereo = requireNotNull(stereoLoveSong.bundled)
        val saved = decodeChart(encodeChart(stereo), stereo.title)
        assertEquals(stereo.notes.map { it.atMs to it.playable }, saved.notes.map { it.atMs to it.playable })
        val oldJson = """{"version":1,"bpm":120,"durationMs":4000,"notes":[[2000,64,150,0.8]]}"""
        val old = decodeChart(oldJson, "Импорт")
        assertTrue(old.notes.single().playable)
        assertTrue(old.accompaniment.isEmpty())
        assertTrue(!old.authoredLandings)
        assertEquals(oldJson, encodeChart(old))
    }

    @Test fun authoredLeadsKeepChordVoicesAndDrumsOutOfThePlayableRoute() {
        val lengths = mapOf("stay" to 32, "turn-down-for-what" to 12, "yeah" to 16, "the-final-countdown" to 16)
        val songs = songLibrary.filter { it.backingAsset == null }
        assertEquals(lengths.keys, songs.map { it.id }.toSet())
        for (song in songs) {
            val chart = requireNotNull(song.bundled)
            val lead = chart.notes.filter { it.playable }
            assertTrue(chart.authoredLandings, song.title)
            assertEquals(lead, chart.cascadeChart().notes, song.title)
            assertTrue(chart.notes.any { !it.playable }, song.title)
            assertTrue(lead.zipWithNext().any { (a, b) -> b.atMs - a.atMs <= 400 }, song.title)
            assertEquals(lengths.getValue(song.id), ((chart.durationMs - 650) * chart.bpm / 240_000.0).roundToInt())
            val band = chart.accompaniment
            assertEquals(BackingVoice.entries.toSet(), band.mapTo(mutableSetOf()) { it.voice }, song.title)
            assertTrue(band.filter { it.voice == BackingVoice.Keys }.groupBy { it.atMs }.values.any {
                chord -> chord.map { it.midi }.distinct().size >= 3
            }, song.title)
            assertEquals(band.size, band.distinctBy { Triple(it.atMs, it.voice, it.midi) }.size, song.title)
        }
    }

    @Test fun stayLeavesSixBeatsForTheBreakAndBringsBackTheOpeningCall() {
        val song = songLibrary.single { it.id == "stay" }
        val chart = requireNotNull(song.bundled)
        val openingMs = chart.notes.first().atMs
        fun eighth(atMs: Long) = ((atMs - openingMs) * chart.bpm / 30_000.0).roundToInt()
        val lead = chart.notes.filter { it.playable }
        val intro = lead.filter { eighth(it.atMs) in 0 until 16 }
        val returnOfTheme = lead.filter { eighth(it.atMs) in 128 until 144 }
        assertEquals(4, song.bundledRevision)
        assertEquals(32 * 8, eighth(chart.durationMs - 650))
        assertEquals(intro.map { it.midi }, returnOfTheme.map { it.midi })
        assertEquals(intro.take(3).map { eighth(it.atMs) },
            returnOfTheme.take(3).map { eighth(it.atMs) - 128 })
        assertTrue(chart.accompaniment.none { eighth(it.atMs) in 105 until 116 })
        val lift = lead.filter { eighth(it.atMs) in 64 until 96 }
        val bridge = chart.notes.filter { eighth(it.atMs) in 96 until 128 }
        assertTrue(bridge.maxOf { it.velocity } < lift.maxOf { it.velocity })
        assertTrue(bridge.zipWithNext().any { (a, b) -> eighth(b.atMs) - eighth(a.atMs) == 12 })
    }

    @Test fun stereoHookKeepsTheAccordionPickupDescendingPairsAndFinalTonic() {
        val source = requireNotNull(stereoLoveSong.bundled)
        val route = source.cascadeChart()
        val notes = source.notes.filter { it.playable }
        assertEquals(notes, route.notes)
        assertTrue(route.cascadeChart() === route)
        assertEquals(9, notes.count { it.atMs in 42_000L..44_000L })
        val firstHookTick = 18 * 16
        fun tick(time: Long) = (time * source.bpm / 15_000.0).roundToInt()
        val call = notes.filter { tick(it.atMs) in firstHookTick until firstHookTick + 16 }
        assertEquals(listOf(0, 3, 4, 6, 8, 10, 12, 14), call.map { tick(it.atMs) - firstHookTick })
        assertEquals(listOf(68, 76, 68, 76, 75, 76, 78, 76), call.map { it.midi })
        val answer = notes.filter { tick(it.atMs) in firstHookTick + 16 until firstHookTick + 32 }
        assertEquals(listOf(75, 76, 73, 75, 71, 73, 69, 71), answer.map { it.midi })
        assertEquals(source.notes.first(), notes.first())
        assertEquals(source.notes.last(), notes.last())
        assertTrue(source.accompaniment.isNotEmpty())
        assertTrue(source.accompaniment.filter { it.voice == music.pinch.core.BackingVoice.Bass }
            .any { tick(it.atMs) == firstHookTick + 2 })
    }
}
