package music.pinch

import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val turnDownBpm = 100
private const val turnDownBars = 64
private const val turnDownStepMs = 150L
private const val turnDownBarMs = 16 * turnDownStepMs

internal enum class TurnDownPart { Intro, Call, Drop, Break, Build, Outro }
internal fun turnDownPartAt(atMs: Long): TurnDownPart = when ((atMs / turnDownBarMs).toInt()) {
    in 0..3 -> TurnDownPart.Intro
    in 4..7 -> TurnDownPart.Call
    in 8..23, in 40..55 -> TurnDownPart.Drop
    in 24..31 -> TurnDownPart.Break
    in 32..39 -> TurnDownPart.Build
    else -> TurnDownPart.Outro
}

private data class TurnTone(val step: Int, val pitch: Int, val gate: Long, val touch: Float = 1f)
private fun turnTone(step: Int, pitch: Int, gate: Long, touch: Float = 1f) = TurnTone(step, pitch, gate, touch)

// The public GEERDES demo (38835.00, instrumental channels 5/8) establishes
// the E–F semitone, the D ending, octave answers and the 0/3/6/8/10/12/14 rhythm.
// Its delayed duplicate is an echo, never a second playable line. The E-minor
// publisher label does not turn the hook's written F naturals into F sharps.
// https://geerdes.media/apps/midifile/en/turn-down-for-what-dj-snake-lil-jon-38835.00.html
// This 64-bar form, breakdown and development are our shorter instrumental edit.
private fun turnHook(root: Int, answer: Boolean = false): List<TurnTone> = listOf(
    turnTone(0, root, 230), turnTone(3, root, 115, .87f), turnTone(6, root + 1, 282, .96f),
    turnTone(8, root, 115, .92f), turnTone(10, root, 115, .87f),
    turnTone(12, root + if (answer) 0 else 1, if (answer) 115 else 282, .94f),
    turnTone(14, root + if (answer) 1 else -2, 282, .97f),
)

private fun turnStutter(root: Int): List<TurnTone> = listOf(
    turnTone(0, root, 68), turnTone(1, root, 64, .69f), turnTone(2, root, 64, .79f),
    turnTone(3, root, 115, .89f), turnTone(6, root + 1, 282, .96f),
    turnTone(8, root, 84, .91f), turnTone(10, root, 78, .81f), turnTone(11, root, 64, .73f),
    turnTone(12, root + 1, 282, .95f), turnTone(14, root - 2, 282),
)

// The melodicized chant retains the demo's four pickup attacks on G, without words.
private val turnPickup = listOf(turnTone(10, 67, 264, .90f), turnTone(12, 67, 264),
    turnTone(14, 67, 128, .88f), turnTone(15, 67, 132, .98f))
private val turnRapidCall = listOf(0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12).map { step ->
    turnTone(step, 67, if (step == 4 || step == 12) 208 else 72,
        if (step == 0 || step == 4 || step == 8 || step == 12) 1f else .79f)
}

private fun turnDrop(local: Int, reprise: Boolean): List<TurnTone> = when (local) {
    0, 8 -> turnHook(64)
    1, 3, 9, 11 -> turnHook(52, answer = true)
    2, 10 -> turnHook(52)
    4, 12 -> turnStutter(52)
    5 -> turnHook(if (reprise) 52 else 64, answer = true).drop(2)
    6 -> turnHook(64)
    7 -> turnHook(64, answer = true)
    13 -> if (reprise) turnRapidCall else turnRapidCall.filter { it.step in listOf(0, 3, 4, 8, 10, 12) }
    14 -> listOf(0, 2, 4, 6, 8, 10, 12, 14).map { turnTone(it, 67, 144, if (it % 4 == 0) 1f else .81f) }
    else -> turnPickup
}

private fun turnMelody(bar: Int): List<TurnTone> = when (turnDownPartAt(bar * turnDownBarMs)) {
    TurnDownPart.Intro -> when (bar) {
        0 -> turnHook(64).filter { it.step in listOf(0, 6, 10, 14) }
        1 -> turnHook(52, answer = true).filter { it.step in listOf(0, 6, 14) }
        2 -> turnHook(64)
        else -> turnPickup
    }
    TurnDownPart.Call -> when (bar - 4) {
        0 -> turnRapidCall
        1 -> turnHook(52, answer = true).filter { it.step in listOf(0, 6, 10, 14) }
        2 -> listOf(0, 2, 4, 6, 8, 10, 12, 14).map { turnTone(it, 67, 144, if (it % 4 == 0) 1f else .82f) }
        else -> turnPickup
    }
    TurnDownPart.Drop -> turnDrop(if (bar < 24) bar - 8 else bar - 40, reprise = bar >= 40)
    TurnDownPart.Break -> when (bar - 24) {
        0 -> listOf(turnTone(0, 64, 890), turnTone(10, 65, 240, .85f), turnTone(14, 64, 230, .90f))
        1 -> listOf(turnTone(0, 52, 590), turnTone(8, 53, 590, .88f))
        2 -> listOf(turnTone(2, 62, 282, .84f), turnTone(6, 64, 432), turnTone(12, 65, 432, .89f))
        3 -> turnPickup
        4 -> listOf(turnTone(0, 52, 890), turnTone(10, 53, 432, .88f))
        5 -> listOf(turnTone(0, 52, 890), turnTone(10, 50, 590, .87f))
        6 -> listOf(0, 4, 8).map { turnTone(it, 67, 360, if (it == 0) 1f else .84f) }
        else -> turnPickup
    }
    TurnDownPart.Build -> when (bar - 32) {
        0 -> turnRapidCall.filter { it.step in listOf(0, 3, 4, 8, 10, 12) }
        1 -> turnHook(64).filter { it.step in listOf(0, 6, 12, 14) }
        2 -> turnRapidCall
        3 -> turnPickup
        4 -> turnHook(64)
        5 -> turnHook(52, answer = true)
        6 -> turnStutter(52)
        else -> turnPickup
    }
    TurnDownPart.Outro -> when (bar - 56) {
        0 -> turnHook(64)
        1 -> turnHook(52, answer = true)
        2 -> turnHook(52)
        3 -> turnPickup
        4 -> turnHook(64).filter { it.step in listOf(0, 6, 14) }
        5 -> listOf(turnTone(0, 52, 590), turnTone(8, 53, 590, .86f))
        6 -> listOf(turnTone(0, 64, 890), turnTone(10, 62, 590, .87f))
        else -> listOf(turnTone(0, 52, 1500))
    }
}

private fun turnDownChart(): Chart {
    val melody = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until turnDownBars) {
        val start = bar * turnDownBarMs
        val part = turnDownPartAt(start)
        val fade = if (part == TurnDownPart.Outro) 1f - (bar - 56) * .105f else 1f
        val intensity = when (part) {
            TurnDownPart.Intro -> .64f + bar * .05f
            TurnDownPart.Call -> .80f
            TurnDownPart.Drop -> .94f
            TurnDownPart.Break -> .65f
            TurnDownPart.Build -> .73f + (bar - 32) * .025f
            TurnDownPart.Outro -> .86f
        } * fade
        for (tone in turnMelody(bar)) {
            melody += Note("turn-down-${bar * 16 + tone.step}", start + tone.step * turnDownStepMs,
                tone.pitch, tone.gate, intensity * tone.touch)
        }
        fun backing(voice: BackingVoice, step: Int, pitch: Int, gate: Long, gain: Float, lateMs: Long = 0) {
            band += BackingNote(start + step * turnDownStepMs + lateMs, pitch, gate,
                (gain * fade).coerceIn(0f, 1f), voice)
        }

        val heavy = part == TurnDownPart.Drop || (part == TurnDownPart.Outro && bar < 60)
        val drums = part != TurnDownPart.Break && (part != TurnDownPart.Intro || bar >= 2) && bar < 62
        if (drums) {
            val kickSteps = when {
                heavy && bar % 4 == 0 -> listOf(0, 3, 5, 6, 13, 15)
                heavy -> listOf(0, 6)
                part == TurnDownPart.Build && bar >= 36 -> listOf(0, 6, 10)
                else -> listOf(0)
            }
            for (step in kickSteps) backing(BackingVoice.Kick, step, 36, 180,
                if (heavy) (if (step == 0 || step == 6) .94f else .73f) else .58f)
            for (step in listOf(4, 12)) backing(BackingVoice.Snare, step, if (heavy) 38 else 37,
                if (heavy) 150 else 110, if (heavy) .82f else .53f)
            val hats = if (heavy || part == TurnDownPart.Build) listOf(0, 2, 4, 6, 8, 10, 12, 14)
                else listOf(2, 6, 10, 14)
            for (step in hats) backing(BackingVoice.Hat, step,
                if (heavy && step == 14 && bar % 4 == 3) 46 else 42,
                if (step == 14) 120 else 60, if (step % 4 == 2) .38f else .24f, lateMs = 7)
            if (heavy && bar % 4 == 2) {
                // The fine hat roll is accompaniment, not impossible extra marble landings.
                for (offset in listOf(0L, 75L, 150L, 225L)) {
                    backing(BackingVoice.Hat, 10, 70, 45, if (offset % 150 == 0L) .27f else .19f, offset)
                }
            }
            if (part == TurnDownPart.Build && bar >= 36) {
                val steps = if (bar == 39) listOf(0, 2, 4, 6, 8) else listOf(6, 14)
                for ((index, step) in steps.withIndex()) {
                    backing(BackingVoice.Snare, step, 38, 95, .27f + .035f * index)
                }
            }
        } else if (part == TurnDownPart.Break && bar >= 28) {
            backing(BackingVoice.Snare, 12, 37, 90, .28f)
            for (step in listOf(2, 10)) backing(BackingVoice.Hat, step, 70, 70, .24f)
        }

        if (bar < 62 && (drums || part == TurnDownPart.Break && bar >= 28)) {
            // E1 in the reference is raised to E2 for a clear, controlled phone-speaker bass.
            val gain = if (heavy) .79f else if (part == TurnDownPart.Break) .36f else .53f
            backing(BackingVoice.Bass, 0, 40, if (heavy) 820 else 1140, gain)
            if (heavy || part == TurnDownPart.Build) backing(BackingVoice.Bass, 6, 40, 1320, gain * .93f)
        }

        if (part == TurnDownPart.Intro || part == TurnDownPart.Break || part == TurnDownPart.Outro && bar >= 60) {
            val gate = if (bar == 63) 1450L else 1840L
            for (pitch in listOf(52, 59)) backing(BackingVoice.Pad, 0, pitch, gate, .31f)
            if (part == TurnDownPart.Intro && bar % 2 == 0) backing(BackingVoice.Pad, 0, 55, gate, .20f)
        }
        if (part == TurnDownPart.Drop && bar % 4 == 1) {
            backing(BackingVoice.Keys, 5, 76, 115, .22f)
            backing(BackingVoice.Keys, 11, 77, 115, .19f)
        }
    }
    return Chart("Turn Down for What", turnDownBpm, turnDownBars * turnDownBarMs,
        melody.sortedBy { it.atMs }, band.sortedBy { it.atMs }, authoredLandings = true)
}

internal val turnDownForWhatSong = Song.arranged(
    id = "turn-down-for-what", title = "Turn Down for What", artist = "DJ Snake & Lil Jon",
    bpm = turnDownBpm, durationMs = turnDownBars * turnDownBarMs,
    backingAsset = "backing/turn-down-for-what.m4a", bundledRevision = 4, load = ::turnDownChart,
)
