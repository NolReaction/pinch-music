package music.pinch

import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

// At 120 BPM a sixteenth is exactly 125 ms. The studio and the course use these same onsets.
private const val gypsyBpm = 120
private const val gypsyBars = 114
private const val gypsyStep = 125L
private const val gypsyBarMs = 16 * gypsyStep

private enum class GypsyPart { Intro, VerseOne, Chorus, VerseTwo, BareChorus, Organ, Bridge, Breakdown, Reprise, Outro }

private fun gypsyPart(bar: Int): GypsyPart = when (bar) {
    in 0..3 -> GypsyPart.Intro
    in 4..17 -> GypsyPart.VerseOne
    in 18..23 -> GypsyPart.Chorus
    in 24..37 -> GypsyPart.VerseTwo
    in 38..43 -> GypsyPart.BareChorus
    in 44..47 -> GypsyPart.Chorus
    in 48..55 -> GypsyPart.Organ
    in 56..59 -> GypsyPart.Bridge
    in 60..63 -> GypsyPart.BareChorus
    in 64..71 -> GypsyPart.Chorus
    in 72..75 -> GypsyPart.Breakdown
    in 76..87 -> GypsyPart.Organ
    in 88..95 -> GypsyPart.Reprise
    in 96..99 -> GypsyPart.Bridge
    in 100..107 -> GypsyPart.Reprise
    else -> GypsyPart.Outro
}

private data class OrganChord(val root: Int, val keys: List<Int>)

private val gypsyDm9 = OrganChord(38, listOf(50, 53, 57, 60, 64))
private val gypsyDm7 = OrganChord(38, listOf(50, 53, 57, 60, 62))
private val gypsyAm = OrganChord(33, listOf(45, 55, 60, 64))
private val gypsyBb = OrganChord(34, listOf(46, 57, 62, 65))
private val gypsyGm = OrganChord(31, listOf(43, 55, 58, 62, 65))
private val gypsyC7flat9 = OrganChord(36, listOf(48, 55, 58, 61, 64))
// The turnaround lifts the right hand by an octave. Hold D as the common upper voice
// across E minor and E-flat major while the root and inner voices descend underneath.
private val gypsyEm = OrganChord(40, listOf(52, 64, 67, 71, 74))
private val gypsyEb = OrganChord(39, listOf(51, 63, 67, 70, 74))

private val gypsyStabs = listOf(0, 4, 7, 10, 13)
private val gypsyGates = listOf(234L, 150L, 110L, 118L, 128L)
private val gypsyAccents = listOf(1f, .87f, .96f, .82f, .88f)
private fun organChord(bar: Int, step: Int): OrganChord {
    val first = step < 7
    return when ((bar + 2) % 8) {
        2, 6 -> if (first) gypsyAm else gypsyBb
        3 -> if (first) gypsyGm else gypsyC7flat9
        7 -> if (first) gypsyEm else gypsyEb
        else -> if (first) gypsyDm9 else gypsyDm7
    }
}

private data class GypsyTone(val step: Int, val pitch: Int, val length: Int)

// Durations are written separately from rests. This keeps the long answer in a phrase
// ringing and leaves silence for the next pickup, even when there is no new note on beat one.
private fun gypsyLine(vararg notes: Pair<Int, Int>, end: Int = 16): List<GypsyTone> =
    notes.mapIndexed { index, (step, pitch) ->
        GypsyTone(step, pitch, (notes.getOrNull(index + 1)?.first ?: end) - step)
    }

private object GypsyMelodies {
    // Two A pickups spring to the upper D; the answering pair falls a full octave lower.
    val refrain = gypsyLine(0 to 57, 2 to 57, 4 to 62, 8 to 57, 10 to 57, 12 to 50)

    // A piano reduction of the sung phrases. Keep the sustained F, the D–C answer,
    // the pickups across bar lines, and the empty space between the lines.
    val firstVerse = listOf(
        gypsyLine(7 to 65, 11 to 65, 13 to 65),
        gypsyLine(2 to 65, 7 to 65, 9 to 64, 11 to 57, 13 to 65, 15 to 65),
        gypsyLine(1 to 62, 5 to 62, 9 to 60, end = 13),
        gypsyLine(0 to 65, 5 to 62, 9 to 60, end = 13),
        gypsyLine(7 to 65, 9 to 65, 11 to 65),
        gypsyLine(1 to 65, 3 to 65, 8 to 65, 13 to 62),
        gypsyLine(1 to 65, 5 to 62, 9 to 60, 15 to 58),
        gypsyLine(1 to 65, 5 to 62, 9 to 60, end = 13),
        gypsyLine(9 to 65, 11 to 65, 13 to 65, 15 to 65),
        gypsyLine(1 to 64, 3 to 65, 7 to 62, 13 to 60),
        gypsyLine(1 to 60, 5 to 62, 9 to 60, end = 13),
        gypsyLine(5 to 62, 9 to 60, end = 13),
        gypsyLine(7 to 55, 9 to 65, 11 to 64, 13 to 65, 15 to 65),
        gypsyLine(5 to 65, 7 to 65, 9 to 65, 11 to 65, end = 14),
    )

    // The second verse has a longer syllable on E and an exposed organ answer before
    // the next four-bar line. A null bar hands those actual chord attacks to the player.
    val secondVerse = listOf(
        gypsyLine(8 to 65, 12 to 65, 14 to 65),
        gypsyLine(0 to 65, 4 to 65, 6 to 64, 14 to 64),
        gypsyLine(2 to 64, end = 14), null,
        gypsyLine(7 to 65, 10 to 65, 12 to 65, 15 to 65),
        gypsyLine(2 to 65, 5 to 64, 12 to 64),
        gypsyLine(0 to 62, 8 to 64, 12 to 62, end = 21),
        gypsyLine(14 to 62),
        gypsyLine(8 to 65, 11 to 65, 14 to 65),
        gypsyLine(0 to 65, 4 to 65, 6 to 64, 14 to 60),
        gypsyLine(0 to 60, 6 to 62, end = 11),
        gypsyLine(1 to 64, 6 to 62, end = 13),
        gypsyLine(6 to 62, 8 to 65, 11 to 65, 14 to 64),
        gypsyLine(4 to 65, 6 to 64, end = 12),
    )
}

private fun gypsyMelody(bar: Int): List<GypsyTone>? = when (gypsyPart(bar)) {
    GypsyPart.VerseOne -> GypsyMelodies.firstVerse[bar - 4]
    GypsyPart.VerseTwo -> GypsyMelodies.secondVerse[bar - 24]
    GypsyPart.Chorus, GypsyPart.BareChorus, GypsyPart.Reprise -> GypsyMelodies.refrain
    GypsyPart.Bridge, GypsyPart.Intro, GypsyPart.Organ, GypsyPart.Breakdown, GypsyPart.Outro -> null
}

internal fun gypsyOrganLeadAt(atMs: Long): Boolean = gypsyMelody((atMs / gypsyBarMs).toInt()) == null

private fun gypsyChart(): Chart {
    val lead = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until gypsyBars) {
        val start = bar * gypsyBarMs
        val part = gypsyPart(bar)
        val vocal = gypsyMelody(bar)
        val gain = if (part == GypsyPart.Outro) 1f - (bar - 108) * .135f else 1f
        fun play(step: Int, pitch: Int, duration: Long, velocity: Float) {
            val ms = start + step * gypsyStep
            lead += Note("gypsy-woman-${bar * 16 + step}", ms, pitch, duration, gain * velocity)
        }
        fun backing(voice: BackingVoice, step: Int, pitch: Int, duration: Long, velocity: Float) {
            band += BackingNote(start + step * gypsyStep, pitch, duration, gain * velocity, voice)
        }
        for (tone in vocal.orEmpty()) {
            play(tone.step, tone.pitch, tone.length * gypsyStep - 22,
                if (tone.length >= 3) .95f else .85f)
        }
        for ((index, step) in gypsyStabs.withIndex()) {
            val chord = organChord(bar, step)
            val gate = gypsyGates[index]
            val accent = gypsyAccents[index]
            val crest = chord.keys.last()
            if (vocal == null) play(step, crest, gate, .85f * accent)
            for (key in chord.keys) {
                if (vocal == null && key == crest) continue
                if (vocal?.any { it.step == step && it.pitch == key } == true) continue
                backing(BackingVoice.Keys, step, key, gate, (if (vocal == null) .65f else .52f) * accent)
            }
        }

        val full = when (part) {
            GypsyPart.Intro, GypsyPart.VerseOne, GypsyPart.BareChorus, GypsyPart.Breakdown -> false
            else -> true
        }
        // Let the organ state the pocket on its own. At the refrain the sub bass and
        // four-on-the-floor kick take over; their space drops out again for the breakdown.
        if (full) {
            for (step in 0 until 16 step 4) {
                backing(BackingVoice.Kick, step, 36, 164, if (step == 0) .97f else .87f)
                if (step == 4 || step == 12) backing(BackingVoice.Snare, step, 39, 121, .70f)
                backing(BackingVoice.Hat, step + 2, if (step == 12) 46 else 42,
                    if (step == 12) 177 else 80, if (step == 4 || step == 12) .49f else .42f)
            }
            for ((i, step) in gypsyStabs.withIndex()) {
                backing(BackingVoice.Bass, step, organChord(bar, step).root,
                    if (step == 0) 320 else 169, gypsyAccents[i] * .77f)
            }
            if ((part == GypsyPart.Organ || part == GypsyPart.Reprise) && bar % 2 == 1) {
                backing(BackingVoice.Hat, 9, 70, 56, .26f)
                backing(BackingVoice.Hat, 15, 70, 62, .34f)
            }
        }
        if (part == GypsyPart.Organ || part == GypsyPart.Reprise || part == GypsyPart.Outro) {
            // A soft string answer joins the second half of the eight-bar cycle. Leave
            // the first half bare so the tonic stabs retain their percussive attack.
            if (bar % 8 == 6 || bar % 8 == 7) {
                for (step in listOf(0, 7)) {
                    val chord = organChord(bar, step)
                    backing(BackingVoice.Pad, step, chord.keys[chord.keys.lastIndex - 1], if (step == 0) 820 else 1040, .34f)
                }
            }
        }
    }
    return Chart("Gypsy Woman", gypsyBpm, gypsyBars * gypsyBarMs, lead, band.sortedBy { it.atMs },
        authoredLandings = true)
}

internal val gypsyWomanSong = Song.arranged(
    id = "gypsy-woman", title = "Gypsy Woman", artist = "Crystal Waters", bpm = gypsyBpm,
    durationMs = gypsyBars * gypsyBarMs, backingAsset = "backing/gypsy-woman.m4a",
    bundledRevision = 4, load = ::gypsyChart,
)
