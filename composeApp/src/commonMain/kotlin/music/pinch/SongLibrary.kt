package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.Chart
import music.pinch.core.Note

/** Two bars of 4/4. Both lines are written as (eighth-note offset, MIDI pitch). */
private data class Phrase(
    val gain: Float,
    val lead: List<Pair<Int, Int>>,
    val answer: List<Pair<Int, Int>>,
)

private fun arrangement(
    id: String, title: String, artist: String, bpm: Int, vararg phrases: Phrase,
): Song {
    fun time(eighth: Int) = 2_200L + (eighth * 30_000.0 / bpm).roundToLong()
    val notes = buildList {
        for ((index, phrase) in phrases.withIndex()) {
            val start = index * 16
            fun addLine(line: List<Pair<Int, Int>>, playable: Boolean) {
                for ((i, tone) in line.withIndex()) {
                    val (offset, midi) = tone
                    val eighth = start + offset
                    val next = line.getOrNull(i + 1)?.first ?: 16
                    val attack = when (offset % 8) {
                        0 -> 1f
                        4 -> .95f
                        2, 6 -> .90f
                        else -> .84f
                    }
                    val onset = time(eighth)
                    // Leave room after each low reply. Longer lead tones can span those answers.
                    val held = (next - offset).coerceAtMost(if (playable) 6 else 2)
                    val gate = if (playable) .78 else .56
                    val sustain = ((time(eighth + held) - onset) * gate).roundToLong()
                    val gain = phrase.gain * attack * (if (playable) 1f else .55f)
                    add(Note("$id-$eighth", onset, midi, sustain, gain, playable))
                }
            }
            addLine(phrase.lead, playable = true)
            addLine(phrase.answer, playable = false)
        }
    }.sortedBy { it.atMs }
    return Song(id, title, artist, Chart(title, bpm, time(phrases.size * 16) + 650L, notes),
        bundledRevision = 2)
}

internal val songLibrary = listOf(
    arrangement("on-the-floor", "On the Floor", "Jennifer Lopez ft. Pitbull", 130,
        // Descending call, syncopated reply, a rising variation, then a close on D.
        Phrase(.91f, lead = listOf(0 to 69, 3 to 67, 5 to 65, 7 to 64, 10 to 62),
            answer = listOf(2 to 50, 8 to 57, 13 to 53)),
        Phrase(.84f, lead = listOf(0 to 62, 2 to 65, 5 to 64, 8 to 62, 11 to 60),
            answer = listOf(4 to 53, 7 to 57, 14 to 57)),
        Phrase(.96f, lead = listOf(0 to 65, 3 to 69, 6 to 72, 10 to 69, 13 to 67),
            answer = listOf(2 to 53, 8 to 60, 12 to 55)),
        Phrase(.90f, lead = listOf(0 to 65, 3 to 64, 6 to 60, 10 to 62),
            answer = listOf(2 to 55, 8 to 57, 12 to 53, 14 to 50))),
    stereoLoveSong,
    arrangement("gypsy-woman", "Gypsy Woman", "Crystal Waters", 120,
        // The short offbeat organ answers sit below the held upper voice.
        Phrase(.86f, lead = listOf(0 to 62, 3 to 64, 6 to 57, 8 to 60, 12 to 58),
            answer = listOf(1 to 50, 5 to 53, 9 to 48, 13 to 53)),
        Phrase(.81f, lead = listOf(0 to 55, 3 to 60, 6 to 62, 10 to 64, 13 to 63),
            answer = listOf(1 to 48, 5 to 52, 8 to 50, 15 to 57)),
        Phrase(.94f, lead = listOf(0 to 62, 4 to 65, 7 to 67, 10 to 65, 13 to 62),
            answer = listOf(1 to 50, 5 to 53, 9 to 55, 15 to 53)),
        Phrase(.88f, lead = listOf(0 to 60, 3 to 64, 6 to 63, 10 to 62),
            answer = listOf(1 to 48, 5 to 55, 9 to 57, 13 to 53, 15 to 50))),
    arrangement("levels", "Levels", "Avicii", 126,
        // The second descent ends lower. The answering bass climbs into the last cadence.
        Phrase(.88f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 6 to 66, 8 to 64, 12 to 63),
            answer = listOf(3 to 49, 7 to 56, 11 to 52, 15 to 56)),
        Phrase(.85f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 7 to 66, 10 to 64, 13 to 61),
            answer = listOf(5 to 52, 9 to 54, 15 to 49)),
        Phrase(.95f, lead = listOf(0 to 64, 3 to 68, 6 to 71, 10 to 73, 14 to 71),
            answer = listOf(2 to 49, 5 to 52, 8 to 54, 12 to 56)),
        Phrase(.92f, lead = listOf(0 to 68, 3 to 66, 6 to 63, 10 to 61),
            answer = listOf(2 to 52, 5 to 54, 8 to 56, 14 to 49))),
    arrangement("stay", "Stay", "The Kid LAROI & Justin Bieber", 170,
        // A half-time lead leaves room for a moving answer between the longer calls.
        Phrase(.88f, lead = listOf(0 to 72, 4 to 73, 8 to 72, 12 to 68),
            answer = listOf(3 to 60, 7 to 65, 11 to 63)),
        Phrase(.81f, lead = listOf(0 to 72, 6 to 72, 10 to 73),
            answer = listOf(2 to 68, 4 to 65, 9 to 63, 13 to 68, 15 to 70)),
        Phrase(.95f, lead = listOf(0 to 73, 3 to 75, 6 to 77, 10 to 75, 13 to 72),
            answer = listOf(2 to 65, 8 to 68, 12 to 67)),
        Phrase(.87f, lead = listOf(0 to 73, 4 to 72, 8 to 68),
            answer = listOf(2 to 65, 6 to 63, 12 to 60, 15 to 56))),
    arrangement("turn-down-for-what", "Turn Down for What", "DJ Snake & Lil Jon", 100,
        // Repeated low B, a broken-down answer, a higher drop and a half-bar of space at the end.
        Phrase(.94f, lead = listOf(0 to 71, 3 to 59, 5 to 59, 8 to 71, 11 to 59),
            answer = listOf(2 to 47, 7 to 54, 10 to 47, 15 to 54)),
        Phrase(.79f, lead = listOf(0 to 59, 4 to 59, 10 to 67, 13 to 64),
            answer = listOf(3 to 47, 7 to 54, 15 to 52)),
        Phrase(.98f, lead = listOf(0 to 71, 3 to 74, 6 to 71, 9 to 59, 12 to 66),
            answer = listOf(2 to 47, 5 to 54, 11 to 47, 15 to 54)),
        Phrase(.91f, lead = listOf(0 to 67, 3 to 64, 6 to 59, 9 to 71),
            answer = listOf(2 to 52, 5 to 54, 12 to 47))),
    arrangement("yeah", "Yeah!", "Usher ft. Lil Jon & Ludacris", 105,
        // A G pedal alternates with two upper stabs; the answering line changes register.
        Phrase(.90f, lead = listOf(0 to 67, 3 to 74, 6 to 67, 8 to 75, 12 to 67),
            answer = listOf(2 to 55, 5 to 62, 10 to 55, 15 to 58)),
        Phrase(.82f, lead = listOf(0 to 75, 3 to 67, 6 to 74, 11 to 67),
            answer = listOf(2 to 58, 5 to 55, 9 to 62, 14 to 55)),
        Phrase(.96f, lead = listOf(0 to 70, 3 to 74, 6 to 77, 10 to 75, 13 to 74),
            answer = listOf(2 to 55, 5 to 58, 8 to 62, 12 to 65)),
        Phrase(.90f, lead = listOf(0 to 67, 3 to 75, 6 to 74, 10 to 67),
            answer = listOf(2 to 55, 5 to 60, 8 to 62, 14 to 55))),
    arrangement("the-final-countdown", "The Final Countdown", "Europe", 118,
        // The fanfare answers across registers, expands upwards, then resolves to F-sharp.
        Phrase(.89f, lead = listOf(0 to 73, 2 to 71, 4 to 73, 7 to 66),
            answer = listOf(3 to 54, 6 to 61, 10 to 57, 13 to 61)),
        Phrase(.84f, lead = listOf(0 to 74, 2 to 73, 4 to 74, 7 to 73, 10 to 71),
            answer = listOf(3 to 59, 6 to 62, 12 to 59, 15 to 61)),
        Phrase(.96f, lead = listOf(0 to 69, 3 to 73, 6 to 76, 9 to 78, 13 to 76),
            answer = listOf(2 to 57, 5 to 61, 8 to 64, 12 to 66)),
        Phrase(.92f, lead = listOf(0 to 74, 3 to 73, 6 to 69, 10 to 66),
            answer = listOf(2 to 59, 5 to 61, 8 to 57, 14 to 54))),
)
