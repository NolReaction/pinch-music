package music.pinch.ui

/** 24-bit sRGB shared by Compose and the native stage, in catalogue order. */
internal enum class StageTheme(val wall: Int, val light: Int, val panel: Int, val accent: Int) {
    Floor(0xA89F9A, 0xECE4DD, 0xABA19A, 0xF46D48),
    Stereo(0x99A7A5, 0xE4EBE4, 0x91A2A5, 0x13C3AA),
    Gypsy(0xB1A393, 0xEFE6D3, 0xB2A184, 0xEAA52F),
    Levels(0x9BA5AE, 0xE2E9ED, 0x96A2B3, 0x577FE0),
    Stay(0xACA2A6, 0xECE5E4, 0xAE9CA7, 0xB85B9F),
    TurnDown(0xA39B92, 0xE8E2D5, 0xA29381, 0xF27628),
    Yeah(0x9FA99F, 0xE2E9D9, 0x99A68F, 0x78B62D),
    Countdown(0x9FA3AF, 0xE7E7EC, 0x9D9EAF, 0x8267C8),
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
