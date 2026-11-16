package music.pinch

import kotlin.math.roundToInt
import kotlin.math.sqrt
import music.pinch.core.GameStats
import music.pinch.core.Grade

/** Only lifetime XP is persisted. Levels, the progress bar and flame trims follow from it. */
data class PlayerProfile(val totalXp: Long = 0L) {
    init { require(totalXp >= 0L) }

    // After n promotions the player has spent 40 + 60 + ... + (20n + 20) XP.
    // The integer comparison keeps a rounded square root on the correct side of the threshold.
    private val promotions = run {
        val scaled = totalXp / 10
        val estimate = ((sqrt(9.0 + 4.0 * scaled) - 3.0) / 2.0).toLong()
        when {
            estimate * (estimate + 3) > scaled -> estimate - 1
            (estimate + 1) * (estimate + 4) <= scaled -> estimate + 1
            else -> estimate
        }
    }
    val level: Int = (promotions + 1).toInt()
    val xpInLevel: Long = totalXp - 10L * (promotions * (promotions + 3))
    val xpForNextLevel: Long = 40L + 20L * promotions
    val progress: Float = (xpInLevel.toDouble() / xpForNextLevel).toFloat()
    val flameTier: Int = when {
        level >= 6 -> 2
        level >= 3 -> 1
        else -> 0
    }

    internal fun add(xp: Int): PlayerProfile {
        require(xp >= 0)
        return PlayerProfile(totalXp + minOf(xp.toLong(), Long.MAX_VALUE - totalXp))
    }

    internal companion object {
        const val storageKey = "player.xp.v1"
        fun restore(value: String?) = PlayerProfile(value?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
    }
}

data class RunReward(
    val startLevel: Int = 1,
    val hitXp: Int = 0,
    val streakXp: Int = 0,
    val finishXp: Int = 0,
) {
    val totalXp: Int get() = hitXp + streakXp + finishXp

    internal fun hit(grade: Grade, combo: Int): RunReward {
        val earned = when (grade) {
            Grade.Perfect -> 5
            Grade.Good -> 4
            Grade.Okay -> 3
        }
        val streak = if (combo > 0 && combo % 5 == 0) 2 else 0
        return copy(hitXp = hitXp + earned, streakXp = streakXp + streak)
    }

    internal fun finish(stats: GameStats): RunReward {
        val hits = stats.perfect + stats.good + stats.okay
        // Scale the finish bonus with actual hits and accuracy. A silent pass earns zero.
        val bonus = (minOf(24, hits) * stats.accuracy * .5).roundToInt()
        return copy(finishXp = bonus)
    }
}
