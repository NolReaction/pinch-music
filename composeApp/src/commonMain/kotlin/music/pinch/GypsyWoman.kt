package music.pinch

import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val gypsyBpm = 120
private const val gypsyBars = 114
private const val gypsyStep = 125L
private const val gypsyBarMs = 16 * gypsyStep

/** An instrumental arrangement of the chord hook, not a synthetic imitation of the vocal. */
private enum class GypsyPart { Intro, Groove, Lift, Bridge, Peak, Breakdown, Reprise, Outro, Coda }
internal enum class GypsyTexture { Organ, ElectricPiano, Mallet }
internal enum class GypsyColour { Plucked, Mallet, Strings }

private fun gypsyPart(bar: Int): GypsyPart = when (bar) {
    in 0..7 -> GypsyPart.Intro
    in 8..23 -> GypsyPart.Groove
    in 24..39 -> GypsyPart.Lift
    in 40..47 -> GypsyPart.Bridge
    in 48..63 -> GypsyPart.Peak
    in 64..71 -> GypsyPart.Breakdown
    in 72..87 -> GypsyPart.Reprise
    in 88..95 -> GypsyPart.Bridge
    in 96..103 -> GypsyPart.Peak
    in 104..111 -> GypsyPart.Outro
    else -> GypsyPart.Coda
}

internal fun gypsyTextureAt(atMs: Long): GypsyTexture = when (gypsyPart((atMs / gypsyBarMs).toInt())) {
    GypsyPart.Bridge -> GypsyTexture.ElectricPiano
    GypsyPart.Breakdown -> GypsyTexture.Mallet
    else -> GypsyTexture.Organ
}

internal fun gypsyColourAt(atMs: Long): GypsyColour = when (gypsyPart((atMs / gypsyBarMs).toInt())) {
    GypsyPart.Lift -> GypsyColour.Plucked
    GypsyPart.Peak, GypsyPart.Reprise -> GypsyColour.Mallet
    else -> GypsyColour.Strings
}

private data class OrganChord(val root: Int, val keys: List<Int>)

// Dm9 — Am7/Bbmaj7 — Gm7/C7, then Em7/Ebmaj7 returning to Dm9.
// Source: Funk Fiction's own keyboard demonstration (18 May 2011). The author's
// comment explains that his C# was an improvised passing tone, not a fixed C7(b9).
// Close voicings retain the common D in Em7/Ebmaj7 without an unrelated octave leap.
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
    val first = step < 7
    return when (bar % 8) {
        2, 6 -> if (first) gypsyAm else gypsyBb
        3 -> if (first) gypsyGm else gypsyC7
        7 -> if (first) gypsyEm else gypsyEb
        else -> if (first) gypsyDm9 else gypsyDm7
    }
}

private fun gypsyChart(): Chart {
    val lead = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until gypsyBars) {
        val start = bar * gypsyBarMs
        val part = gypsyPart(bar)
        val outro = when (part) {
            GypsyPart.Outro -> 1f - (bar - 104) * .11f
            GypsyPart.Coda -> .22f
            else -> 1f
        }
        val breath = when (bar % 4) { 1 -> .96f; 3 -> .93f; else -> 1f }
        val gain = outro * breath
        fun backing(voice: BackingVoice, step: Int, pitch: Int, duration: Long, velocity: Float, lateMs: Long = 0) {
            band += BackingNote(start + step * gypsyStep + lateMs, pitch, duration, gain * velocity, voice)
        }

        val stabs = when {
            bar == gypsyBars - 1 -> listOf(0)
            part == GypsyPart.Breakdown -> listOf(0, 7, 13)
            else -> gypsyStabs
        }
        for (step in stabs) {
            val chord = organChord(bar, step)
            val index = gypsyStabs.indexOf(step)
            val gate = when {
                bar == gypsyBars - 1 -> 1450L
                part == GypsyPart.Bridge || part == GypsyPart.Breakdown -> 292L
                else -> gypsyGates[index]
            }
            val accent = gypsyAccents[index]
            val crest = chord.keys.last()
            lead += Note("gypsy-woman-${bar * 16 + step}", start + step * gypsyStep, crest, gate, gain * .91f * accent)
            for (key in chord.keys.dropLast(1)) {
                backing(BackingVoice.Keys, step, key, gate, .66f * accent)
            }
        }

        val drums = part in setOf(GypsyPart.Groove, GypsyPart.Lift, GypsyPart.Peak, GypsyPart.Reprise) ||
            (part == GypsyPart.Intro && bar >= 4) || (part == GypsyPart.Outro && bar < 108)
        val full = drums && part != GypsyPart.Intro
        if (drums) {
            for (step in 0 until 16 step 4) {
                if (full) backing(BackingVoice.Kick, step, 36, 172, if (step == 0) .91f else .82f)
                if (step == 4 || step == 12) {
                    backing(BackingVoice.Snare, step, if (full) 39 else 37, 124, if (full) .62f else .31f)
                }
                backing(BackingVoice.Hat, step + 2, if (full && step == 12 && bar % 2 == 1) 46 else 42,
                    if (step == 12) 144 else 67, if (step == 4 || step == 12) .37f else .29f, lateMs = 9)
            }
            if (part == GypsyPart.Lift || part == GypsyPart.Peak || part == GypsyPart.Reprise) {
                for (step in listOf(1, 5, 9, 15)) {
                    backing(BackingVoice.Hat, step, 70, 48, if (step == 15) .23f else .14f, lateMs = 14)
                }
            }
            // A quiet pickup closes an eight-bar sentence; no endless drum roll.
            if (full && bar % 8 == 7 && bar != 103) {
                backing(BackingVoice.Snare, 14, 37, 69, .25f)
                backing(BackingVoice.Snare, 15, 37, 58, .32f)
            }
        }

        if (full || part == GypsyPart.Bridge) {
            val bassGain = if (part == GypsyPart.Bridge) .49f else .76f
            for ((index, step) in listOf(0, 6, 8, 14).withIndex()) {
                val chord = organChord(bar, step)
                val octave = if (index == 1 || (index == 3 && bar % 2 == 1)) 12 else 0
                backing(BackingVoice.Bass, step, chord.root + octave,
                    if (index == 0 || index == 2) 295 else 106, bassGain * if (octave == 0) 1f else .68f)
            }
        }

        // Instrumental colours answer chord tones in the open spaces, without an estimated vocal line.
        when (part) {
            GypsyPart.Lift -> for (step in listOf(6, 14)) {
                val chord = organChord(bar, step)
                backing(BackingVoice.Pad, step, chord.keys[chord.keys.lastIndex - 1] + 12, 122, .32f)
            }
            GypsyPart.Peak, GypsyPart.Reprise -> if (bar % 2 == 1) {
                for (step in listOf(6, 15)) {
                    val chord = organChord(bar, step)
                    backing(BackingVoice.Pad, step, chord.keys.last() + 12, 154, .32f)
                }
            }
            GypsyPart.Bridge, GypsyPart.Breakdown -> for (step in listOf(0, 7)) {
                val chord = organChord(bar, step)
                backing(BackingVoice.Pad, step, chord.keys[1], if (step == 0) 790 else 950, .24f)
            }
            else -> Unit
        }
    }
    return Chart("Gypsy Woman", gypsyBpm, gypsyBars * gypsyBarMs, lead, band.sortedBy { it.atMs },
        authoredLandings = true)
}

internal val gypsyWomanSong = Song.arranged(
    id = "gypsy-woman", title = "Gypsy Woman", artist = "Crystal Waters", bpm = gypsyBpm,
    durationMs = gypsyBars * gypsyBarMs, backingAsset = "backing/gypsy-woman.m4a",
    bundledRevision = 5, load = ::gypsyChart,
)
