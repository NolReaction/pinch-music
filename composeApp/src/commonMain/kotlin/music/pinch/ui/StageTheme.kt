package music.pinch.ui

/** 24-bit sRGB shared by Compose and the native stage, in catalogue order. */
internal enum class StageTheme(val wall: Int, val light: Int, val panel: Int, val accent: Int) {
    Floor(0x24152D, 0x754056, 0xAB5865, 0xFFC185),
    Stereo(0x122A38, 0x326577, 0x3E8790, 0xA1EEE2),
    Gypsy(0x30221B, 0x846345, 0x9D7549, 0xFFD68C),
    Levels(0x191C42, 0x414D98, 0x626FB3, 0xB7D4FF),
    Stay(0x35263A, 0x805B7A, 0x956C9D, 0xF5CBEE),
    TurnDown(0x2A201A, 0x885339, 0xAB623C, 0xFFD09A),
    Yeah(0x172D2B, 0x416D61, 0x518878, 0xC5EF9D),
    Countdown(0x20263C, 0x515F85, 0x7581AC, 0xE6DAFF),
}

internal fun stageTheme(songId: String): StageTheme = when (songId) {
    "on-the-floor" -> StageTheme.Floor
    "gypsy-woman" -> StageTheme.Gypsy
    "levels" -> StageTheme.Levels
    "stay" -> StageTheme.Stay
    "turn-down-for-what" -> StageTheme.TurnDown
    "yeah" -> StageTheme.Yeah
    "the-final-countdown" -> StageTheme.Countdown
    else -> StageTheme.Stereo
}

/** Hold a judged contact long enough to read it, then fade it on its own panel. */
internal fun feedbackStrength(ageMs: Long): Float = when {
    ageMs < 0 || ageMs >= 800 -> 0f
    ageMs <= 580 -> 1f
    else -> (800 - ageMs) / 220f
}
