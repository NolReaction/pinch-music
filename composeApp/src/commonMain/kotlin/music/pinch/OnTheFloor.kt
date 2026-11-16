package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

/** The playable keys and studio master share these positions on a sixteenth-note grid. */
private const val floorBpm = 130
private const val floorBars = 124
private fun floorTime(tick: Double) = (tick * 15_000.0 / floorBpm).roundToLong()

internal enum class FloorLeadVoice { Reed, Pluck, Vocal, Brass }

private enum class FloorPart(val voice: FloorLeadVoice, val drums: Float, val bass: Float, val melody: Float) {
    Intro(FloorLeadVoice.Reed, .48f, .66f, .87f),
    Call(FloorLeadVoice.Pluck, .74f, .78f, .91f),
    Verse(FloorLeadVoice.Vocal, .76f, .84f, .84f),
    Rise(FloorLeadVoice.Vocal, .81f, .83f, .88f),
    Chorus(FloorLeadVoice.Vocal, .94f, .96f, .98f),
    Hook(FloorLeadVoice.Reed, 1f, .99f, .97f),
    VerseReturn(FloorLeadVoice.Vocal, .82f, .88f, .88f),
    Break(FloorLeadVoice.Pluck, .35f, .52f, .78f),
    Reentry(FloorLeadVoice.Brass, .86f, .88f, .91f),
    Final(FloorLeadVoice.Reed, 1f, .99f, 1f),
    Outro(FloorLeadVoice.Reed, .72f, .82f, .88f),
}

private data class FloorPassage(val bar: Int, val part: FloorPart) {
    val atMs = floorTime(bar * 16.0)
}

private val floorForm = listOf(
    FloorPassage(0, FloorPart.Intro),
    FloorPassage(8, FloorPart.Call),
    FloorPassage(16, FloorPart.Verse),
    FloorPassage(32, FloorPart.Rise),
    FloorPassage(40, FloorPart.Chorus),
    FloorPassage(48, FloorPart.Hook),
    FloorPassage(56, FloorPart.VerseReturn),
    FloorPassage(72, FloorPart.Rise),
    FloorPassage(80, FloorPart.Chorus),
    FloorPassage(88, FloorPart.Hook),
    FloorPassage(96, FloorPart.Break),
    FloorPassage(104, FloorPart.Reentry),
    FloorPassage(112, FloorPart.Final),
    FloorPassage(120, FloorPart.Outro),
)

internal fun floorLeadVoiceAt(atMs: Long): FloorLeadVoice =
    floorForm.lastOrNull { it.atMs <= atMs }?.part?.voice ?: FloorLeadVoice.Reed

private data class FloorBar(val tones: List<Pair<Int, Int>>, val gate: Double)
private fun line(vararg notes: Pair<Int, Int>, gate: Double = .82) = FloorBar(notes.toList(), gate)
// Load the written phrases when the player opens the song or the studio renders the master.
private object FloorPhrases {
    private val rest = line()

    // The reed opens on the low fifth, jumps an octave, then descends in two syncopated answers.
    // Numbers are (sixteenth within the bar, MIDI pitch), in concert E-flat minor.
    private val refrain = listOf(
        line(0 to 58, 2 to 70, 7 to 68, 9 to 66, 11 to 65, 13 to 63, gate = .89),
        line(1 to 63, 4 to 66, 6 to 65, 8 to 63, 11 to 61, 13 to 63, gate = .77),
        line(0 to 61, 3 to 58, 5 to 56, 7 to 58, 13 to 61, gate = .91),
        line(0 to 63, 4 to 61, 6 to 58, 10 to 56, 13 to 58, gate = .70),
        line(0 to 58, 2 to 70, 7 to 68, 9 to 66, 11 to 65, 13 to 63, gate = .92),
        line(1 to 63, 4 to 66, 6 to 65, 8 to 63, 11 to 61, 13 to 63, gate = .83),
        line(0 to 61, 3 to 58, 5 to 56, 7 to 58, 12 to 66, 14 to 65),
        line(0 to 63, 6 to 61, 9 to 58, gate = .90),
    )

    private val verse = listOf(
        line(1 to 58, 3 to 58, 6 to 61, 8 to 63, 11 to 61, 14 to 58, gate = .69),
        line(0 to 59, 3 to 59, 6 to 63, 9 to 66, 14 to 63),
        line(1 to 61, 3 to 61, 6 to 63, 8 to 66, 11 to 65, 14 to 61, gate = .71),
        line(0 to 58, 4 to 61, 7 to 65, 12 to 61),
        line(1 to 58, 4 to 61, 6 to 63, 10 to 63, 13 to 61),
        line(0 to 59, 2 to 59, 5 to 63, 8 to 66, 12 to 68, gate = .72),
        line(1 to 66, 4 to 66, 7 to 65, 10 to 63, 13 to 61),
        line(0 to 61, 3 to 58, 8 to 56, 12 to 58, gate = .90),
    )

    private val verseAnswer = listOf(
        line(0 to 63, 3 to 63, 6 to 66, 8 to 68, 12 to 66, 14 to 63, gate = .71),
        line(0 to 63, 5 to 66, 8 to 68, 11 to 66, 14 to 63),
        line(0 to 66, 4 to 68, 6 to 70, 10 to 68, 13 to 66),
        line(0 to 65, 3 to 65, 8 to 61, 13 to 58, gate = .88),
    )

    private val calls = listOf(
        line(1 to 63, 4 to 63, 6 to 58, 9 to 63, 12 to 66, gate = .54),
        line(0 to 63, 6 to 59, 9 to 63, 14 to 59, gate = .61),
        line(1 to 66, 4 to 61, 8 to 66, 10 to 68, 13 to 66, gate = .57),
        line(0 to 65, 4 to 61, 8 to 58, gate = .65),
    )

    private val rise = listOf(
        line(0 to 63, 5 to 65, 8 to 66, 13 to 68, gate = .94),
        line(0 to 66, 6 to 68, 10 to 70),
        line(0 to 66, 4 to 68, 6 to 70, 9 to 73, 13 to 70),
        line(0 to 68, 6 to 65, 10 to 65, 14 to 68),
        line(0 to 66, 3 to 68, 6 to 70, 10 to 70, 13 to 73),
        line(0 to 71, 4 to 70, 6 to 68, 10 to 66, 14 to 68),
        line(0 to 70, 4 to 73, 8 to 70, 12 to 68),
        line(0 to 65, 7 to 68, 10 to 70, gate = .68),
    )

    private val chorus = listOf(
        line(0 to 70, 5 to 68, 7 to 66, 10 to 63, 14 to 66, gate = .92),
        line(0 to 68, 4 to 66, 8 to 63, 11 to 59),
        line(0 to 70, 4 to 68, 7 to 66, 10 to 61, 14 to 66, gate = .92),
        line(0 to 65, 5 to 61, 8 to 58, 13 to 61),
        line(0 to 70, 4 to 73, 8 to 70, 10 to 68, 13 to 66),
        line(0 to 71, 5 to 70, 7 to 68, 10 to 66, 14 to 63, gate = .91),
        line(0 to 66, 3 to 68, 6 to 70, 8 to 66, 11 to 65, 14 to 63),
        line(0 to 65, 6 to 61, 10 to 58, gate = .77),
    )

    private val breakdown = listOf(
        line(0 to 63, 8 to 58, gate = .94), line(4 to 59, 12 to 63),
        line(0 to 66, 10 to 61), rest,
        line(0 to 63, 8 to 66), line(4 to 68, 10 to 66, 14 to 63),
        line(0 to 66, 8 to 70, 12 to 68), line(0 to 65, 6 to 61, gate = .78),
    )
    private val coda = listOf(refrain[0], refrain[1],
        line(0 to 61, 3 to 58, 6 to 56, 8 to 58, 13 to 61),
        line(0 to 63, 5 to 58, 8 to 63, gate = 1.0))

    fun phrase(part: FloorPart, local: Int): FloorBar = when (part) {
        FloorPart.Intro -> if (local < 4) refrain[local] else calls[local - 4]
        FloorPart.Call -> if (local == 7) rise[3] else calls[local % 4]
        FloorPart.Verse -> if (local < 12) verse[local % 8] else verseAnswer[local % 4]
        FloorPart.VerseReturn -> if (local % 8 < 4) verseAnswer[local % 4] else verse[local % 8]
        FloorPart.Rise -> rise[local]
        FloorPart.Chorus -> chorus[local]
        FloorPart.Hook -> refrain[local]
        FloorPart.Break -> breakdown[local]
        FloorPart.Reentry -> if (local < 4) calls[local] else rise[local]
        FloorPart.Final -> if (local < 6) refrain[local] else chorus[local]
        FloorPart.Outro -> coda[local]
    }
}

private data class Voicing(val bass: Int, val inner: IntArray)
private val tonic = Voicing(39, intArrayOf(54, 58, 63))
private val progression = listOf(tonic, Voicing(35, intArrayOf(54, 59, 63)),
    Voicing(42, intArrayOf(54, 58, 61)), Voicing(34, intArrayOf(53, 58, 61)))

private fun floorChart(): Chart {
    val lead = mutableListOf<Note>()
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until floorBars) {
        val passage = floorForm.last { it.bar <= bar }
        val local = bar - passage.bar
        val part = passage.part
        val at = bar * 16
        val melody = FloorPhrases.phrase(part, local)
        for ((i, entry) in melody.tones.withIndex()) {
            val (offset, midi) = entry
            val next = melody.tones.getOrNull(i + 1)?.first ?: 16
            val onset = floorTime((at + offset).toDouble())
            val length = floorTime((next - offset).coerceAtMost(9) * melody.gate)
            val accent = when {
                i == 0 || next - offset >= 5 -> 1f
                offset % 4 == 0 -> .93f
                else -> .84f
            }
            lead += Note("floor-${at + offset}", onset, midi, length, part.melody * accent)
        }

        val last = bar == floorBars - 1
        val chord = if (last || (part == FloorPart.Call && local < 4)) tonic else progression[local % 4]
        fun add(v: BackingVoice, midi: Int, tick: Double, gate: Double, gain: Float) {
            band += BackingNote(floorTime(at + tick), midi, floorTime(gate), gain, v)
        }
        val thin = part == FloorPart.Break || (part == FloorPart.Intro && local < 2)
        val drop = part == FloorPart.Hook || part == FloorPart.Final || part == FloorPart.Chorus
        val turn = local % 4 == 3
        val build = part == FloorPart.Rise || part == FloorPart.Reentry
        for ((voice, pitch) in chord.inner.withIndex()) {
            val pad = if (thin) .37f else if (drop) .29f else .23f
            add(BackingVoice.Pad, pitch, .055 * voice, if (last) 15.3 else 14.75, pad)
        }
        if (last) {
            add(BackingVoice.Bass, chord.bass, 0.0, 5.7, .70f)
            add(BackingVoice.Bass, chord.bass, 8.0, 7.7, .76f)
            add(BackingVoice.Kick, 36, 0.0, 1.9, .73f)
            add(BackingVoice.Kick, 36, 8.0, 2.0, .58f)
            continue
        }

        val level = part.drums * when {
            part == FloorPart.Outro -> 1f - local * .14f
            build -> .74f + .035f * local
            else -> 1f
        }
        val leaveSpace = (build && local == 7) || (part == FloorPart.Break && local == 3)
        for (beat in 0..3) {
            if (leaveSpace && beat >= 2) break
            val tick = beat * 4.0
            if (!thin || beat == 0 || (beat == 2 && local % 2 == 0)) {
                add(BackingVoice.Kick, 36, tick, 1.72, level * if (beat == 0) .96f else .80f)
            }
            if (beat % 2 == 1 && (!thin || beat == 3)) {
                add(BackingVoice.Snare, if (thin) 37 else 39, tick + .045, .96, level * if (beat == 3) .78f else .68f)
            }
            if (!thin || beat % 2 == 1) {
                val open = drop && beat == 3
                add(BackingVoice.Hat, if (open) 46 else 42, tick + 2.03, if (open) 1.48 else .56,
                    level * if (beat % 2 == 0) .31f else .43f)
            }
            if ((drop || build) && (beat == 1 || beat == 3)) {
                add(BackingVoice.Hat, 70, tick + 3.08, .48, .24f * level)
            }
        }
        if (thin) {
            add(BackingVoice.Bass, chord.bass, 0.0, 5.8, .66f * part.bass)
            if (!leaveSpace) add(BackingVoice.Bass, chord.bass + 7, 10.0, 2.5, .51f * part.bass)
        } else {
            // Anticipations and short octave/fifth answers give the bass a Latin syncopation.
            add(BackingVoice.Bass, chord.bass, 0.0, 2.55, .81f * part.bass)
            add(BackingVoice.Bass, chord.bass, 3.0, 1.35, .60f * part.bass)
            add(BackingVoice.Bass, chord.bass + if (drop) 12 else 7, 6.0, 1.52, .65f * part.bass)
            if (!leaveSpace) {
                add(BackingVoice.Bass, chord.bass, 8.0, 2.55, .77f * part.bass)
                add(BackingVoice.Bass, chord.bass + 7, 11.0, 1.26, .56f * part.bass)
                add(BackingVoice.Bass, chord.bass + if (turn) 12 else 0, 14.0, 1.26, .67f * part.bass)
            }
        }
        if (!thin) {
            for (hit in listOf(2.0, 6.0, 10.0, 14.0)) {
                if ((leaveSpace && hit > 6) || (!drop && hit == 6.0) || (turn && hit == 14.0)) continue
                add(BackingVoice.Keys, chord.inner[0], hit + .03, 1.05, .38f)
                add(BackingVoice.Keys, chord.inner[if (hit == 10.0) 1 else 2], hit + .095, 1.26, .30f)
            }
        }
        if (build && local % 8 == 6) {
            for (tick in listOf(9.0, 10.5, 13.0, 14.0, 15.0)) {
                add(BackingVoice.Snare, 37, tick, .46, (.23 + .014 * (tick - 8)).toFloat())
            }
        }
    }
    return Chart("On the Floor", floorBpm, floorTime(floorBars * 16.0) + 800,
        lead, band.sortedBy { it.atMs }, authoredLandings = true)
}

internal val onTheFloorSong = Song.arranged(
    id = "on-the-floor", title = "On the Floor", artist = "Jennifer Lopez ft. Pitbull",
    bpm = floorBpm, durationMs = floorTime(floorBars * 16.0) + 800,
    backingAsset = "backing/on-the-floor.m4a", bundledRevision = 4, load = ::floorChart,
)
