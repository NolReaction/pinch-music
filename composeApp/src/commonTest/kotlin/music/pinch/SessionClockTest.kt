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
    fun nativeReadsStayBoundedAtDifferentRefreshRatesAndTheClockTracksEachFrame() {
        for (hz in listOf(60, 90, 120)) {
            val clock = BackingClock(10_000)
            var reads = 0
            for (frame in 0..hz * 5) {
                val now = frame * 1_000L / hz
                val snap = clock.snapshot(now) {
                    reads++
                    Playback(true, true, false, 800L + now, now)
                }
                assertEquals(800L + now, snap.positionMs)
                assertTrue(snap.positionMs - (snap.confirmedPositionMs ?: snap.positionMs) in 0L..63L)
            }
            assertTrue(reads in 72..79, "$reads native queries over five seconds at $hz Hz")
        }
    }

    @Test
    fun aStalledDecoderCannotAdvanceIndefinitelyAndPauseDiscardsPredictions() {
        val clock = BackingClock(500)
        fun at(uptime: Long, audio: Long) = clock.snapshot(uptime) { Playback(true, true, false, audio, uptime) }
        assertEquals(0L, at(0, 0).positionMs)
        assertTrue(at(16, 0).stalled)
        assertEquals(100L, at(80, 100).positionMs)
        assertEquals(117L, at(97, 100).positionMs)
        val stuck = at(150, 100)
        assertTrue(stuck.stalled)
        assertEquals(100L, at(170, 100).positionMs)
        assertEquals(100L, at(5_000, 100).positionMs)
        assertEquals(491L, at(5_100, 491).positionMs)
        assertEquals(500L, at(5_130, 491).positionMs)
        clock.reset()
        val paused = clock.snapshot(5_131) { Playback(true, false, false, 496, 5_131) }
        assertFalse(paused.playing)
        assertEquals(496L, paused.positionMs)
    }

    @Test
    fun decoderJitterDoesNotDoubleOrFreezeAFrameAndLongGapsResync() {
        var previous = Playback(true, true, false, 42_000, 0)
        var drawn = previous.positionMs
        for (frame in 1..60) {
            val uptime = frame * 16L
            val jitter = if ((frame / 4) % 2 == 0) 8L else -8L
            val current = previous.copy(observedUptimeMs = uptime, positionMs = 42_000 + uptime + jitter)
            val next = current.visualPosition(previous, drawn, 184_000)
            assertTrue(next - drawn in 12L..20L)
            assertTrue(kotlin.math.abs(next - current.positionMs) <= 12L)
            previous = current
            drawn = next
        }
        val lateFrame = previous.copy(observedUptimeMs = 1500, positionMs = 43_500)
        assertEquals(43_500L, lateFrame.visualPosition(previous, drawn, 184_000))
        val stalled = lateFrame.copy(observedUptimeMs = 1516, stalled = true)
        assertEquals(43_500L, stalled.visualPosition(lateFrame, 43_500, 184_000))
    }
}
