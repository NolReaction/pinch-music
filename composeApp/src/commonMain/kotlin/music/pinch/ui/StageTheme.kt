package music.pinch.ui

/** 24-bit sRGB shared by Compose and the native stage, in catalogue order. */
internal enum class StageTheme(
    val wall: Int, val light: Int, val panel: Int, val accent: Int,
    val cornerFraction: Float, val cutCorners: Boolean,
) {
    Floor(0xC5D7DE, 0xE7EFF2, 0xB99568, 0xF3BD66, .24f, false),
    Stereo(0xD6D7D4, 0xF0EDE7, 0xAD774A, 0x64C6B5, .24f, false),
    Gypsy(0xCAD9DE, 0xEAF1F4, 0x5D9993, 0xE0B462, .24f, false),
    Levels(0xC6D7E2, 0xE7EFF5, 0x7059B5, 0x6EC6D5, .70f, false),
    Stay(0xD8D2DA, 0xF1EAF0, 0xB87C96, 0xD6A47B, .24f, false),
    TurnDown(0xCFD5E2, 0xEDF0F7, 0x7563A8, 0x65D4D3, .70f, false),
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

/** One material family belongs to the track, never to its rhythmic subdivisions. */
internal val StageTheme.orbitShape: PanelShape get() = when (this) {
    StageTheme.TurnDown -> PanelShape.Drum
    StageTheme.Stay -> PanelShape.Fork
    else -> PanelShape.WoodBar
}
internal val StageTheme.hasTubes: Boolean get() = this == StageTheme.Levels || this == StageTheme.Countdown
