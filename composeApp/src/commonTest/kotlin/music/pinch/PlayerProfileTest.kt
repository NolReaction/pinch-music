package music.pinch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.GameStats
import music.pinch.core.Grade

class PlayerProfileTest {
    @Test fun levelCostsAndFlameUnlocksUseLifetimeXp() {
        var before = 0L
        for (level in 1..8) {
            val threshold = 40L + 20L * (level - 1)
            val start = PlayerProfile(before)
            assertEquals(level, start.level)
            assertEquals(0L, start.xpInLevel)
            assertEquals(threshold, start.xpForNextLevel)
            val last = PlayerProfile(before + threshold - 1)
            assertEquals(level, last.level)
            assertEquals(threshold - 1, last.xpInLevel)
            assertTrue(last.progress in .97f..1f)
            assertEquals(when { level >= 6 -> 2; level >= 3 -> 1; else -> 0 }, last.flameTier)
            before += threshold
        }
    }

    @Test fun malformedOldSettingsStartAtZeroAndALargeValidTotalCannotOverflow() {
        for (raw in listOf(null, "", "broken", "-12", "123456789012345678900")) {
            assertEquals(PlayerProfile(), PlayerProfile.restore(raw))
        }
        assertEquals(75L, PlayerProfile.restore("75").totalXp)
        val max = PlayerProfile(Long.MAX_VALUE - 2).add(5)
        assertEquals(Long.MAX_VALUE, max.totalXp)
        assertTrue(max.xpInLevel in 0 until max.xpForNextLevel)
        assertTrue(max.level > 1)
    }

    @Test fun streakBonusesAreForConsecutiveHitsAndTheFinishBonusIsBounded() {
        val opening = RunReward().hit(Grade.Okay, 1)
        val broken = opening.hit(Grade.Good, 0)
        assertEquals(7, broken.hitXp)
        assertEquals(0, broken.streakXp)
        assertEquals(2, broken.hit(Grade.Perfect, 5).streakXp)
        assertEquals(0, broken.finish(GameStats(500, missed = 500)).finishXp)
        assertEquals(12, broken.finish(GameStats(500, perfect = 500)).finishXp)
        assertEquals(6, broken.finish(GameStats(100, perfect = 50, missed = 50)).finishXp)
    }
}
