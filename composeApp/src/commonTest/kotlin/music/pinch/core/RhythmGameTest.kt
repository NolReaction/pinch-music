package music.pinch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RhythmGameTest {
    private val first = Note("a", 2_000, 72, 250, .8f)
    private val second = Note("b", 2_500, 76, 500, .8f)
    private val chart = Chart("Тест", 120, 4_000, listOf(first, second))

    @Test
    fun inactiveLeadInAndTailAreIgnoredButBoundaryTapsSound() {
        val game = RhythmGame(chart)
        assertEquals(TapResult.Ignored, game.tap(1_879))
        assertEquals(TapResult.Hit(first, Grade.Okay, -120), game.tap(1_880))
        assertEquals(TapResult.Hit(second, Grade.Okay, 120), game.tap(2_620))
        assertEquals(TapResult.Ignored, game.tap(2_621))
        assertEquals(40_000, game.finish().score)
    }

    @Test
    fun pauseDoesNotExpirePendingNotesAndFinishCannotBeResumed() {
        val game = RhythmGame(chart)
        game.tap(2_000)
        game.pause()
        assertEquals(TapResult.Ignored, game.tap(2_500))
        game.advance(40_000)
        assertEquals(emptyList(), game.takeMisses())
        assertEquals(0, game.stats.missed)
        game.resume()
        assertIs<TapResult.Hit>(game.tap(2_500))
        val result = game.finish()
        assertEquals(100_000, result.score)
        game.resume()
        assertEquals(TapResult.Ignored, game.tap(2_510))
        assertEquals(result, game.finish())
    }

    @Test
    fun restartErasesEveryEventAndAcceptsTheFirstNoteAgain() {
        val game = RhythmGame(chart)
        game.tap(2_000)
        game.tap(2_010)
        game.finish()
        game.restart()
        assertEquals(GameStats(2), game.stats)
        assertIs<TapResult.Hit>(game.tap(2_000))
        assertEquals(50_000, game.finish().score)
    }

    @Test
    fun silenceCountsAllNotesOnce() {
        val game = RhythmGame(chart)
        game.advance(2_120)
        assertEquals(emptyList(), game.takeMisses())
        game.advance(2_121)
        assertEquals(listOf(first), game.takeMisses())
        game.advance(2_121)
        assertEquals(emptyList(), game.takeMisses())
        game.advance(2_621)
        assertEquals(listOf(second), game.takeMisses())
        game.advance(4_000)
        assertEquals(emptyList(), game.takeMisses())
        assertEquals(GameStats(2, missed = 2), game.finish())
    }

    @Test
    fun delayedDownCanCorrectFrameExpiryIncludingLostCombo() {
        val game = RhythmGame(chart)
        game.tap(2_000)
        game.advance(2_700)
        assertEquals(0, game.stats.combo)
        assertEquals(1, game.stats.missed)
        assertEquals(TapResult.Hit(second, Grade.Good, 50), game.tap(2_550))
        assertEquals(GameStats(2, perfect = 1, good = 1, combo = 2, maxCombo = 2), game.finish())
    }

    @Test
    fun resolvingOldTouchDoesNotUndoOtherActualMisses() {
        val game = RhythmGame(chart)
        game.advance(2_800)
        assertEquals(2, game.stats.missed)
        assertIs<TapResult.Hit>(game.tap(2_050))
        assertEquals(1, game.stats.missed)
        assertEquals(0, game.stats.combo)
        assertEquals(1, game.stats.maxCombo)
        assertEquals(35_000, game.finish().score)
    }

    @Test
    fun aChordIsOneLandingAndEveryDistinctOnsetScoresIncludingUnaccentedNotes() {
        val intro = first.copy(id = "intro", atMs = 1_000, playable = false)
        val otherVoice = first.copy(id = "chord", midi = 60, playable = false)
        val passive = second.copy(playable = false)
        val game = RhythmGame(chart.copy(notes = listOf(intro, otherVoice, first, passive)), forgiving = true)
        assertEquals(3, game.stats.noteCount)
        assertEquals(intro, assertIs<TapResult.Hit>(game.tap(1_000)).note)
        assertEquals(first, assertIs<TapResult.Hit>(game.tap(2_000)).note)
        assertEquals(TapResult.Ignored, game.tap(2_010))
        assertEquals(passive, assertIs<TapResult.Hit>(game.tap(2_500)).note)
        assertEquals(GameStats(3, perfect = 3, combo = 3, maxCombo = 3), game.finish())
    }

    @Test
    fun aLegacyScoreWithNoWrittenAccentsStillProducesScoreableLandings() {
        val soft = first.copy(playable = false)
        val ornament = soft.copy(id = "passing", atMs = first.atMs + 70)
        val tail = second.copy(playable = false)
        val fitted = chart.copy(notes = listOf(soft, ornament, tail)).cascadeChart()
        assertEquals(listOf(soft, tail), fitted.notes)
        val game = RhythmGame(fitted, forgiving = true)
        for (note in fitted.notes) assertEquals(note, assertIs<TapResult.Hit>(game.tap(note.atMs)).note)
        assertEquals(100_000, game.finish().score)
    }

    @Test
    fun signedCountdownAcceptsEarlyDownForTimeZeroIncludingItsBoundary() {
        val zero = first.copy(atMs = 0)
        val game = RhythmGame(chart.copy(notes = listOf(zero, second)), forgiving = true)
        assertEquals(TapResult.Ignored, game.tap(-196))
        assertEquals(TapResult.Hit(zero, Grade.Okay, -195), game.tap(-195))
        assertEquals(TapResult.Ignored, game.tap(-195))
        assertEquals(TapResult.Ignored, game.tap(0))
        assertEquals(0, game.stats.stray)
        game.restart()
        assertEquals(TapResult.Hit(zero, Grade.Perfect, -20), game.tap(-20))
        val leadIn = RhythmGame(chart, forgiving = true)
        assertEquals(TapResult.Ignored, leadIn.tap(-20))
    }

    @Test
    fun playModeUsesInclusiveWiderGradesAndExpiryOnBothSides() {
        val game = RhythmGame(chart, forgiving = true)
        for ((distance, grade) in listOf(80L to Grade.Perfect, 81L to Grade.Good, 135L to Grade.Good,
            136L to Grade.Okay, 195L to Grade.Okay)) {
            for (error in listOf(-distance, distance)) {
                game.restart()
                assertEquals(TapResult.Hit(first, grade, error), game.tap(first.atMs + error))
            }
        }
        game.restart()
        assertEquals(TapResult.Ignored, game.tap(first.atMs - 196))
        game.advance(first.atMs + 195)
        assertEquals(emptyList(), game.takeMisses())
        game.advance(first.atMs + 196)
        assertEquals(listOf(first), game.takeMisses())
        assertEquals(TapResult.Hit(first, Grade.Okay, 193), game.tap(first.atMs + 193))
        assertEquals(0, game.stats.missed)
    }

    @Test
    fun overlapHasOneOwnerAtTheMidpointAndRepeatsCannotSpendTheNextPanel() {
        val next = second.copy(atMs = 2_100)
        val game = RhythmGame(chart.copy(notes = listOf(first, next)), forgiving = true)
        assertEquals(first, assertIs<TapResult.Hit>(game.tap(2_050)).note)
        assertEquals(TapResult.Ignored, game.tap(2_050))
        assertEquals(TapResult.Ignored, game.tap(2_051))
        assertEquals(next, assertIs<TapResult.Hit>(game.tap(2_100)).note)
        assertEquals(TapResult.Ignored, game.tap(2_190))
        assertEquals(GameStats(2, perfect = 2, combo = 2, maxCombo = 2), game.finish())

        val swift = next.copy(atMs = 2_012)
        val fast = RhythmGame(chart.copy(notes = listOf(first, swift)), forgiving = true)
        assertEquals(first, assertIs<TapResult.Hit>(fast.tap(2_000)).note)
        assertEquals(swift, assertIs<TapResult.Hit>(fast.tap(2_012)).note)
    }

    @Test
    fun anOlderLandingExpiresOnlyAfterItsOwnLateWindowAndPreservesTheLaterHitStreak() {
        val middle = first.copy(id = "mid", atMs = 2_100)
        val game = RhythmGame(chart.copy(notes = listOf(first, middle, second)), forgiving = true)
        assertEquals(middle, assertIs<TapResult.Hit>(game.tap(2_100)).note)
        assertEquals(1, game.stats.combo)
        game.advance(2_195)
        assertEquals(emptyList(), game.takeMisses())
        game.advance(2_196)
        assertEquals(listOf(first), game.takeMisses())
        assertEquals(1, game.stats.combo)
        assertEquals(second, assertIs<TapResult.Hit>(game.tap(2_500)).note)
        assertEquals(GameStats(3, perfect = 2, missed = 1, combo = 2, maxCombo = 2), game.finish())
    }

    @Test
    fun everyMissKeepsItsOwnOnsetAcrossStallsAndTheLateDownReplacesOnlyItsPanel() {
        val middle = first.copy(id = "middle", atMs = 2_250)
        val game = RhythmGame(chart.copy(notes = listOf(first, middle, second)), forgiving = true)
        game.advance(3_500)
        assertEquals(listOf(2_000L, 2_250L, 2_500L), game.takeMisses().map { it.atMs })
        assertEquals(3, game.stats.missed)
        val corrected = assertIs<TapResult.Hit>(game.tap(2_250))
        assertEquals(middle.atMs, corrected.note.atMs)
        assertEquals(2, game.stats.missed)
        assertEquals(emptyList(), game.takeMisses())
        assertEquals(TapResult.Ignored, game.tap(2_000))
        assertEquals(0, game.stats.combo)
        assertEquals(1, game.stats.maxCombo)

        game.restart()
        game.advance(3_500)
        game.tap(2_250)
        assertEquals(listOf(first, second), game.takeMisses())
        assertEquals(emptyList(), game.takeMisses())
    }

    @Test
    fun tapsCanCrossDeadlinesBetweenFramesAndStraySpamCannotBreakTheNextHit() {
        val game = RhythmGame(chart, forgiving = true)
        assertEquals(TapResult.Stray, game.tap(2_250))
        assertEquals(listOf(first), game.takeMisses())
        assertEquals(TapResult.Ignored, game.tap(2_256))
        assertEquals(second, assertIs<TapResult.Hit>(game.tap(2_500)).note)
        assertEquals(GameStats(2, perfect = 1, missed = 1, stray = 1, combo = 1, maxCombo = 1), game.finish())
    }

    @Test
    fun pauseDropsAnExpiredSoundCueAndLeavesTheFollowingLandingOpen() {
        val game = RhythmGame(chart, forgiving = true)
        game.advance(2_245)
        assertEquals(listOf(first), game.takeMisses())
        assertNull(game.takeMissCue(2_245))
        game.pause()
        assertNull(game.takeMissCue(8_000))
        game.resume()
        assertNull(game.takeMissCue(2_246))
        assertNull(game.takeMissCue(2_260))
        assertEquals(second, assertIs<TapResult.Hit>(game.tap(2_500)).note)
        assertEquals(GameStats(2, perfect = 1, missed = 1, combo = 1, maxCombo = 1), game.finish())
    }

    @Test
    fun tenFpsMissCuesWaitForQueuedTapsAndDoNotReplayAfterAStall() {
        val game = RhythmGame(chart, forgiving = true)
        for (phase in 0L until 100L) {
            game.restart()
            val cues = (2_144L + phase..2_944L + phase step 100L).mapNotNull { game.takeMissCue(it) }
            assertEquals(listOf(first, second), cues)
        }
        game.restart()
        assertNull(game.takeMissCue(2_144))
        assertNull(game.takeMissCue(2_244))
        assertIs<TapResult.Hit>(game.tap(2_020))
        assertNull(game.takeMissCue(2_344))
        assertNull(game.takeMissCue(2_744))
        assertNull(game.takeMissCue(5_744))
        assertNull(game.takeMissCue(5_844))
    }

    @Test
    fun invalidChartsCannotStart() {
        val badScalars = listOf<() -> Chart>(
            { chart.copy(notes = emptyList()) }, { chart.copy(bpm = 0) }, { chart.copy(title = "") },
            { chart.copy(notes = listOf(first, first)) }, { chart.copy(notes = listOf(second, first)) },
        )
        badScalars.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        for (bad in listOf(
            first.copy(id = " "), first.copy(atMs = -1), first.copy(atMs = 4_000),
            first.copy(durationMs = 0), first.copy(midi = 128), first.copy(velocity = Float.NaN),
        )) {
            assertFailsWith<IllegalArgumentException> { chart.copy(notes = listOf(bad)) }
        }
    }
}
