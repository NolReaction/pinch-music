package music.pinch.ui

/** 24-bit sRGB shared by Compose and the native stage, in catalogue order. */
internal enum class StageTheme(
    val wall: Int, val light: Int, val panel: Int, val accent: Int,
    val cornerFraction: Float, val cutCorners: Boolean,
) {
    Floor(0xB9BCAF, 0xF2F0E6, 0xA56B40, 0xCC9356, .28f, false),
    Stereo(0xBAC6C3, 0xEFF3EB, 0xD8DCCF, 0x419B9E, .24f, false),
    Gypsy(0xC1C3B9, 0xF3F0E5, 0xDECBA7, 0xD3A14D, .24f, false),
    Levels(0xBBC6CC, 0xEDF2EF, 0x6B68C9, 0x7460D5, .70f, false),
    Stay(0xC6BFC3, 0xF3ECEB, 0xE3D4D8, 0xBE729A, .24f, false),
    TurnDown(0xBFC1C7, 0xEFEFF0, 0x7D4FBA, 0x985BCD, .70f, false),
    Yeah(0xBEC4B5, 0xF0F0E3, 0xB9844F, 0xA0B76A, .28f, false),
    Countdown(0xBBC4CD, 0xEDF1F4, 0x565CC1, 0x6878D6, .70f, false),
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
