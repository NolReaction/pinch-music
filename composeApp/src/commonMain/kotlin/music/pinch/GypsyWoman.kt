package music.pinch

import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val gypsyBpm = 120
private const val gypsyBars = 102
private const val gypsyStep = 125L
private const val gypsyBarMs = 16 * gypsyStep

private enum class GypsyPart { Intro, Verse, Refrain, Interlude, Breakdown, Outro, Coda }
internal enum class GypsyTexture { Organ, ElectricPiano, Mallet }
internal enum class GypsyColour { Plucked, Mallet, Strings }

private data class GypsySection(val first: Int, val part: GypsyPart, val variation: Int = 0)
private val gypsySections = listOf(
    GypsySection(0, GypsyPart.Intro),
    GypsySection(4, GypsyPart.Verse),
    GypsySection(20, GypsyPart.Refrain),
    GypsySection(28, GypsyPart.Verse, 1),
    GypsySection(44, GypsyPart.Refrain, 1),
    GypsySection(52, GypsyPart.Interlude),
    GypsySection(60, GypsyPart.Breakdown),
    GypsySection(68, GypsyPart.Verse, 2),
    GypsySection(84, GypsyPart.Refrain, 2),
    GypsySection(92, GypsyPart.Outro),
    GypsySection(100, GypsyPart.Coda),
)
private fun gypsySection(bar: Int) = gypsySections.last { bar >= it.first }

internal fun gypsyTextureAt(atMs: Long): GypsyTexture = when (gypsySection((atMs / gypsyBarMs).toInt()).part) {
    GypsyPart.Verse, GypsyPart.Refrain, GypsyPart.Outro -> GypsyTexture.ElectricPiano
    GypsyPart.Breakdown -> GypsyTexture.Mallet
    else -> GypsyTexture.Organ
}

internal fun gypsyColourAt(atMs: Long): GypsyColour = when (gypsySection((atMs / gypsyBarMs).toInt()).part) {
    GypsyPart.Verse, GypsyPart.Interlude -> GypsyColour.Plucked
    GypsyPart.Refrain -> GypsyColour.Mallet
    else -> GypsyColour.Strings
}

private data class OrganChord(val root: Int, val keys: List<Int>)

// A restrained close-voicing arrangement of the demonstrated organ progression.
// Funk Fiction's keyboard tutorial and GEERDES's public demo disagree on some
// extensions/registers; these voicings are our musical choice, not an exact master transcription.
private val gypsyDm9 = OrganChord(38, listOf(50, 53, 57, 60, 64))
private val gypsyDm7 = OrganChord(38, listOf(50, 53, 57, 60, 62))
private val gypsyAm = OrganChord(33, listOf(45, 55, 60, 64))
private val gypsyBb = OrganChord(34, listOf(46, 57, 62, 65))
private val gypsyGm = OrganChord(31, listOf(43, 53, 58, 62))
private val gypsyC7 = OrganChord(36, listOf(48, 55, 58, 64))
private val gypsyEm = OrganChord(40, listOf(52, 55, 59, 62))
private val gypsyEb = OrganChord(39, listOf(51, 55, 58, 62))

private val gypsyStabs = listOf(0, 4, 7, 10, 13)
private val gypsyGates = listOf(246L, 183L, 156L, 171L, 194L)
private val gypsyAccents = listOf(1f, .82f, .94f, .78f, .88f)
private fun organChord(bar: Int, step: Int): OrganChord {
    val section = gypsySection(bar)
    val phase = when (section.part) {
        GypsyPart.Intro -> bar + 4
        GypsyPart.Coda -> 0
        else -> bar - section.first
    } % 8
    val first = step < 7
    return when (phase) {
        2, 6 -> if (first) gypsyAm else gypsyBb
        3 -> if (first) gypsyGm else gypsyC7
        7 -> if (first) gypsyEm else gypsyEb
        else -> if (first) gypsyDm9 else gypsyDm7
    }
}

private data class GypsyTone(val step: Int, val pitch: Int, val length: Int, val touch: Float = 1f)
private fun tone(step: Int, pitch: Int, length: Int, touch: Float = 1f) = GypsyTone(step, pitch, length, touch)

// Short melodic references verified against the vendor's freely exposed demo of
// article 982.00 (channel 4, melody). The demo is an excerpt, not a complete score.
// https://geerdes.media/apps/midifile/gypsy-woman-she-s-homeless-la-da-dee-la-da-da-radio-crystal-waters-982.00.html
// These gestures and their gaps are retained; the longer verse development and
// 16/8-bar form below are the game's instrumental arrangement.
private val gypsyResponse = listOf(tone(0, 62, 4), tone(4, 60, 4, .90f), tone(12, 65, 4, .92f))
private val gypsyResponseEnd = listOf(tone(0, 62, 4), tone(4, 60, 4, .88f))
private val gypsyCall = listOf(tone(2, 64, 2, .88f), tone(4, 65, 2), tone(8, 65, 1, .82f),
    tone(10, 65, 2, .95f), tone(12, 64, 2, .87f), tone(14, 65, 2, .96f))
private val gypsyCallEnd = listOf(tone(0, 64, 5), tone(8, 64, 1, .78f), tone(10, 64, 2, .90f))
private val gypsyPreCall = listOf(tone(4, 65, 2), tone(6, 64, 2, .88f), tone(8, 65, 1, .82f), tone(10, 65, 2, .96f))
private val gypsyPreEnd = listOf(tone(0, 65, 1, .84f), tone(2, 65, 2), tone(4, 64, 2, .88f),
    tone(6, 65, 2, .96f), tone(8, 64, 4, .94f), tone(12, 56, 2, .85f), tone(14, 57, 2, .96f))
private val gypsyHook = listOf(tone(0, 62, 4), tone(4, 56, 2, .82f), tone(6, 57, 2, .94f),
    tone(8, 50, 4), tone(12, 56, 2, .82f), tone(14, 57, 2, .94f))

private fun gypsyVerse(local: Int, variation: Int): List<GypsyTone> = when (local) {
    0, 12 -> gypsyResponse
    1, 13 -> gypsyResponseEnd
    2 -> gypsyCall
    3 -> gypsyCallEnd
    4 -> when (variation) {
        1 -> listOf(tone(2, 62, 6), tone(12, 65, 3, .87f))
        2 -> listOf(tone(0, 62, 7), tone(10, 60, 3, .86f))
        else -> listOf(tone(0, 62, 6), tone(10, 60, 2, .85f), tone(14, 62, 2, .92f))
    }
    5 -> when (variation) {
        1 -> listOf(tone(0, 65, 3), tone(6, 62, 3, .92f), tone(12, 60, 2, .86f))
        2 -> listOf(tone(4, 65, 4), tone(12, 62, 2, .86f))
        else -> listOf(tone(4, 65, 4), tone(10, 62, 4, .90f))
    }
    6, 14 -> gypsyPreCall
    7 -> gypsyPreEnd.take(5) // An answer with a breath; save the pickup for the real refrain.
    8 -> when (variation) {
        1 -> listOf(tone(0, 62, 4), tone(8, 60, 5, .88f))
        2 -> listOf(tone(2, 62, 4), tone(8, 65, 3, .91f), tone(14, 62, 2, .85f))
        else -> listOf(tone(0, 62, 7), tone(12, 65, 3, .90f))
    }
    9 -> when (variation) {
        1 -> listOf(tone(0, 65, 4), tone(8, 62, 3, .90f), tone(12, 60, 2, .85f))
        2 -> listOf(tone(4, 60, 6, .90f))
        else -> listOf(tone(4, 60, 4, .90f), tone(12, 62, 2, .86f))
    }
    10 -> if (variation == 1) gypsyCall else gypsyCall.take(4)
    11 -> if (variation == 2) gypsyCallEnd else gypsyCallEnd.take(1)
    else -> gypsyPreEnd
}

private fun gypsyMelody(bar: Int): List<GypsyTone> {
    val section = gypsySection(bar)
    val local = bar - section.first
    return when (section.part) {
        GypsyPart.Verse -> gypsyVerse(local, section.variation)
        GypsyPart.Refrain -> when (local) {
            6 -> listOf(tone(0, 62, 6), tone(8, 50, 6, .94f))
            7 -> gypsyHook.take(4)
            else -> gypsyHook
        }
        GypsyPart.Breakdown -> when (local) {
            0 -> listOf(tone(0, 62, 6), tone(10, 60, 3, .85f))
            1 -> listOf(tone(4, 65, 5))
            2 -> gypsyCall.filter { it.step == 2 || it.step == 10 || it.step == 14 }
            3 -> gypsyCallEnd.take(1)
            4 -> gypsyResponse
            5 -> gypsyResponseEnd
            6 -> gypsyPreCall
            else -> gypsyPreEnd
        }
        GypsyPart.Outro -> when (local) {
            0, 1 -> gypsyHook
            2 -> gypsyHook.take(4)
            3 -> listOf(tone(0, 62, 4), tone(8, 50, 6))
            4 -> gypsyResponse
            5 -> gypsyResponseEnd
            6 -> gypsyPreCall.take(2)
            else -> listOf(tone(0, 65, 2), tone(4, 64, 6, .88f))
        }
        else -> emptyList()
    }
}

private fun gypsyChart(): Chart {
    val lead = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until gypsyBars) {
        val start = bar * gypsyBarMs
        val section = gypsySection(bar)
        val part = section.part
        val local = bar - section.first
        val fade = when (part) {
            GypsyPart.Outro -> 1f - local * .095f
            GypsyPart.Coda -> .22f
            else -> 1f
        }
        val gain = fade * when (bar % 4) { 1 -> .96f; 3 -> .93f; else -> 1f }
        val melody = gypsyMelody(bar)
        val organLead = part == GypsyPart.Intro || part == GypsyPart.Interlude || part == GypsyPart.Coda
        fun backing(voice: BackingVoice, step: Int, pitch: Int, duration: Long, velocity: Float, lateMs: Long = 0) {
            band += BackingNote(start + step * gypsyStep + lateMs, pitch, duration, gain * velocity, voice)
        }
        for (note in melody) {
            val entrance = if (part == GypsyPart.Verse && section.variation == 0 && local < 4) .92f else 1f
            lead += Note("gypsy-woman-${bar * 16 + note.step}", start + note.step * gypsyStep,
                note.pitch, note.length * gypsyStep - 18, gain * .90f * note.touch * entrance)
        }

        val stabs = when {
            bar == gypsyBars - 1 -> listOf(0)
            part == GypsyPart.Breakdown -> listOf(0, 7, 13)
            else -> gypsyStabs
        }
        for (step in stabs) {
            val chord = organChord(bar, step)
            val index = gypsyStabs.indexOf(step)
            val gate = if (bar == gypsyBars - 1) 1450L else gypsyGates[index]
            val accent = gypsyAccents[index]
            if (organLead) {
                lead += Note("gypsy-woman-${bar * 16 + step}", start + step * gypsyStep,
                    chord.keys.last(), gate, gain * .91f * accent)
            }
            for (key in if (organLead) chord.keys.dropLast(1) else chord.keys) {
                // Do not double the foreground's current note with another simultaneous attack.
                if (melody.any { it.pitch == key && step >= it.step && step < it.step + it.length }) continue
                backing(BackingVoice.Keys, step, key, gate, (if (organLead) .66f else .57f) * accent)
            }
        }

        val drums = part == GypsyPart.Verse || part == GypsyPart.Refrain || part == GypsyPart.Interlude ||
            (part == GypsyPart.Outro && local < 4)
        val full = drums && !(part == GypsyPart.Verse && section.variation == 0 && local < 4)
        if (drums) {
            for (step in 0 until 16 step 4) {
                if (full) backing(BackingVoice.Kick, step, 36, 172, if (step == 0) .91f else .82f)
                if (step == 4 || step == 12) {
                    backing(BackingVoice.Snare, step, if (full) 39 else 37, 124, if (full) .62f else .27f)
                }
                backing(BackingVoice.Hat, step + 2, if (full && step == 12 && bar % 2 == 1) 46 else 42,
                    if (step == 12) 144 else 67, if (step == 4 || step == 12) .33f else .25f, lateMs = 9)
            }
            if (part == GypsyPart.Refrain || (part == GypsyPart.Verse && section.variation > 0)) {
                for (step in listOf(1, 5, 9, 15)) {
                    backing(BackingVoice.Hat, step, 70, 48, if (step == 15) .21f else .12f, lateMs = 14)
                }
            }
            if (full && local % 8 == 7) {
                backing(BackingVoice.Snare, 14, 37, 69, .22f)
                backing(BackingVoice.Snare, 15, 37, 58, .29f)
            }
        }

        if (drums || (part == GypsyPart.Breakdown && local >= 4)) {
            val bassGain = if (!full) .46f else .73f
            for ((index, step) in listOf(0, 6, 8, 14).withIndex()) {
                val chord = organChord(bar, step)
                val octave = if (index == 1 || (index == 3 && bar % 2 == 1)) 12 else 0
                backing(BackingVoice.Bass, step, chord.root + octave,
                    if (index == 0 || index == 2) 295 else 106, bassGain * if (octave == 0) 1f else .68f)
            }
        }

        // Small answers occupy written rests; they never replace the playable melody.
        if (part == GypsyPart.Verse && local % 4 == 1) {
            for (step in listOf(10, 14)) {
                if (melody.none { step >= it.step && step < it.step + it.length }) {
                    backing(BackingVoice.Pad, step, organChord(bar, step).keys[2] + 12, 156, .24f)
                }
            }
        } else if (part == GypsyPart.Refrain && local >= 6) {
            backing(BackingVoice.Pad, 14, organChord(bar, 14).keys.last() + 12, 158, .22f)
        } else if (part == GypsyPart.Interlude && local % 2 == 1) {
            backing(BackingVoice.Pad, 6, organChord(bar, 6).keys[2] + 12, 165, .28f)
            backing(BackingVoice.Pad, 15, organChord(bar, 15).keys[2] + 12, 165, .23f)
        } else if (part == GypsyPart.Breakdown) {
            for (step in listOf(0, 7)) {
                backing(BackingVoice.Pad, step, organChord(bar, step).keys[1], if (step == 0) 790 else 950, .19f)
            }
        }
    }
    return Chart("Gypsy Woman", gypsyBpm, gypsyBars * gypsyBarMs, lead.sortedBy { it.atMs },
        band.sortedBy { it.atMs }, authoredLandings = true)
}

internal val gypsyWomanSong = Song.arranged(
    id = "gypsy-woman", title = "Gypsy Woman", artist = "Crystal Waters", bpm = gypsyBpm,
    durationMs = gypsyBars * gypsyBarMs, backingAsset = "backing/gypsy-woman.m4a",
    bundledRevision = 6, load = ::gypsyChart,
)
