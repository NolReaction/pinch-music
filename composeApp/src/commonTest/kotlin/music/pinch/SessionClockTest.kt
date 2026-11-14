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
}
