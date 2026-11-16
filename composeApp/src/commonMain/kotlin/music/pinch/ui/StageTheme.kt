package music.pinch.ui

/** 24-bit sRGB shared by Compose and the native stage, in catalogue order. */
internal enum class StageTheme(
    val wall: Int, val light: Int, val panel: Int, val accent: Int,
    val cornerFraction: Float, val cutCorners: Boolean,
) {
    Floor(0xD2DDDA, 0xF8F7F1, 0xF0EEE4, 0xD5AD71, .24f, false),
    Stereo(0xCEDCDB, 0xF4F8F4, 0xEEF1E8, 0x52A4A0, .24f, false),
    Gypsy(0xD9DDD3, 0xF9F7ED, 0xF1EBDC, 0xD2A662, .24f, false),
    Levels(0xCFDDE4, 0xF4F8F9, 0x7771C3, 0x7B6ECD, .70f, false),
    Stay(0xDDD5DA, 0xFAF5F3, 0xF2E9E8, 0xBD8BA5, .24f, false),
    TurnDown(0xD7D9E2, 0xF6F6FA, 0x8970B8, 0x987FC5, .70f, false),
    Yeah(0xD8DECF, 0xF8F8EF, 0xB0875C, 0xA7B88C, .28f, false),
    Countdown(0xD0DAE5, 0xF5F7FA, 0x6D7ABB, 0x7888C9, .70f, false),
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
