package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

/** The playable keys and studio master share these positions on a sixteenth-note grid. */
private const val floorBpm = 130
private const val floorBars = 124
private const val pickupTicks = 4
private fun floorTime(tick: Double) = (tick * 15_000.0 / floorBpm).roundToLong()

internal enum class FloorLeadVoice { Reed, Pluck, Vocal, Brass }

private enum class FloorPart(val voice: FloorLeadVoice, val drums: Float, val bass: Float, val melody: Float) {
    Intro(FloorLeadVoice.Reed, .48f, .66f, .87f),
    Call(FloorLeadVoice.Pluck, .74f, .78f, .91f),
    Verse(FloorLeadVoice.Vocal, .76f, .84f, .84f),
    Rise(FloorLeadVoice.Vocal, .81f, .83f, .88f),
    Chorus(FloorLeadVoice.Vocal, .94f, .96f, .98f),
    Turn(FloorLeadVoice.Vocal, .78f, .81f, .84f),
    Hook(FloorLeadVoice.Reed, 1f, .99f, .97f),
    VerseReturn(FloorLeadVoice.Vocal, .82f, .88f, .88f),
    Break(FloorLeadVoice.Pluck, .35f, .52f, .78f),
    Reentry(FloorLeadVoice.Brass, .86f, .88f, .91f),
    Final(FloorLeadVoice.Reed, 1f, .99f, 1f),
    Outro(FloorLeadVoice.Reed, .72f, .82f, .88f),
}

private data class FloorPassage(val bar: Int, val part: FloorPart)

private val floorForm = listOf(
    FloorPassage(0, FloorPart.Intro),
    FloorPassage(8, FloorPart.Call),
    FloorPassage(24, FloorPart.Verse),
    FloorPassage(40, FloorPart.Rise),
    FloorPassage(44, FloorPart.Chorus),
    FloorPassage(52, FloorPart.Turn),
    FloorPassage(54, FloorPart.Hook),
    FloorPassage(62, FloorPart.VerseReturn),
    FloorPassage(78, FloorPart.Rise),
    FloorPassage(82, FloorPart.Chorus),
    FloorPassage(90, FloorPart.Turn),
    FloorPassage(92, FloorPart.Hook),
    FloorPassage(100, FloorPart.Break),
    FloorPassage(108, FloorPart.Reentry),
    FloorPassage(110, FloorPart.Final),
    FloorPassage(122, FloorPart.Outro),
)

private fun FloorPassage.startsRefrainAt(bar: Int): Boolean = (bar - this.bar) % 4 == 0 && when (part) {
    FloorPart.Intro, FloorPart.Chorus, FloorPart.Hook, FloorPart.Final -> true
    else -> false
}

internal fun floorLeadVoiceAt(atMs: Long): FloorLeadVoice {
    val tick = (atMs * floorBpm / 15_000.0).roundToLong() - pickupTicks
    if (tick < 0) return FloorLeadVoice.Reed
    val bar = (tick / 16).toInt()
    val passage = floorForm.last { it.bar <= bar }
    if (tick % 16 == 12L && bar + 1 < floorBars) {
        val next = floorForm.last { it.bar <= bar + 1 }
        if (next.startsRefrainAt(bar + 1)) return next.part.voice
    }
    val drop = passage.part == FloorPart.Hook || passage.part == FloorPart.Final
    return if (drop && (bar - passage.bar) % 4 == 3) FloorLeadVoice.Vocal else passage.part.voice
}

private data class FloorBar(val tones: List<Pair<Int, Int>>, val gate: Double)
private fun line(vararg notes: Pair<Int, Int>, gate: Double = .82) = FloorBar(notes.toList(), gate)
// Load the written phrases when the player opens the song or the studio renders the master.
private object FloorPhrases {
    private val rest = line()

    // The octave pickup falls before the first downbeat. The straight eighth-note answer
    // settles on B flat for three beats, giving the dancer time to cross the long rest.
    // Each pair is a sixteenth-note offset inside a bar and a sounding MIDI pitch.
    private val refrain = listOf(
        line(0 to 70, 6 to 68, 8 to 66, 10 to 65, 12 to 63, gate = .95),
        line(0 to 63, 2 to 66, 4 to 65, 6 to 63, 8 to 61, 10 to 63, 12 to 58, 14 to 56, gate = .87),
        line(0 to 58, 3 to 56, 4 to 58, gate = .97),
        rest,
    )

    // The verse speaks rhythmically around E flat; the second half leans on G flat.
    // Leave the break between calls exposed instead of playing through every bar.
    private val verse = listOf(
        line(0 to 63, 3 to 63, 6 to 63, 8 to 63, 12 to 63, gate = .66),
        line(0 to 63, 3 to 63, 6 to 63, 8 to 61, 11 to 63, gate = .76),
        line(3 to 63, 4 to 63, 6 to 63, 9 to 63, 14 to 63, gate = .68),
        line(0 to 63, 3 to 63, 5 to 63, 9 to 61, 12 to 58, gate = .72),
        line(0 to 63, 4 to 63, 8 to 63, 12 to 63, gate = .69),
        line(1 to 61, 3 to 63, 8 to 63, 11 to 63, 14 to 63, gate = .71),
        line(0 to 63, 3 to 68, 6 to 70, 8 to 63, 10 to 63, 14 to 63, gate = .74),
        line(3 to 68, 6 to 70, 8 to 63, 10 to 63, 14 to 61, gate = .66),
    )

    private val verseAnswer = listOf(
        line(0 to 63, 4 to 66, 6 to 66, 8 to 63, 11 to 66, 14 to 63, gate = .74),
        line(0 to 66, 3 to 66, 6 to 66, 9 to 66, 12 to 68, 14 to 70, gate = .76),
        line(1 to 63, 4 to 66, 6 to 63, 9 to 66, 12 to 66, gate = .72),
        line(0 to 63, 4 to 63, 7 to 63, 9 to 61, 13 to 58, gate = .66),
    )

    private val calls = listOf(
        line(1 to 63, 4 to 63, 6 to 58, 9 to 63, 12 to 66, gate = .54),
        line(0 to 63, 6 to 59, 9 to 63, 14 to 59, gate = .61),
        line(1 to 66, 4 to 61, 8 to 66, 10 to 68, 13 to 66, gate = .57),
        line(0 to 65, 4 to 61, 8 to 58, gate = .65),
    )

    private val rise = listOf(
        line(0 to 63, 3 to 63, 5 to 66, 8 to 63, 13 to 66, gate = .69),
        line(0 to 63, 3 to 63, 6 to 66, 8 to 63, 12 to 58, gate = .72),
        line(0 to 63, 4 to 63, 6 to 63, 8 to 66, 12 to 68, gate = .73),
        line(0 to 63, 3 to 63, 6 to 68, 8 to 70, gate = .83),
    )

    private val dropAnswer = line(0 to 58, 2 to 58, 4 to 61, 6 to 61, 8 to 65, gate = .70)

    private val breakdown = listOf(
        line(0 to 63, 8 to 58, gate = .94), line(4 to 59, 12 to 63),
        line(0 to 66, 10 to 61), rest,
        line(0 to 63, 8 to 66), line(4 to 68, 10 to 66, 14 to 63),
        line(0 to 66, 8 to 70, 12 to 68), line(0 to 65, 6 to 61, gate = .78),
    )
    private val coda = line(0 to 63, 5 to 58, 8 to 63, gate = .98)

    fun phrase(part: FloorPart, local: Int): FloorBar = when (part) {
        FloorPart.Intro, FloorPart.Chorus -> refrain[local % 4]
        FloorPart.Hook, FloorPart.Final -> if (local % 4 == 3) dropAnswer else refrain[local % 4]
        FloorPart.Outro -> if (local == 0) refrain[2] else coda
        FloorPart.Call -> if (local == 7) rise[3] else calls[local % 4]
        FloorPart.Verse -> if (local < 8) verse[local] else verseAnswer[local % 4]
        FloorPart.VerseReturn -> if (local < 8) verse[(local + 4) % 8] else verseAnswer[local % 4]
        FloorPart.Rise -> rise[local]
        FloorPart.Turn -> if (local == 0) line(0 to 58, 4 to 61, 8 to 63, gate = .89) else rest
        FloorPart.Break -> breakdown[local]
        FloorPart.Reentry -> calls[local]
    }
}

private data class Voicing(val bass: Int, val inner: IntArray)
private val tonic = Voicing(39, intArrayOf(54, 58, 63))
private val progression = listOf(tonic, Voicing(35, intArrayOf(54, 58, 59, 63)),
    Voicing(42, intArrayOf(54, 58, 61)), Voicing(34, intArrayOf(53, 56, 58, 61)))

private fun floorChart(): Chart {
    val lead = mutableListOf(Note("floor-0", 0, 58, floorTime(pickupTicks * .92), .78f))
    val band = mutableListOf<BackingNote>()
    for (bar in 0 until floorBars) {
        val passage = floorForm.last { it.bar <= bar }
        val local = bar - passage.bar
        val part = passage.part
        val at = pickupTicks + bar * 16
        val melody = FloorPhrases.phrase(part, local)
        val nextPassage = floorForm.last { it.bar <= bar + 1 }
        val pickup = bar + 1 < floorBars && nextPassage.startsRefrainAt(bar + 1)
        val tones = if (pickup) melody.tones.takeWhile { it.first < 12 } + (12 to 58) else melody.tones
        for ((i, entry) in tones.withIndex()) {
            val (offset, midi) = entry
            val next = tones.getOrNull(i + 1)?.first ?: 16
            val onset = floorTime((at + offset).toDouble())
            val length = floorTime((next - offset) * melody.gate)
            val accent = when {
                i == 0 || next - offset >= 5 -> 1f
                offset % 4 == 0 -> .93f
                else -> .84f
            }
            lead += Note("floor-${at + offset}", onset, midi, length, part.melody * accent)
        }

        val last = bar == floorBars - 1
        val chord = when (part) {
            FloorPart.Call, FloorPart.Verse, FloorPart.VerseReturn -> tonic
            else -> if (last) tonic else progression[local % 4]
        }
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
        val leaveSpace = (build && nextPassage != passage) || (part == FloorPart.Break && local == 3)
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
        if (build && floorForm.any { it.bar == bar + 2 }) {
            for (tick in listOf(9.0, 10.5, 13.0, 14.0, 15.0)) {
                add(BackingVoice.Snare, 37, tick, .46, (.23 + .014 * (tick - 8)).toFloat())
            }
        }
    }
    return Chart("On the Floor", floorBpm, floorTime(floorBars * 16.0 + pickupTicks) + 800,
        lead, band.sortedBy { it.atMs }, authoredLandings = true)
}

internal val onTheFloorSong = Song.arranged(
    id = "on-the-floor", title = "On the Floor", artist = "Jennifer Lopez ft. Pitbull",
    bpm = floorBpm, durationMs = floorTime(floorBars * 16.0 + pickupTicks) + 800,
    backingAsset = "backing/on-the-floor.m4a", bundledRevision = 5, load = ::floorChart,
)
