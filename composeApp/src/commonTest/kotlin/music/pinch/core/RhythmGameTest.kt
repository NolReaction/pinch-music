package music.pinch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
