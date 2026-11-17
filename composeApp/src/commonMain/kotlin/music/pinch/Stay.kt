package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val stayBpm = 170
private const val stayBars = 96
private fun stayTime(step: Double) = (step * 15_000.0 / stayBpm).roundToLong()

internal enum class StayPart { Intro, Chorus, Verse, Rise, Breakdown, Finale, Outro }
private data class StaySection(val bar: Int, val part: StayPart, val variation: Int = 0)
private val staySections = listOf(
    StaySection(0, StayPart.Intro),
    StaySection(8, StayPart.Chorus),
    StaySection(24, StayPart.Verse),
    StaySection(40, StayPart.Rise),
    StaySection(48, StayPart.Chorus, 1),
    StaySection(64, StayPart.Breakdown),
    StaySection(72, StayPart.Finale),
    StaySection(88, StayPart.Outro),
)
private fun staySection(bar: Int) = staySections.last { it.bar <= bar }
internal fun stayPartAt(atMs: Long): StayPart =
    staySection(((atMs * stayBpm / 15_000.0).roundToLong() / 16).toInt()).part

private data class StayTone(val step: Int, val midi: Int, val length: Double = 1.6, val touch: Float = 1f)
private fun s(step: Int, midi: Int, length: Double = 1.6, touch: Float = 1f) = StayTone(step, midi, length, touch)

// Short motif references from GEERDES's publicly offered demo (article 43104.00,
// melody channel 4 and keyboard channel 3). The vendor demo is not the full score.
// https://geerdes.media/apps/midifile/en/stay-explicit-the-kid-laroi-justin-bieber-43104.00.html
// Retain the characteristic chorus pickup, long answer and contrasting verse entrance;
// the 96-bar form, subsequent verse development, break and coda are this game's arrangement.
private val stayChorusCall = listOf(s(0, 60), s(2, 61), s(4, 61), s(6, 68, 4.9), s(12, 60), s(14, 60, 1.9))
private val stayChorusReply = listOf(s(0, 60), s(2, 60), s(4, 60), s(6, 60), s(8, 60), s(10, 61), s(12, 61), s(14, 60))
private val stayChorusLift = listOf(s(0, 60), s(2, 61), s(4, 61), s(6, 68, 5.2), s(12, 61), s(14, 63))
private val stayChorusClose = listOf(s(0, 63), s(2, 72, 5.35), s(8, 68, 7.8))
private val stayVerseCall = listOf(s(2, 65), s(4, 65), s(6, 65), s(8, 65), s(10, 61), s(12, 61), s(14, 61))
private val stayVerseReply = listOf(s(0, 61), s(2, 63), s(4, 63, 6.0))

private fun stayRiff(local: Int): List<StayTone> = when (local % 4) {
    0, 2 -> listOf(s(2, 60, 1.1), s(4, 61, 1.1), s(6, 68, 1.1), s(12, 61, 1.1))
    1 -> listOf(s(4, 60, 1.1), s(6, 61, 1.1), s(12, 56, 1.1))
    else -> listOf(s(4, 60, 1.1), s(6, 61, 1.1), s(8, 60, 1.1), s(10, 56, 1.1), s(12, 58, 1.1), s(14, 56, 1.1))
}

private fun stayChorus(local: Int): List<StayTone> = when (local % 8) {
    6 -> stayChorusLift
    7 -> stayChorusClose
    0, 2, 4 -> stayChorusCall
    else -> stayChorusReply
}

private fun stayVerse(local: Int): List<StayTone> = when (local) {
    0 -> stayVerseCall
    1 -> stayVerseReply
    2 -> listOf(s(4, 65, 2.6), s(8, 65), s(10, 61), s(14, 61))
    3 -> listOf(s(0, 61), s(2, 63, 5.6), s(10, 60, 2.6), s(14, 61))
    4 -> listOf(s(2, 65, 2.6), s(6, 65), s(8, 61, 2.6), s(12, 61))
    5 -> listOf(s(0, 61), s(4, 63, 7.0))
    6 -> listOf(s(2, 65), s(4, 65), s(8, 68, 3.2), s(12, 65, 2.7))
    7 -> listOf(s(0, 63, 4.6), s(6, 60, 3.4), s(12, 61, 2.7))
    8 -> listOf(s(2, 72), s(4, 72), s(6, 73, 3.4), s(10, 63), s(12, 63), s(14, 61))
    9 -> listOf(s(0, 63, 3.5), s(4, 63), s(6, 61), s(8, 63, 3.5), s(12, 65, 3.2))
    10 -> listOf(s(2, 65, 2.8), s(6, 61), s(8, 63, 2.6), s(12, 61, 2.6))
    11 -> listOf(s(0, 63, 3.2), s(4, 65, 3.2), s(8, 60, 3.2), s(12, 61, 3.2))
    12 -> listOf(s(2, 61), s(4, 65), s(6, 63, 3.2), s(10, 61), s(12, 65), s(14, 63, 1.8))
    13 -> listOf(s(2, 61, 2.8), s(6, 63, 5.3), s(12, 65, 2.8))
    14 -> listOf(s(0, 65, 4.8), s(6, 68, 3.4), s(12, 65, 2.8))
    else -> listOf(s(0, 63, 5.5), s(8, 60, 3.2), s(12, 61, 3.2))
}

private fun stayMelody(bar: Int): List<StayTone> {
    val section = staySection(bar)
    val local = bar - section.bar
    return when (section.part) {
        StayPart.Intro -> if (local == 0) listOf(s(0, 66, 1.7)) + stayRiff(local) else stayRiff(local)
        StayPart.Chorus, StayPart.Finale -> stayChorus(local)
        StayPart.Verse -> stayVerse(local)
        StayPart.Rise -> when (local) {
            0 -> listOf(s(0, 68, 3.0), s(4, 65, 2.8), s(8, 68, 2.8), s(12, 65, 2.8))
            1 -> listOf(s(0, 63), s(2, 63), s(4, 63), s(6, 65), s(8, 63, 3.0), s(12, 61, 3.4))
            2 -> listOf(s(0, 68, 5.4), s(8, 65, 3.2), s(12, 68, 3.2))
            3 -> listOf(s(0, 63, 3.2), s(4, 65, 3.2), s(8, 63, 3.2), s(12, 61, 3.2))
            4 -> listOf(s(0, 68, 7.0), s(8, 65, 5.5))
            5 -> listOf(s(0, 63, 5.4), s(8, 65, 2.8), s(12, 68, 2.8))
            6 -> listOf(s(0, 68, 5.4), s(8, 65, 3.2), s(12, 63, 3.2))
            else -> listOf(s(0, 63, 3.2), s(4, 65, 3.2), s(8, 63, 3.2), s(12, 61, 2.8))
        }
        StayPart.Breakdown -> when (local) {
            0 -> listOf(s(0, 61, 5.8), s(8, 68, 5.8))
            1 -> listOf(s(0, 60, 9.0))
            2 -> listOf(s(2, 61, 4.8), s(10, 65, 3.8))
            3 -> listOf(s(0, 60, 6.5), s(12, 56, 3.2))
            4 -> listOf(s(0, 61, 3.5), s(6, 68, 6.0))
            5 -> listOf(s(0, 60, 5.0), s(8, 63, 5.0))
            6 -> stayChorusLift
            else -> stayChorusClose
        }
        StayPart.Outro -> when {
            local < 4 -> stayRiff(local)
            local == 4 -> listOf(s(0, 61, 5.8), s(8, 68, 5.8))
            local == 5 -> listOf(s(0, 63, 5.8), s(8, 60, 5.8))
            local == 6 -> listOf(s(0, 65, 5.8), s(8, 61, 5.8))
            else -> listOf(s(0, 61, 10.5))
        }
    }
}

private data class StayChord(val bass: Int, val notes: List<Int>)
private val stayHarmony = listOf(
    StayChord(42, listOf(54, 58, 61)),
    StayChord(44, listOf(56, 60, 63)),
    StayChord(46, listOf(58, 61, 65)),
    StayChord(41, listOf(53, 56, 60)),
)

private fun stayChart(): Chart {
    val lead = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until stayBars) {
        val section = staySection(bar)
        val part = section.part
        val local = bar - section.bar
        val start = bar * 16
        val last = bar == stayBars - 1
        val chord = if (last) StayChord(37, listOf(53, 56, 61)) else stayHarmony[bar % 4]
        val fade = if (part == StayPart.Outro) 1f - local * .095f else 1f
        val energy = when (part) {
            StayPart.Intro -> if (local < 4) .67f else .83f
            StayPart.Breakdown -> if (local < 4) .56f else .76f
            StayPart.Verse -> .82f
            StayPart.Rise -> .83f + local * .015f
            StayPart.Finale -> .98f
            else -> .93f
        }
        val melody = stayMelody(bar)
        for ((index, tone) in melody.withIndex()) {
            val at = stayTime((start + tone.step).toDouble())
            val length = stayTime(start + tone.step + tone.length) - at - 6L
            val accent = if (index == 0 || tone.length > 3) 1f else if (tone.step % 4 == 0) .94f else .87f
            lead += Note("stay-${start + tone.step}", at, tone.midi, length,
                (.88f * accent * fade * tone.touch).coerceIn(0f, 1f))
        }
        fun add(voice: BackingVoice, step: Double, midi: Int, length: Double, velocity: Float) {
            val at = stayTime(start + step)
            band += BackingNote(at, midi, stayTime(start + step + length) - at,
                (velocity * fade).coerceIn(0f, 1f), voice)
        }
        val bare = part == StayPart.Breakdown && local < 4
        for ((index, midi) in chord.notes.withIndex()) {
            add(BackingVoice.Pad, index * .035, midi, if (last) 11.0 else 15.2,
                if (bare) .39f else if (part == StayPart.Intro) .30f else .36f)
        }
        if (last) {
            add(BackingVoice.Bass, 0.0, chord.bass, 10.0, .43f)
            continue
        }
        val thin = (part == StayPart.Intro && local < 4) || bare || (part == StayPart.Outro && local >= 4)
        val bassSteps = if (thin) listOf(0.0) else if (part == StayPart.Verse) listOf(0.0, 6.0, 10.0)
            else listOf(0.0, 6.0, 10.0, 14.0)
        for ((index, step) in bassSteps.withIndex()) {
            val duration = if (thin) 13.0 else if (index == 0) 5.2 else 1.7
            add(BackingVoice.Bass, step, chord.bass, duration, energy * if (index == 0) .79f else .64f)
        }
        if (!thin) {
            val kickSteps = if (bar % 4 == 3) listOf(0.0, 8.0, 10.0) else listOf(0.0, 8.0)
            for (step in kickSteps) {
                add(BackingVoice.Kick, step, 36, 1.55, energy * if (step == 0.0) .95f else .75f)
            }
            for (step in listOf(4.0, 12.0)) add(BackingVoice.Snare, step, 38, 1.0, energy * .84f)
            if (part == StayPart.Finale || (part == StayPart.Chorus && section.variation > 0)) {
                add(BackingVoice.Snare, 15.0, 37, .65, .16f)
            }
            for (step in 0 until 16 step 2) {
                add(BackingVoice.Hat, step + .055, if (step == 14 && bar % 2 == 1) 46 else 42,
                    .65, energy * if (step % 4 == 2) .31f else .20f)
            }
            if (local % 8 == 7 && part != StayPart.Outro) {
                add(BackingVoice.Snare, 13.0, 37, .60, energy * .24f)
                add(BackingVoice.Snare, 14.0, 37, .60, energy * .31f)
            }
        } else if (part == StayPart.Intro && local >= 2) {
            add(BackingVoice.Snare, 8.0, 37, .65, .26f)
            for (step in listOf(2.0, 6.0, 10.0, 14.0)) add(BackingVoice.Hat, step, 70, .60, .17f)
        }
        // The recognisable keyboard motif becomes a quiet answer under the melody.
        // Never double a foreground note or fill the complete sparse breakdown.
        if (part != StayPart.Intro && part != StayPart.Outro && !bare) {
            for (tone in stayRiff(bar)) {
                val overlaps = melody.any { it.midi == tone.midi && tone.step >= it.step && tone.step < it.step + it.length }
                if (!overlaps && (tone.step == 6 || tone.step >= 12 || part == StayPart.Verse)) {
                    add(BackingVoice.Keys, tone.step.toDouble(), tone.midi, 1.15, .34f * energy)
                }
            }
        }
    }
    return Chart("Stay", stayBpm, stayTime(stayBars * 16.0), lead,
        band.sortedBy { it.atMs }, authoredLandings = true)
}

internal val staySong = Song.arranged("stay", "Stay", "The Kid LAROI & Justin Bieber",
    stayBpm, stayTime(stayBars * 16.0), backingAsset = "backing/stay.m4a", bundledRevision = 5,
    load = ::stayChart)
