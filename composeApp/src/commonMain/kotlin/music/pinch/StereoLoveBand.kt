package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice

/** Balance the live lead against the rhythm section in the different parts of Stereo Love. */
internal enum class StereoGroove(val drums: Float, val bass: Float, val air: Float, val house: Boolean = true) {
    Intro(.23f, .45f, .78f, false), Soft(.30f, .52f, .78f, false), Lift(.65f, .70f, .80f),
    Club(.97f, .94f, .84f), Turn(.49f, .66f, .84f), Groove(.80f, .82f, .75f),
    Build(.83f, .84f, .86f), Break(.16f, .38f, .88f, false), Peak(1f, .98f, .96f),
    Bridge(.50f, .68f, .84f), Final(.94f, .92f, 1f), Outro(.77f, .82f, .84f),
}

internal data class StereoBandSection(val groove: StereoGroove, val chords: IntArray)

/** Same progression and offbeat bass as the recorded mix. No lead in this part:
 * all three selectable instruments get the same space for the player's attack. */
internal fun stereoBand(bpm: Int, harmonies: Array<IntArray>, sections: List<StereoBandSection>): List<BackingNote> {
    val tickMs = 15_000.0 / bpm
    val notes = ArrayList<BackingNote>()
    var bar = 0
    for (section in sections) {
        val g = section.groove
        for ((index, chordId) in section.chords.withIndex()) {
            val last = g == StereoGroove.Outro && index == section.chords.lastIndex
            val chord = harmonies[chordId]
            fun add(voice: BackingVoice, pitch: Int, offset: Double, length: Double, velocity: Float) {
                val at = ((bar * 16 + offset) * tickMs).roundToLong()
                notes += BackingNote(at, pitch, (length * tickMs).roundToLong(), velocity, voice)
            }
            // Keep the inner voices below the melody's C#4 floor for the entire phrase.
            // Octaving them away only when a matching lead arrives makes held chords jump.
            val voices = chord.drop(1).map { if (it >= 60) it - 12 else it }.sorted()
            for ((part, pitch) in voices.withIndex()) {
                val level = if (part == 0) .22f else .29f
                add(BackingVoice.Pad, pitch, .04 * part, if (last) 9.0 else 15.0, level * g.air)
            }
            val cadence = (index + 1) % 4 == 0
            val swell = when (g) {
                StereoGroove.Build -> .72f + .28f * index / section.chords.lastIndex.coerceAtLeast(1)
                StereoGroove.Outro -> 1f - .18f * index
                else -> 1f
            }
            if (last) {
                add(BackingVoice.Bass, chord[0], 0.0, 8.0, .54f)
                add(BackingVoice.Kick, 36, 0.0, 1.2, .42f)
            } else for (beat in 0..3) {
                val t = beat * 4.0
                val drums = g.drums * swell
                if (g.house || beat % 2 == 0) {
                    val accent = if (beat == 0) .85f else .72f
                    add(BackingVoice.Kick, 36, t, 1.4, accent * drums)
                }
                if (beat % 2 == 1) {
                    add(BackingVoice.Snare, 38, t + .05, 1.05, (if (beat == 3) .57f else .49f) * drums)
                }
                if (g.house || beat % 2 == 1) {
                    add(BackingVoice.Hat, 42, t + 2.03, .55, (if (beat % 2 == 0) .27f else .38f) * drums)
                }
                if (g.house) {
                    val pickup = beat == 3 && cadence
                    val pitch = chord[0] + if (pickup) 12 else 0
                    add(BackingVoice.Bass, pitch, t + 2.0, if (pickup) .85 else 1.66, .70f * g.bass * swell)
                    if (beat == 1 || (beat == 3 && !cadence)) {
                        // A two-note offbeat guitar/keys chop. The pad already supplies the full triad.
                        add(BackingVoice.Keys, voices[1], t + 2.06, .96, .34f * g.air)
                        add(BackingVoice.Keys, voices[if (beat == 1) 2 else 0], t + 2.09, 1.13, .28f * g.air)
                    }
                    if (pickup && (g == StereoGroove.Club || g == StereoGroove.Peak || g == StereoGroove.Final)) {
                        add(BackingVoice.Hat, 42, t + 3.10, .34, .17f * drums)
                    }
                } else if (beat % 2 == 0) {
                    val length = if (beat == 0) 5.8 else 3.4
                    add(BackingVoice.Bass, chord[0], t, length, .64f * g.bass)
                }
            }
            bar++
        }
    }
    return notes.sortedBy { it.atMs }
}
