package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionClockTest {
    @Test
    fun pausedTimeDoesNotEnterTapOrStageTimeline() {
        val clock = SessionClock(2_500)
        clock.play(10_000)
        clock.play(10_400) // An extra play command must preserve the original anchor.
        val atTouch = clock.snapshot(10_900, true, null)
        assertEquals(900L, atTouch.positionMs)
        assertEquals(10_900L, atTouch.observedUptimeMs)
        clock.pause(11_250)
        assertEquals(1_250L, clock.snapshot(19_000, true, null).positionMs)
        clock.play(19_000)
        assertTrue(clock.snapshot(20_249, true, null).playing)
        val end = clock.snapshot(20_250, true, null)
        assertEquals(2_500L, end.positionMs)
        assertTrue(end.ended)
        assertFalse(end.playing)
    }

    @Test
    fun fullMidiRangeUsesAvailableRootsWithinNativePlaybackRates() {
        for (midi in 0..127) {
            val root = instrumentRoot(midi)
            assertTrue(root in 0..120 && root % 12 == 0)
            assertTrue(midi - root in -6..7)
        }
    }

    @Test
    fun displayCoastsOnlyBetweenRepeatedSamplesAndFreezesAtThePredictionLimit() {
        val clock = DecoderDisplayClock()
        assertEquals(0L, clock.position(0, 15, 1_000))
        assertEquals(0L, clock.position(0, 1_000, 1_000))
        assertEquals(210L, clock.position(210, 1_100, 1_000))
        assertEquals(226L, clock.position(210, 1_116, 1_000))
        assertEquals(234L, clock.position(210, 5_100, 1_000))
        assertEquals(234L, clock.position(222, 5_116, 1_000))
        assertEquals(240L, clock.position(240, 5_132, 1_000))
        assertEquals(990L, clock.position(990, 6_000, 1_000))
        assertEquals(1_000L, clock.position(990, 6_016, 1_000))
        clock.reset()
        assertEquals(100L, clock.position(100, 9_000, 1_000))
    }
}
