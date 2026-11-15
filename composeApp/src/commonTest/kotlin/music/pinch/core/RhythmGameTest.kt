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
        assertEquals(0, game.advance(40_000))
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
        assertEquals(1, game.advance(2_121))
        assertEquals(0, game.advance(2_121))
        assertEquals(1, game.advance(2_621))
        assertEquals(0, game.advance(4_000))
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
    fun nearestThenEarliestThenIdBreaksTies() {
        val earlier = first.copy(id = "z")
        val sameTime = first.copy(id = "c")
        val later = second.copy(atMs = 2_160)
        val notes = listOf(earlier, sameTime, later)
        val game = RhythmGame(chart.copy(notes = notes))
        assertEquals("c", assertIs<TapResult.Hit>(game.tap(2_080)).note.id)
        assertEquals("b", assertIs<TapResult.Hit>(game.tap(2_090)).note.id)
        assertEquals("z", assertIs<TapResult.Hit>(game.tap(2_100)).note.id)
    }

    @Test
    fun optionalContactsHaveATightWindowAndNeverStealANearerManualAccent() {
        val intro = first.copy(id = "intro", atMs = 1_400, playable = false)
        val inner = first.copy(id = "inner", atMs = 2_100, playable = false)
        val accent = second.copy(atMs = 2_200)
        val tail = first.copy(id = "tail", atMs = 2_800, playable = false)
        val mixed = chart.copy(notes = listOf(intro, first, inner, accent, tail))
        val game = RhythmGame(mixed, forgiving = true)
        assertEquals(TapResult.Ignored, game.tap(1_314))
        assertEquals(TapResult.Automatic(intro), game.tap(1_315))
        assertEquals(TapResult.Ignored, game.tap(1_408))
        assertEquals(first, assertIs<TapResult.Hit>(game.tap(1_975)).note)
        assertEquals(TapResult.Automatic(inner), game.tap(2_124))
        assertEquals(TapResult.Ignored, game.tap(2_126))
        assertEquals(1, game.stats.combo)
        assertEquals(accent, assertIs<TapResult.Hit>(game.tap(2_157)).note)
        assertEquals(TapResult.Automatic(tail), game.tap(2_885))
        assertEquals(TapResult.Ignored, game.tap(2_886))
        assertEquals(GameStats(2, perfect = 2, combo = 2, maxCombo = 2), game.finish())
        game.restart()
        assertEquals(first, assertIs<TapResult.Hit>(game.tap(2_050)).note)
        assertEquals(accent, assertIs<TapResult.Hit>(game.tap(2_150)).note)
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
        assertEquals(0, game.advance(first.atMs + 195))
        assertEquals(1, game.advance(first.atMs + 196))
        assertEquals(TapResult.Hit(first, Grade.Okay, 193), game.tap(first.atMs + 193))
        assertEquals(0, game.stats.missed)
    }

    @Test
    fun exactOptionalContactsWinAndAmbiguousDownsPreferTheAccent() {
        val fill = first.copy(id = "fill", atMs = 2_118, playable = false)
        val game = RhythmGame(chart.copy(notes = listOf(first, fill, second)), forgiving = true)
        assertEquals(first, assertIs<TapResult.Hit>(game.tap(2_066)).note)
        assertEquals(TapResult.Automatic(fill), game.tap(2_118))
        game.restart()
        assertEquals(TapResult.Automatic(fill), game.tap(2_112))
        assertEquals(0, game.stats.combo)
    }

    @Test
    fun duplicateDownsNeitherBreakComboNorSpendTheNextNoteAndQuickLegitimateDownsStillScore() {
        val fill = first.copy(id = "fast", atMs = 2_056, playable = false)
        val accent = second.copy(atMs = 2_118)
        val game = RhythmGame(chart.copy(notes = listOf(first, fill, accent)), forgiving = true)
        assertIs<TapResult.Hit>(game.tap(2_000))
        assertEquals(TapResult.Ignored, game.tap(2_010))
        assertEquals(TapResult.Automatic(fill), game.tap(2_056))
        assertEquals(TapResult.Ignored, game.tap(2_063))
        assertEquals(accent, assertIs<TapResult.Hit>(game.tap(2_118)).note)
        assertEquals(TapResult.Ignored, game.tap(2_122))
        assertEquals(GameStats(2, perfect = 2, combo = 2, maxCombo = 2), game.finish())
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
