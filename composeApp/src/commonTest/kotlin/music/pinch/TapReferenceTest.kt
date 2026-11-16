package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapReferenceTest {
    @Test fun repeatEvidenceNeedsTwoSeparateTakesOfTheSameVersionAndTimeRange() {
        val a = TapTake(0, 1500, listOf(100, 590, 645, 1300), "mine", chartVersionAtCapture = "v2")
        val b = a.copy(startMs = 200, endMs = 1150, taps = listOf(625))
        val panels = listOf(100L, 900L, 1300L)
        val first = compareTaps(a, panels)
        val irrelevant = b.copy(version = "other-mix")
        assertEquals(null, repeatedTapDifferences(a, listOf(a, irrelevant), panels, first, 0))
        val result = repeatedTapDifferences(a, listOf(a, b, irrelevant), panels, first, 0)
        assertEquals(RepeatedTapDifferences(listOf(645L), listOf(900L)), result)
    }

    private fun take(vararg taps: Long) = TapTake(1_000, 4_000, taps.toList(), "test.v1")

    @Test fun matchesEachTapOnceWhilePreservingDenseNeighborPairs() {
        val r = compareTaps(take(1_100, 1_210, 1_890, 3_000), listOf(30L, 1_000, 1_130, 2_000, 3_700, 4_100))
        assertEquals(listOf(MatchedTap(1_100, 1_000), MatchedTap(1_210, 1_130), MatchedTap(1_890, 2_000)), r.matched)
        assertEquals(80L, r.medianMs)
        assertEquals(listOf(3_000L), r.tapsOnly)
        assertEquals(listOf(3_700L), r.panelsOnly)
    }

    @Test fun selectsClosestValidNeighborAndExportsAllDiscrepanciesWithSignedOffset() {
        val t = take(1_115, 2_050, 3_800)
        val delta = 50L
        val r = compareTaps(t, listOf(1_000, 1_070, 2_000, 3_200), delta)
        assertEquals(listOf(MatchedTap(1_115, 1_120), MatchedTap(2_050, 2_050)), r.matched)
        val report = tapReport(Song("test", "Тест", ""), t, "v2", r, delta)
        assertTrue("chart_offset_ms=50" in report)
        assertTrue("1115\t1120\t-5" in report)
        assertTrue("3800\t—\t—" in report)
        assertTrue("—\t3250\t—" in report)
        assertTrue("reference=test.v1 chart=v2" in report)
    }
}
