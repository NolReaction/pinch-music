package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

/** Two bars. Lead and keyboard answers are in eighths; pickups and grace notes are in sixteenths. */
private data class Phrase(
    val gain: Float, val lead: List<Pair<Int, Int>>, val answer: List<Pair<Int, Int>>,
    val pickups: List<Pair<Int, Int>> = emptyList(), val grace: List<Pair<Int, Int>> = emptyList(),
    val gate: Double = .78,
)

private fun arrangement(
    id: String, title: String, artist: String, bpm: Int, bars: Int, revision: Int,
    score: () -> List<Phrase>,
): Song {
    fun time(tick: Double) = (tick * 15_000.0 / bpm).roundToLong()
    val duration = time(bars * 16.0) + 650L
    return Song.arranged(id, title, artist, bpm, duration, bundledRevision = revision) {
        val phrases = score()
        require(phrases.size * 2 == bars)
        val melody = mutableListOf<Note>()
        val answers = mutableListOf<BackingNote>()
        for ((index, phrase) in phrases.withIndex()) {
            val start = index * 32
            val lead = phrase.lead.map { (eighth, pitch) -> eighth * 2 to pitch } + phrase.pickups
            val onsets = (lead + phrase.grace).sortedBy { it.first }
            for ((i, tone) in onsets.withIndex()) {
                val (tick, pitch) = tone
                val main = tone in lead
                val accent = when (tick % 16) {
                    0 -> 1f
                    8 -> .96f
                    4, 12 -> .90f
                    else -> .86f
                }
                val next = onsets.getOrNull(i + 1)?.first ?: 32
                val gate = if (main) phrase.gate else .48
                val sustain = time((next - tick).coerceAtMost(12) * gate).coerceAtLeast(1)
                melody += Note("$id-${start + tick}", time((start + tick).toDouble()), pitch, sustain,
                    phrase.gain * accent * (if (main) 1f else .68f), playable = main)
            }
            for ((eighth, pitch) in phrase.answer) {
                answers += BackingNote(time((start + 2 * eighth).toDouble()), pitch,
                    time(if (eighth % 2 == 0) 2.4 else 1.7), phrase.gain * .43f, BackingVoice.Keys)
            }
        }
        val band = bandArrangement(id, bpm, phrases.map { it.gain })
        val backing = (answers + band).sortedBy { it.atMs }.distinctBy { Triple(it.atMs, it.midi, it.voice) }
        Chart(title, bpm, duration, melody, backing, authoredLandings = true)
    }
}

internal val songLibrary = listOf(
    onTheFloorSong,
    stereoLoveSong,
    arrangement("gypsy-woman", "Gypsy Woman", "Crystal Waters", 120, 16, 3) { listOf(
        Phrase(.86f, lead = listOf(0 to 62, 3 to 64, 6 to 57, 8 to 60, 12 to 58),
            answer = listOf(1 to 50, 5 to 53, 9 to 48, 13 to 53), pickups = listOf(10 to 60), gate = .60),
        Phrase(.81f, lead = listOf(0 to 55, 3 to 60, 6 to 62, 10 to 64, 13 to 63),
            answer = listOf(1 to 48, 5 to 52, 8 to 50, 15 to 57), grace = listOf(24 to 65), gate = .61),
        Phrase(.74f, lead = listOf(0 to 65, 6 to 62, 12 to 60),
            answer = listOf(3 to 57, 9 to 53), gate = .95),
        Phrase(.85f, lead = listOf(1 to 64, 4 to 63, 8 to 62, 12 to 61),
            answer = listOf(3 to 55, 7 to 58, 15 to 57), pickups = listOf(19 to 64), gate = .63),
        Phrase(.94f, lead = listOf(0 to 62, 4 to 65, 7 to 67, 10 to 65, 13 to 62),
            answer = listOf(1 to 50, 5 to 53, 9 to 55, 15 to 53), grace = listOf(27 to 64), gate = .62),
        Phrase(.96f, lead = listOf(1 to 65, 4 to 69, 8 to 67, 11 to 65),
            answer = listOf(3 to 57, 7 to 60, 15 to 58), pickups = listOf(26 to 64), gate = .52),
        Phrase(.89f, lead = listOf(0 to 62, 3 to 64, 6 to 57, 10 to 60, 13 to 62),
            answer = listOf(1 to 53, 8 to 55, 15 to 58), gate = .6),
        Phrase(.88f, lead = listOf(0 to 60, 3 to 64, 6 to 63, 10 to 62),
            answer = listOf(1 to 48, 5 to 55, 9 to 57, 13 to 53, 15 to 50), gate = .76),
    ) },
    arrangement("levels", "Levels", "Avicii", 126, 20, 3) { listOf(
        Phrase(.88f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 6 to 66, 8 to 64, 12 to 63),
            answer = listOf(3 to 49, 7 to 56, 11 to 52, 15 to 56), pickups = listOf(22 to 64), grace = listOf(20 to 66)),
        Phrase(.85f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 7 to 66, 10 to 64, 13 to 61),
            answer = listOf(5 to 52, 9 to 54, 15 to 49)),
        Phrase(.74f, lead = listOf(0 to 73, 8 to 71, 13 to 68),
            answer = listOf(5 to 64), gate = .96),
        Phrase(.85f, lead = listOf(0 to 66, 4 to 68, 7 to 71, 12 to 73),
            answer = listOf(2 to 59, 9 to 64), grace = listOf(20 to 70)),
        Phrase(.95f, lead = listOf(0 to 64, 3 to 68, 6 to 71, 10 to 73, 14 to 71),
            answer = listOf(2 to 49, 5 to 52, 8 to 54, 12 to 56), grace = listOf(18 to 71)),
        Phrase(.98f, lead = listOf(0 to 76, 3 to 75, 6 to 73, 10 to 71, 14 to 68),
            answer = listOf(2 to 64, 8 to 59), pickups = listOf(24 to 73)),
        Phrase(.88f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 6 to 66, 8 to 64, 12 to 63),
            answer = listOf(3 to 49, 7 to 56, 11 to 52, 15 to 56), pickups = listOf(28 to 64), gate = .70),
        Phrase(.92f, lead = listOf(0 to 73, 2 to 71, 4 to 68, 8 to 66, 12 to 63),
            answer = listOf(6 to 61, 14 to 59), grace = listOf(19 to 68)),
        Phrase(.96f, lead = listOf(0 to 64, 3 to 68, 6 to 71, 11 to 75, 14 to 73),
            answer = listOf(2 to 57, 8 to 59), grace = listOf(17 to 73)),
        Phrase(.92f, lead = listOf(0 to 68, 3 to 66, 6 to 63, 10 to 61),
            answer = listOf(2 to 52, 5 to 54, 8 to 56, 14 to 49), gate = .93),
    ) },
    arrangement("stay", "Stay", "The Kid LAROI & Justin Bieber", 170, 32, 4) { listOf(
        Phrase(.88f, lead = listOf(0 to 72, 4 to 73, 8 to 72, 12 to 68),
            answer = listOf(3 to 53, 7 to 60, 14 to 56), grace = listOf(5 to 70)),
        Phrase(.82f, lead = listOf(1 to 72, 6 to 70, 10 to 68),
            answer = listOf(0 to 49, 4 to 56, 9 to 63, 14 to 60)),
        Phrase(.86f, lead = listOf(0 to 68, 3 to 70, 7 to 72, 11 to 68),
            answer = listOf(2 to 51, 5 to 58, 10 to 63, 14 to 58), grace = listOf(11 to 73)),
        Phrase(.88f, lead = listOf(0 to 65, 4 to 68, 7 to 70, 11 to 72),
            answer = listOf(2 to 53, 6 to 60, 13 to 63, 15 to 65), pickups = listOf(18 to 71)),
        Phrase(.93f, lead = listOf(0 to 73, 3 to 75, 6 to 77, 10 to 75, 13 to 77),
            answer = listOf(2 to 61, 5 to 65, 8 to 68, 12 to 65), gate = .76),
        Phrase(.98f, lead = listOf(0 to 80, 3 to 77, 7 to 75, 11 to 72),
            answer = listOf(2 to 56, 5 to 60, 9 to 63, 14 to 60), grace = listOf(18 to 77)),
        Phrase(.74f, lead = listOf(0 to 68, 8 to 65),
            answer = listOf(2 to 53), gate = .95),
        Phrase(.78f, lead = listOf(4 to 63, 12 to 68),
            answer = listOf(7 to 58), gate = .94),
        Phrase(.94f, lead = listOf(0 to 72, 4 to 73, 8 to 72, 13 to 68),
            answer = listOf(2 to 53, 6 to 60, 10 to 65, 15 to 60), grace = listOf(22 to 70)),
        Phrase(.97f, lead = listOf(0 to 70, 3 to 73, 7 to 75, 12 to 73),
            answer = listOf(2 to 49, 5 to 56, 9 to 61, 15 to 63)),
        Phrase(.82f, lead = listOf(1 to 68, 5 to 70, 10 to 73, 14 to 70),
            answer = listOf(3 to 61, 8 to 65), gate = .84),
        Phrase(.89f, lead = listOf(0 to 68, 4 to 72, 9 to 75, 13 to 73),
            answer = listOf(2 to 58, 7 to 63), pickups = listOf(14 to 73), gate = .76),
        Phrase(.96f, lead = listOf(0 to 73, 4 to 77, 8 to 80, 12 to 77),
            answer = listOf(2 to 61, 6 to 65, 14 to 63), pickups = listOf(19 to 78)),
        Phrase(.97f, lead = listOf(0 to 75, 3 to 72, 7 to 73, 12 to 75),
            answer = listOf(5 to 60, 9 to 63), grace = listOf(20 to 77)),
        Phrase(.91f, lead = listOf(0 to 77, 3 to 75, 6 to 73, 10 to 72, 13 to 70),
            answer = listOf(2 to 51, 5 to 58, 8 to 63)),
        Phrase(.85f, lead = listOf(0 to 73, 4 to 72, 8 to 68),
            answer = listOf(2 to 61, 6 to 63, 12 to 60, 15 to 56), gate = .96),
    ) },
    arrangement("turn-down-for-what", "Turn Down for What", "DJ Snake & Lil Jon", 100, 12, 3) { listOf(
        Phrase(.94f, lead = listOf(0 to 71, 3 to 59, 5 to 59, 8 to 71, 11 to 59),
            answer = listOf(2 to 47, 7 to 54, 10 to 47, 15 to 54), pickups = listOf(18 to 71), gate = .49),
        Phrase(.79f, lead = listOf(0 to 59, 4 to 59, 10 to 67, 13 to 64),
            answer = listOf(3 to 47, 7 to 54, 15 to 52), gate = .58),
        Phrase(.75f, lead = listOf(0 to 59, 8 to 59, 14 to 66),
            answer = listOf(4 to 47), gate = .38),
        Phrase(.98f, lead = listOf(0 to 71, 3 to 74, 6 to 71, 9 to 59, 12 to 66),
            answer = listOf(2 to 47, 5 to 54, 11 to 47, 15 to 54), pickups = listOf(28 to 71), gate = .43),
        Phrase(.96f, lead = listOf(0 to 71, 3 to 59, 6 to 66, 9 to 71, 12 to 74),
            answer = listOf(2 to 47, 8 to 54, 14 to 58), grace = listOf(11 to 62), gate = .54),
        Phrase(.91f, lead = listOf(0 to 67, 3 to 64, 6 to 59, 9 to 71),
            answer = listOf(2 to 52, 5 to 54, 12 to 47), gate = .60),
    ) },
    arrangement("yeah", "Yeah!", "Usher ft. Lil Jon & Ludacris", 105, 16, 3) { listOf(
        Phrase(.90f, lead = listOf(0 to 67, 3 to 74, 6 to 67, 8 to 75, 12 to 67),
            answer = listOf(2 to 55, 5 to 62, 10 to 55, 15 to 58), pickups = listOf(18 to 74), gate = .48),
        Phrase(.82f, lead = listOf(0 to 75, 3 to 67, 6 to 74, 11 to 67),
            answer = listOf(2 to 58, 5 to 55, 9 to 62, 14 to 55), pickups = listOf(26 to 70), gate = .55),
        Phrase(.87f, lead = listOf(0 to 67, 3 to 70, 7 to 72, 11 to 70),
            answer = listOf(2 to 55, 5 to 60, 14 to 62), grace = listOf(19 to 72), gate = .61),
        Phrase(.78f, lead = listOf(0 to 67, 8 to 74, 12 to 72),
            answer = listOf(3 to 58, 14 to 54), gate = .65),
        Phrase(.96f, lead = listOf(0 to 70, 3 to 74, 6 to 77, 10 to 75, 13 to 74),
            answer = listOf(2 to 55, 5 to 58, 8 to 62, 12 to 65), gate = .58),
        Phrase(.97f, lead = listOf(0 to 75, 3 to 77, 6 to 79, 10 to 77, 13 to 74),
            answer = listOf(2 to 63, 8 to 67), pickups = listOf(17 to 75), gate = .55),
        Phrase(.90f, lead = listOf(0 to 67, 3 to 74, 6 to 67, 9 to 75, 13 to 67),
            answer = listOf(4 to 62, 8 to 55, 15 to 58), gate = .48),
        Phrase(.90f, lead = listOf(0 to 67, 3 to 75, 6 to 74, 10 to 67),
            answer = listOf(2 to 55, 5 to 60, 8 to 62, 14 to 55), gate = .75),
    ) },
    arrangement("the-final-countdown", "The Final Countdown", "Europe", 118, 16, 3) { listOf(
        Phrase(.89f, lead = listOf(0 to 73, 2 to 71, 4 to 73, 7 to 66),
            answer = listOf(3 to 54, 6 to 61, 10 to 57, 13 to 61), pickups = listOf(10 to 71), grace = listOf(12 to 73), gate = .88),
        Phrase(.84f, lead = listOf(0 to 74, 2 to 73, 4 to 74, 7 to 73, 10 to 71),
            answer = listOf(3 to 59, 6 to 62, 12 to 59, 15 to 61), gate = .86),
        Phrase(.77f, lead = listOf(0 to 69, 5 to 66, 10 to 62),
            answer = listOf(3 to 59, 12 to 57), gate = .96),
        Phrase(.84f, lead = listOf(0 to 68, 4 to 71, 8 to 73, 13 to 71),
            answer = listOf(2 to 59, 10 to 61), pickups = listOf(19 to 74), gate = .88),
        Phrase(.96f, lead = listOf(0 to 69, 3 to 73, 6 to 76, 9 to 78, 13 to 76),
            answer = listOf(2 to 57, 5 to 61, 8 to 64, 12 to 66), grace = listOf(23 to 76), gate = .90),
        Phrase(.98f, lead = listOf(0 to 78, 4 to 81, 7 to 78, 11 to 76, 14 to 73),
            answer = listOf(2 to 61, 8 to 66), gate = .85),
        Phrase(.93f, lead = listOf(0 to 73, 2 to 71, 4 to 73, 8 to 66, 13 to 69),
            answer = listOf(6 to 61, 11 to 57), grace = listOf(24 to 68), gate = .88),
        Phrase(.92f, lead = listOf(0 to 74, 3 to 73, 6 to 69, 10 to 66),
            answer = listOf(2 to 59, 5 to 61, 8 to 57, 14 to 54), gate = .96),
    ) },
)
