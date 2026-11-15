package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.Chart
import music.pinch.core.Note

/** Short single-voice practice reductions. Each pair gives MIDI pitch and length in eighth notes. */
private fun arrangement(
    id: String, title: String, artist: String, bpm: Int, vararg phrase: Pair<Int, Int>,
): Song {
    var eighths = 0
    fun time(position: Int) = 2_200L + (position * 30_000.0 / bpm).roundToLong()
    val notes = phrase.mapIndexed { index, (pitch, length) ->
        val onset = time(eighths)
        val accent = when (eighths % 8) {
            0 -> 0.96f
            4 -> 0.87f
            else -> 0.77f
        }
        eighths += length
        val sustain = ((time(eighths) - onset) * 0.76).roundToLong()
        Note("$id-$index", onset, pitch, sustain, accent)
    }
    return Song(id, title, artist, Chart(title, bpm, time(eighths) + 650, notes))
}

internal val songLibrary = listOf(
    arrangement("on-the-floor", "On the Floor", "Jennifer Lopez ft. Pitbull", 130,
        69 to 3, 67 to 2, 65 to 2, 64 to 3, 62 to 6,
        62 to 2, 65 to 3, 64 to 2, 62 to 3, 60 to 2, 62 to 4, 69 to 4),
    stereoLoveSong,
    arrangement("gypsy-woman", "Gypsy Woman", "Crystal Waters", 120,
        62 to 3, 64 to 3, 57 to 2, 60 to 2, 58 to 4, 55 to 3, 60 to 3,
        62 to 4, 57 to 2, 58 to 4, 64 to 2, 63 to 2, 62 to 4),
    arrangement("levels", "Levels", "Avicii", 126,
        73 to 2, 71 to 2, 68 to 2, 66 to 2, 64 to 2, 64 to 3, 63 to 2, 64 to 4,
        73 to 2, 71 to 2, 68 to 2, 66 to 2, 64 to 3, 61 to 2, 59 to 4),
    arrangement("stay", "Stay", "The Kid LAROI & Justin Bieber", 170,
        72 to 4, 73 to 4, 72 to 4, 68 to 8, 72 to 6, 72 to 6,
        73 to 4, 72 to 4, 68 to 8, 73 to 6, 75 to 6, 72 to 4, 68 to 8),
    arrangement("turn-down-for-what", "Turn Down for What", "DJ Snake & Lil Jon", 100,
        71 to 3, 59 to 2, 59 to 2, 71 to 3, 59 to 2, 59 to 4,
        71 to 3, 59 to 2, 59 to 3, 67 to 2, 64 to 4),
    arrangement("yeah", "Yeah!", "Usher ft. Lil Jon & Ludacris", 105,
        67 to 3, 74 to 3, 67 to 2, 75 to 4, 67 to 2, 75 to 3, 67 to 2, 74 to 5,
        67 to 3, 74 to 3, 67 to 2, 75 to 4),
    arrangement("the-final-countdown", "The Final Countdown", "Europe", 118,
        73 to 2, 71 to 2, 73 to 3, 66 to 6,
        74 to 2, 73 to 2, 74 to 2, 73 to 2, 71 to 5,
        73 to 2, 71 to 2, 73 to 3, 66 to 6),
)
