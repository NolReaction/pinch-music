package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice

/** The bass is voiced independently of these three upper notes, including slash chords. */
private enum class Chord(val bass: Int, val lower: Int, val middle: Int, val upper: Int) {
    Dm(38, 57, 62, 65), Gm(43, 58, 62, 67), C(36, 55, 60, 64), Bb(34, 53, 58, 62),
    F(41, 57, 60, 65), A7(33, 55, 61, 64), Am(33, 57, 60, 64),
    Dm9(38, 60, 64, 65), Gm7(43, 58, 62, 65), C9(36, 58, 62, 64), BbMaj7(34, 57, 62, 65),
    Csm(37, 56, 61, 64), A(33, 57, 61, 64), E(40, 56, 59, 64), B(35, 54, 59, 63),
    Db(37, 56, 61, 65), Eb(39, 58, 63, 67), Fm(41, 56, 60, 65), Ab(32, 56, 60, 63),
    Bbm(34, 53, 58, 61), Cm(36, 55, 60, 63), Bm(35, 54, 59, 62), G(31, 55, 59, 62),
    Em(40, 55, 59, 64), Fs7(42, 58, 61, 64), D7(38, 54, 60, 62),
    Fsm(42, 57, 61, 66), D(38, 57, 62, 66), EoverGis(44, 59, 64, 68), Cs7(37, 59, 61, 65),
}

private enum class Section { Intro, Groove, Lift, Break, Return, End }
private data class Passage(val section: Section, val harmony: List<Chord>)
private fun bars(section: Section, vararg chords: Chord) = Passage(section, chords.toList())

/** One bar of authored attacks in sixteenths. The bass intervals are relative to this bar's root. */
private data class Groove(
    val bass: List<Pair<Int, Int>>,
    val chordHits: List<Int>, val chordGate: Double,
    val kick: List<Int>, val snare: List<Int>, val hat: List<Int>,
    val swing: Double = 0.0,
)

private data class Band(val groove: Groove, val form: List<Passage>)

private fun bandFor(id: String): Band {
    return when (id) {
        // Organ chops, sevenths/ninths and a lightly swung inner pulse.
        "gypsy-woman" -> Band(Groove(listOf(0 to 0, 7 to 7, 10 to 12, 14 to 7), listOf(2, 6, 10, 14), 1.15,
            listOf(0, 4, 8, 12), listOf(4, 12), listOf(2, 6, 9, 10, 14), .19),
            listOf(bars(Section.Intro, Chord.Dm9, Chord.Gm7), bars(Section.Groove, Chord.C9, Chord.Dm9),
                bars(Section.Break, Chord.BbMaj7, Chord.Gm7), bars(Section.Groove, Chord.C9, Chord.A7),
                bars(Section.Lift, Chord.Dm9, Chord.Gm7, Chord.C9, Chord.BbMaj7),
                bars(Section.Return, Chord.Gm7, Chord.C9), bars(Section.End, Chord.A7, Chord.Dm9)))
        // Offbeat bass octaves and short key chords; long counter-harmony at the lift.
        "levels" -> Band(Groove(listOf(2 to 0, 6 to 12, 10 to 0, 14 to 7), listOf(0, 7, 12), 2.8,
            listOf(0, 4, 8, 12), listOf(4, 12), listOf(2, 6, 10, 14)),
            listOf(bars(Section.Intro, Chord.Csm, Chord.A), bars(Section.Groove, Chord.E, Chord.B),
                bars(Section.Break, Chord.A, Chord.E), bars(Section.Groove, Chord.B, Chord.Csm),
                bars(Section.Lift, Chord.A, Chord.E, Chord.B, Chord.Csm),
                bars(Section.Return, Chord.Csm, Chord.A, Chord.E, Chord.B),
                bars(Section.Lift, Chord.A, Chord.B), bars(Section.End, Chord.B, Chord.Csm)))
        // Half-time snare and short offbeat keyboard chords below the quicker upper line.
        "stay" -> Band(Groove(listOf(0 to 0, 6 to 7, 11 to 12), listOf(2, 6, 11, 14), 1.8,
            listOf(0, 6, 11), listOf(8), listOf(0, 2, 4, 6, 8, 10, 12, 14)),
            listOf(bars(Section.Intro, Chord.Fm, Chord.Db), bars(Section.Groove, Chord.Ab, Chord.Eb),
                bars(Section.Groove, Chord.Db, Chord.Ab, Chord.Eb, Chord.Fm),
                bars(Section.Lift, Chord.Db, Chord.Eb, Chord.Ab, Chord.Fm),
                bars(Section.Break, Chord.Fm, Chord.Db, Chord.Bbm, Chord.Eb),
                bars(Section.Return, Chord.Db, Chord.Eb, Chord.Fm, Chord.Ab),
                bars(Section.Groove, Chord.Bbm, Chord.Db, Chord.Eb, Chord.Fm),
                bars(Section.Lift, Chord.Db, Chord.Eb, Chord.Fm, Chord.Ab),
                bars(Section.Return, Chord.Db, Chord.Eb), bars(Section.End, Chord.Fm, Chord.Ab)))
        // Sub-bass drops and a half-time backbeat leave holes for the octave shouts.
        "turn-down-for-what" -> Band(Groove(listOf(0 to 0, 5 to 0, 10 to 12), listOf(3, 11), 1.0,
            listOf(0, 5, 10, 14), listOf(8), listOf(0, 3, 6, 10, 14, 15)),
            listOf(bars(Section.Intro, Chord.Bm, Chord.Bm), bars(Section.Groove, Chord.G, Chord.Em),
                bars(Section.Break, Chord.Bm, Chord.Fs7), bars(Section.Lift, Chord.Bm, Chord.G),
                bars(Section.Return, Chord.Em, Chord.Fs7), bars(Section.End, Chord.G, Chord.Bm)))
        // Clipped two-note replies, a staggered kick and a straight backbeat.
        "yeah" -> Band(Groove(listOf(0 to 0, 3 to 12, 7 to 7, 13 to 0), listOf(2, 5, 11), 1.0,
            listOf(0, 3, 7, 10), listOf(4, 12), listOf(0, 2, 6, 8, 10, 14), .12),
            listOf(bars(Section.Intro, Chord.Gm, Chord.Gm), bars(Section.Groove, Chord.Eb, Chord.D7),
                bars(Section.Groove, Chord.Cm, Chord.Gm), bars(Section.Break, Chord.Eb, Chord.D7),
                bars(Section.Lift, Chord.Gm, Chord.Eb, Chord.Cm, Chord.D7),
                bars(Section.Return, Chord.Gm, Chord.Eb), bars(Section.End, Chord.D7, Chord.Gm)))
        // A held synth/brass harmony and rock kick/snare leave the fanfare audible.
        "the-final-countdown" -> Band(Groove(listOf(0 to 0, 4 to 0, 8 to 7, 12 to 12), listOf(0, 8), 6.5,
            listOf(0, 8, 10), listOf(4, 12), listOf(0, 2, 4, 6, 8, 10, 12, 14)),
            listOf(bars(Section.Intro, Chord.Fsm, Chord.D), bars(Section.Groove, Chord.E, Chord.A),
                bars(Section.Break, Chord.Bm, Chord.D), bars(Section.Groove, Chord.EoverGis, Chord.Cs7),
                bars(Section.Lift, Chord.Fsm, Chord.A, Chord.Bm, Chord.Cs7),
                bars(Section.Return, Chord.Fsm, Chord.D), bars(Section.End, Chord.Cs7, Chord.Fsm)))
        else -> error("Missing band score for $id")
    }
}

internal fun bandArrangement(id: String, bpm: Int, phraseGains: List<Float>): List<BackingNote> {
    val (groove, form) = bandFor(id)
    require(form.sumOf { it.harmony.size } == phraseGains.size * 2)
    val tickMs = 15_000.0 / bpm
    val result = mutableListOf<BackingNote>()
    var bar = 0
    for ((section, chords) in form) {
        for ((within, chord) in chords.withIndex()) {
            val gain = phraseGains[bar / 2]
            fun put(voice: BackingVoice, tick: Int, gate: Double, midi: Int, strength: Float) {
                // Leave the six-beat breath in Stay completely open between the two lead calls.
                if (id == "stay" && (bar * 16 + tick) in 209 until 232) return
                val onset = bar * 16 + tick + (if (tick % 2 == 1) groove.swing else 0.0)
                result += BackingNote((onset * tickMs).roundToLong(), midi, (gate * tickMs).roundToLong(),
                    (gain * strength).coerceIn(0f, 1f), voice)
            }
            fun chordAt(voice: BackingVoice, tick: Int, gate: Double, strength: Float) {
                put(voice, tick, gate, chord.lower, strength)
                put(voice, tick, gate, chord.middle, strength * .87f)
                if (id != "yeah" || section == Section.Lift) put(voice, tick, gate, chord.upper, strength * .78f)
            }
            val cadence = section == Section.End && within == chords.lastIndex
            if (section == Section.Break || cadence) {
                chordAt(BackingVoice.Pad, 0, if (cadence) 11.5 else 5.5, if (cadence) .29f else .20f)
                put(BackingVoice.Bass, 0, if (cadence) 8.0 else 3.0, chord.bass, .56f)
                if (cadence) put(BackingVoice.Kick, 0, 1.1, 36, .56f)
            } else {
                for ((i, pulse) in groove.bass.withIndex()) {
                    val (tick, interval) = pulse
                    val next = groove.bass.getOrNull(i + 1)?.first ?: 16
                    val gate = minOf((next - tick) * .78, if (id == "turn-down-for-what") 6.0 else 3.25)
                    put(BackingVoice.Bass, tick, gate, chord.bass + interval, if (i == 0) .82f else .64f)
                }
                for (tick in groove.chordHits) chordAt(BackingVoice.Keys, tick, groove.chordGate, .29f)
                for (tick in groove.kick) put(BackingVoice.Kick, tick, 1.7, 36, if (tick == 0) .84f else .69f)
                for (tick in groove.snare) put(BackingVoice.Snare, tick, 1.3, 38, .58f)
                for ((i, tick) in groove.hat.withIndex()) {
                    if (section != Section.Intro || i % 2 == 0) {
                        put(BackingVoice.Hat, tick, if (tick == 14) 1.3 else .55, 42, if (i % 2 == 0) .35f else .24f)
                    }
                }
                if (section == Section.Lift || section == Section.Return) {
                    chordAt(BackingVoice.Pad, 0, 14.5, .17f)
                    if (within == chords.lastIndex && 15 !in groove.snare) {
                        put(BackingVoice.Snare, 15, .65, 38, .37f)
                    }
                }
            }
            bar++
        }
    }
    return result.sortedBy { it.atMs }
}
