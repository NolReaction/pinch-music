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
            for (i in 1 until chord.size) add(BackingVoice.Pad, chord[i], 0.0, if (last) 9.0 else 14.8, .31f * g.air)
            if (last) {
                add(BackingVoice.Bass, chord[0], 0.0, 8.0, .66f)
                add(BackingVoice.Kick, 36, 0.0, 1.2, .49f)
            } else for (beat in 0..3) {
                val t = beat * 4.0
                if (g.house || beat % 2 == 0) add(BackingVoice.Kick, 36, t, 1.4, .82f * g.drums)
                if (beat % 2 == 1) add(BackingVoice.Snare, 38, t + .06, 1.05, .51f * g.drums)
                if (g.house || beat % 2 == 1) add(BackingVoice.Hat, 42, t + 2.03, .50, .32f * g.drums)
                if (g.house) {
                    val octave = if (beat == 3 && index % 4 == 3) 12 else 0
                    add(BackingVoice.Bass, chord[0] + octave, t + 2.0, 1.75, .74f * g.bass)
                    if (beat % 2 == 1) {
                        for (i in 1 until chord.size) add(BackingVoice.Keys, chord[i], t + 2.08, 1.35, .31f * g.air)
                    }
                } else if (beat % 2 == 0) add(BackingVoice.Bass, chord[0], t, 4.7, .68f * g.bass)
            }
            bar++
        }
    }
    return notes.sortedBy { it.atMs }
}
