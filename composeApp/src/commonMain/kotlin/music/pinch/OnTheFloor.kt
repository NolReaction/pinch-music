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
    Reentry(FloorLeadVoice.Pluck, .86f, .88f, .91f),
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
    val reprise = passage.part == FloorPart.Hook || passage.part == FloorPart.Final
    val phraseBar = (bar - passage.bar) % 4
    val answering = phraseBar == 3 || (phraseBar == 2 && tick % 16 >= 6)
    return if (reprise && answering) FloorLeadVoice.Vocal else passage.part.voice
}

private data class FloorBar(val tones: List<Pair<Int, Int>>, val gate: Double, val restAt: Int?)
private fun line(vararg notes: Pair<Int, Int>, gate: Double = .92, restAt: Int? = null) =
    FloorBar(notes.toList(), gate, restAt)
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

    // The rap is spoken. Play the accompaniment: two bars of E-flat power-chord
    // pulses, then octaves answering the low syncopation across a genuine beat of rest.
    private val powerPulse = line(0 to 51, 3 to 51, 6 to 51, 8 to 51, 11 to 51, 14 to 51)
    private val powerReply = line(0 to 63, 3 to 63, 6 to 63, 8 to 63, 11 to 63, 14 to 63)
    private val octaveAnswer = line(0 to 63, 3 to 63, 6 to 63, 12 to 75, 14 to 73, restAt = 8)
    private val fallingAnswer = line(0 to 63, 3 to 63, 6 to 63, 8 to 70, 10 to 69, 12 to 68, 14 to 67)
    private val turningAnswer = line(0 to 63, 3 to 63, 6 to 63, 8 to 70, 9 to 71, 10 to 73, 12 to 71, 14 to 70)

    private fun rap(local: Int): FloorBar = when {
        local == 0 -> powerPulse
        local == 1 -> powerReply
        local % 2 == 0 -> octaveAnswer
        local % 4 == 3 -> fallingAnswer
        else -> turningAnswer
    }

    // The voice enters off the downbeat. Dotted eighths, ties and the quarter rest
    // belong to the response; repeated E flats are separate attacks only where sung.
    private val verseEntry = line(2 to 61, 4 to 63, 6 to 63, 8 to 63, 10 to 63, 12 to 63, 14 to 63)
    private val versePickup = line(2 to 63, 4 to 63, 6 to 63, 8 to 63, 10 to 63, 12 to 63, 14 to 63)
    private val verseClose = line(0 to 63, 3 to 63, 6 to 63, 8 to 63, restAt = 12)
    private val verseFall = line(0 to 63, 3 to 63, 6 to 63, 8 to 63, 11 to 61, restAt = 13)
    private val verse = listOf(verseEntry, verseClose, versePickup, verseFall,
        versePickup, powerReply, versePickup, verseClose)
    private val verseMiddle = listOf(
        line(2 to 63, 4 to 66, 6 to 66, 8 to 66, 10 to 66, 12 to 66, 14 to 66),
        line(0 to 66, 3 to 66, 6 to 66, 8 to 66, restAt = 12),
        line(4 to 63, 6 to 63, 8 to 63, 10 to 63, 12 to 63, 14 to 63),
        verseFall,
    )

    private val rise = listOf(
        line(0 to 63, 3 to 63, 6 to 63, 8 to 63, 14 to 63),
        line(0 to 63, 6 to 63, 8 to 63, 10 to 63, restAt = 12),
        line(0 to 63, 2 to 63, 4 to 63, 6 to 63, 8 to 63, restAt = 10),
        line(0 to 63, 4 to 63, 6 to 63, 8 to 63, restAt = 10),
    )

    // On the dance reprise the descending riff overlaps a second vocal entrance.
    // The lower B flat rings across the start of the answer on the same timeline.
    private val danceCadence = line(0 to 58, 3 to 56, 4 to 58, 6 to 63, 8 to 63,
        10 to 63, 12 to 63, 14 to 63)
    private val danceAnswer = verseClose
    private val coda = line(0 to 63, 5 to 58, 8 to 63, gate = .98)

    fun phrase(part: FloorPart, local: Int): FloorBar = when (part) {
        FloorPart.Intro, FloorPart.Chorus -> refrain[local % 4]
        FloorPart.Hook, FloorPart.Final -> when (local % 4) {
            2 -> danceCadence
            3 -> danceAnswer
            else -> refrain[local % 4]
        }
        FloorPart.Outro -> if (local == 0) refrain[2] else coda
        FloorPart.Call -> rap(local)
        FloorPart.Verse, FloorPart.VerseReturn -> when (local) {
            in 0..7 -> verse[local]
            in 8..11 -> verseMiddle[local - 8]
            else -> verse[local - 8]
        }
        FloorPart.Rise -> rise[local]
        FloorPart.Turn -> powerReply
        FloorPart.Break -> if (local == 3) rest else rap(local)
        FloorPart.Reentry -> if (local == 0) octaveAnswer else powerReply
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
            val release = melody.restAt?.takeIf { it in (offset + 1) until next } ?: next
            val danceHold = (part == FloorPart.Hook || part == FloorPart.Final) &&
                local % 4 == 2 && offset == 4
            val length = if (danceHold) floorTime(8.0) else floorTime((release - offset) * melody.gate)
            val accent = when {
                i == 0 || next - offset >= 5 -> 1f
                offset % 4 == 0 -> .93f
                else -> .84f
            }
            lead += Note("floor-${at + offset}", onset, midi, length, part.melody * accent)
        }

        val last = bar == floorBars - 1
        val chord = when (part) {
            FloorPart.Call, FloorPart.Verse, FloorPart.VerseReturn, FloorPart.Turn -> tonic
            else -> if (last) tonic else progression[local % 4]
        }
        fun add(v: BackingVoice, midi: Int, tick: Double, gate: Double, gain: Float) {
            band += BackingNote(floorTime(at + tick), midi, floorTime(gate), gain, v)
        }
        if (part == FloorPart.Intro && local in 4..7) {
            // A second voice enters under the repeated introduction. Sustain its D flat
            // across the bar line. A unison with the foreground needs only one attack.
            fun response(pitch: Int, tick: Int, duration: Double) {
                if ((tick to pitch) !in tones) add(BackingVoice.Keys, pitch, tick.toDouble(), duration, .51f)
            }
            when (local) {
                4 -> {
                    response(63, 12, 1.8)
                    response(63, 14, 1.8)
                }
                5 -> {
                    for (tick in listOf(0, 3, 6, 8, 12)) response(63, tick, if (tick == 8) 3.7 else 1.9)
                    response(61, 14, 17.6)
                }
                7 -> {
                    response(61, 6, 1.9)
                    response(61, 8, 3.7)
                    response(58, 12, 1.9)
                    response(58, 14, 1.9)
                }
            }
        }
        val rap = part == FloorPart.Call || part == FloorPart.Break || part == FloorPart.Reentry
        if (rap) {
            // Without a pitched rapper the octave bass carries the riff. Avoid a second
            // top line or sustained minor triads filling the gaps between its answers.
            for ((index, tone) in melody.tones.withIndex()) {
                val (offset, pitch) = tone
                val next = melody.tones.getOrNull(index + 1)?.first ?: 16
                val end = melody.restAt?.takeIf { it in (offset + 1) until next } ?: next
                add(BackingVoice.Bass, pitch - if (pitch < 60) 12 else 24, offset.toDouble(),
                    (end - offset) * .84, if (offset == 0) .73f else .59f)
                if (part == FloorPart.Call && local == 1) {
                    add(BackingVoice.Keys, 58, offset.toDouble(), (end - offset) * .84, .38f)
                }
            }
            for (beat in 0..3) {
                val tick = beat * 4.0
                if (beat == 0 || (part != FloorPart.Break && beat == 2)) {
                    add(BackingVoice.Kick, 36, tick, 1.62, .75f * part.drums)
                }
                if (beat % 2 == 1) add(BackingVoice.Snare, 37, tick, .82, .54f * part.drums)
                if (local >= 2) add(BackingVoice.Hat, 42, tick + 2.0, .62, .29f * part.drums)
            }
            continue
        }
        val thin = part == FloorPart.Intro && local < 2
        val drop = part == FloorPart.Hook || part == FloorPart.Final
        val turn = local % 4 == 3
        val build = part == FloorPart.Rise
        for ((voice, pitch) in chord.inner.withIndex()) {
            val pad = if (thin) .37f else if (drop) .29f else .23f
            add(BackingVoice.Pad, pitch, .055 * voice, if (last) 15.3 else 14.75, pad)
        }
        if (part == FloorPart.Chorus || part == FloorPart.Turn) {
            for (offset in listOf(0, 3, 6, 10, 14)) {
                add(BackingVoice.Keys, chord.bass + 12, offset.toDouble(), 1.72, .51f)
            }
            add(BackingVoice.Bass, chord.bass, 0.0, 14.8, .50f)
            continue
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
        val leaveSpace = build && nextPassage != passage
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
    backingAsset = "backing/on-the-floor.m4a", bundledRevision = 6, load = ::floorChart,
)
