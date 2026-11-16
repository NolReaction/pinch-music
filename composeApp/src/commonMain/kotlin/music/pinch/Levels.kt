package music.pinch

import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val levelsBpm = 126
private const val levelsBars = 104
private fun levelsTime(beat: Double) = (beat * 60_000.0 / levelsBpm).roundToLong()

private enum class LevelsPart { Arpeggio, Suspension, Hook, Vocal, Build, Ending }
private fun levelsPart(bar: Int) = when (bar) {
    in 0..3 -> LevelsPart.Arpeggio
    in 4..7, in 40..43 -> LevelsPart.Suspension
    in 8..39, in 68..99 -> LevelsPart.Hook
    in 44..59 -> LevelsPart.Vocal
    in 60..67 -> LevelsPart.Build
    else -> LevelsPart.Ending
}

/** The gospel passage is played one octave higher so its lower notes carry on a phone speaker. */
internal fun levelsSoftLeadAt(atMs: Long): Boolean {
    return atMs in levelsTime(160.0) until levelsTime(240.0)
}

private data class LevelTone(val beat: Double, val midi: Int, val length: Double = .42)
private data class LevelChord(val bass: Int, val tones: IntArray)
private val levelsHarmony = listOf(
    LevelChord(37, intArrayOf(61, 64, 68)),
    LevelChord(40, intArrayOf(59, 64, 68)),
    LevelChord(35, intArrayOf(59, 63, 66)),
    LevelChord(33, intArrayOf(57, 61, 64)),
)

private object LevelsPhrases {
    // EMI's piano edition doubles this hook in octaves. One attack makes one key;
    // the saw's harmonics supply the upper octave without a second scheduled note.
    val hook = listOf(
        LevelTone(0.0, 66), LevelTone(.5, 64), LevelTone(1.0, 64),
        LevelTone(2.0, 64), LevelTone(2.5, 64), LevelTone(3.0, 64), LevelTone(3.5, 64),
        LevelTone(4.0, 63), LevelTone(4.5, 63), LevelTone(5.0, 64), LevelTone(5.5, 64),
        LevelTone(6.5, 73), LevelTone(7.0, 71), LevelTone(7.5, 68),
        LevelTone(8.0, 66), LevelTone(8.5, 64), LevelTone(9.0, 64),
        LevelTone(10.0, 64), LevelTone(10.5, 64), LevelTone(11.0, 64), LevelTone(11.5, 64),
        LevelTone(12.0, 61), LevelTone(12.5, 61), LevelTone(13.0, 59), LevelTone(13.5, 59),
        LevelTone(14.5, 73), LevelTone(15.0, 71), LevelTone(15.5, 68),
    )
    // The opening is a C-sharp pedal and a cross-staff sixteenth-note figure.
    // The right-hand attacks are playable; the lower arpeggio stays in the band.
    val opening = listOf(
        LevelTone(0.0, 49, .20), LevelTone(.25, 61, .20), LevelTone(.5, 63, .20),
        LevelTone(1.0, 61, .20), LevelTone(1.25, 63, .20), LevelTone(1.75, 61, .20),
        LevelTone(2.0, 66, .20), LevelTone(2.5, 61, .20), LevelTone(2.75, 68, .20),
        LevelTone(3.25, 61, .20), LevelTone(3.5, 63, .20), LevelTone(3.75, 61, .20),
        LevelTone(4.0, 49, .20), LevelTone(4.25, 61, .20), LevelTone(4.5, 63, .20),
        LevelTone(5.0, 61, .20), LevelTone(5.25, 63, .20), LevelTone(5.75, 61, .20),
        LevelTone(6.0, 66, .20), LevelTone(6.25, 73, .20), LevelTone(6.5, 71, .20),
        LevelTone(7.0, 66, .20), LevelTone(7.5, 61, .20),
    )
    val build = listOf(
        LevelTone(0.0, 73, .20), LevelTone(.5, 64, .20), LevelTone(1.0, 73, .20),
        LevelTone(1.5, 68, .20), LevelTone(2.0, 73, .20), LevelTone(2.5, 73, .20),
        LevelTone(3.0, 73, .20), LevelTone(3.5, 76, .20),
        LevelTone(4.0, 76, .20), LevelTone(4.5, 73, .20), LevelTone(5.0, 68, .20),
        LevelTone(5.5, 68, .20), LevelTone(6.0, 80, .20), LevelTone(6.5, 76, .20),
        LevelTone(7.0, 68, .20), LevelTone(7.5, 80, .20),
    )
    // The public performance demo gives the sung phrasing, including the two
    // different melismatic endings. Transpose the entire line, preserving intervals.
    val vocal = listOf(
        LevelTone(0.000, 80, 1.485),
        LevelTone(1.500, 78, 1.985),
        LevelTone(3.500, 76, 0.485),
        LevelTone(4.000, 73, 1.985),
        LevelTone(6.500, 71, 0.485),
        LevelTone(7.000, 68, 0.235),
        LevelTone(7.250, 66, 0.235),
        LevelTone(7.500, 64, 0.485),
        LevelTone(8.000, 73, 0.485),
        LevelTone(8.500, 73, 0.735),
        LevelTone(11.500, 68, 0.235),
        LevelTone(11.750, 66, 0.235),
        LevelTone(12.000, 64, 1.235),
        LevelTone(17.000, 83, 0.485),
        LevelTone(17.500, 80, 0.485),
        LevelTone(18.000, 83, 0.485),
        LevelTone(18.500, 83, 0.485),
        LevelTone(19.000, 80, 0.485),
        LevelTone(19.500, 80, 0.485),
        LevelTone(20.000, 85, 0.485),
        LevelTone(20.500, 85, 0.235),
        LevelTone(20.750, 83, 0.485),
        LevelTone(21.250, 83, 0.235),
        LevelTone(21.556, 80, 0.360),
        LevelTone(21.962, 78, 0.271),
        LevelTone(22.238, 76, 0.285),
        LevelTone(22.552, 73, 0.398),
        LevelTone(23.000, 76, 0.350),
        LevelTone(23.390, 73, 0.260),
        LevelTone(23.692, 71, 0.294),
        LevelTone(24.000, 73, 0.985),
        LevelTone(25.500, 68, 0.235),
        LevelTone(25.750, 66, 0.235),
        LevelTone(26.000, 64, 0.235),
        LevelTone(26.500, 71, 0.485),
        LevelTone(27.000, 68, 0.235),
        LevelTone(27.250, 66, 0.235),
        LevelTone(27.500, 64, 0.235),
        LevelTone(28.000, 73, 0.450),
        LevelTone(28.500, 73, 0.485),
        LevelTone(29.500, 68, 0.235),
        LevelTone(29.750, 66, 0.235),
        LevelTone(30.000, 64, 0.735),
        LevelTone(32.000, 80, 1.485),
        LevelTone(33.500, 78, 1.985),
        LevelTone(35.500, 76, 0.485),
        LevelTone(36.000, 73, 1.235),
        LevelTone(38.500, 71, 0.485),
        LevelTone(39.000, 68, 0.235),
        LevelTone(39.250, 66, 0.235),
        LevelTone(39.500, 64, 0.485),
        LevelTone(40.000, 73, 0.485),
        LevelTone(40.500, 73, 0.485),
        LevelTone(43.500, 68, 0.235),
        LevelTone(43.750, 66, 0.235),
        LevelTone(44.000, 64, 0.985),
        LevelTone(49.000, 83, 0.485),
        LevelTone(49.500, 80, 0.485),
        LevelTone(50.000, 83, 0.235),
        LevelTone(50.500, 83, 0.235),
        LevelTone(51.000, 80, 0.235),
        LevelTone(51.500, 80, 0.235),
        LevelTone(52.000, 85, 0.235),
        LevelTone(52.500, 85, 0.235),
        LevelTone(52.750, 83, 0.235),
        LevelTone(53.250, 83, 0.235),
        LevelTone(53.556, 80, 0.440),
        LevelTone(54.008, 78, 0.281),
        LevelTone(54.308, 76, 0.298),
        LevelTone(54.631, 73, 0.346),
        LevelTone(54.987, 76, 0.323),
        LevelTone(55.331, 73, 0.279),
        LevelTone(55.650, 71, 0.335),
        LevelTone(56.000, 73, 0.979),
        LevelTone(57.500, 68, 0.235),
        LevelTone(57.750, 66, 0.235),
        LevelTone(58.000, 64, 0.496),
        LevelTone(58.500, 71, 0.485),
        LevelTone(59.000, 68, 0.235),
        LevelTone(59.250, 66, 0.235),
        LevelTone(59.500, 64, 0.485),
        LevelTone(60.000, 73, 0.471),
        LevelTone(60.500, 73, 0.485),
        LevelTone(61.500, 68, 0.235),
        LevelTone(61.750, 66, 0.235),
        LevelTone(62.000, 64, 0.485)
    )
}

private fun levelsChart(): Chart {
    val lead = mutableListOf<Note>()
    val backing = mutableListOf<BackingNote>()
    fun melody(start: Double, tones: List<LevelTone>, gain: Float) {
        for (tone in tones) {
            val at = levelsTime(start + tone.beat)
            lead += Note("levels-$at", at, tone.midi, levelsTime(tone.length).coerceAtLeast(1), gain)
        }
    }
    for (cycle in 0..1) melody(cycle * 8.0, LevelsPhrases.opening, .55f + .035f * cycle)
    melody(16.0, listOf(LevelTone(0.0, 66, 15.65)), .50f)
    for (bar in 8 until 40 step 4) melody(bar * 4.0, LevelsPhrases.hook, if (bar < 24) .87f else .94f)
    melody(160.0, listOf(LevelTone(0.0, 64, 7.5), LevelTone(8.0, 68, 7.4)), .44f)
    melody(176.0, LevelsPhrases.vocal, .90f)
    for (cycle in 0..3) melody(240.0 + cycle * 8, LevelsPhrases.build, .56f + cycle * .075f)
    for (bar in 68 until 100 step 4) melody(bar * 4.0, LevelsPhrases.hook, if (bar < 84) .96f else .91f)
    melody(400.0, LevelsPhrases.hook, .79f)
    melody(416.0, listOf(LevelTone(0.0, 73, 1.5)), .72f)

    fun add(voice: BackingVoice, midi: Int, at: Double, length: Double, gain: Float) {
        backing += BackingNote(levelsTime(at), midi, levelsTime(length), gain, voice)
    }
    for (bar in 0 until levelsBars) {
        val start = bar * 4.0
        val part = levelsPart(bar)
        val suspended = part == LevelsPart.Suspension
        val vocal = part == LevelsPart.Vocal
        val building = part == LevelsPart.Build
        val intro = part == LevelsPart.Arpeggio
        val closing = part == LevelsPart.Ending
        val fade = if (closing) 1f - .17f * (bar - 100) else 1f
        for (half in 0..1) {
            val at = start + half * 2
            val chord = if (bar < 8) levelsHarmony[0] else levelsHarmony[((at / 2).toInt()) % 4]
            val padGain = (if (vocal || suspended) .25f else .14f) * fade
            for ((index, pitch) in chord.tones.withIndex()) {
                add(BackingVoice.Pad, pitch - 12, at + index * .012, 1.90, padGain)
            }
            if (suspended || vocal) {
                add(BackingVoice.Bass, chord.bass, at, 1.83, .46f * fade)
            } else {
                for (offbeat in listOf(.5, 1.5)) {
                    add(BackingVoice.Bass, chord.bass, at + offbeat, .38, .73f * fade)
                }
            }
        }
        if (part == LevelsPart.Hook || closing) {
            val phraseBar = bar % 4
            for (tone in LevelsPhrases.hook.filter { it.beat >= phraseBar * 4 && it.beat < (phraseBar + 1) * 4 }) {
                val at = start + tone.beat - phraseBar * 4
                val chord = levelsHarmony[((at / 2).toInt()) % 4]
                for (pitch in chord.tones) add(BackingVoice.Keys, pitch, at, .37, .19f * fade)
            }
        }
        if (intro) {
            for ((offset, pitch) in listOf(0.0 to 49, .75 to 56, 1.5 to 52, 2.25 to 54, 3.0 to 56)) {
                add(BackingVoice.Keys, pitch, start + offset, .20, .38f)
            }
        }
        if (building) {
            // A quieter sixteenth layer answers the selected lead accents.
            val notes = if (bar % 2 == 0) intArrayOf(73,68,64,68,73,76,68,73,73,68,73,80,73,68,76,73)
                else intArrayOf(76,68,73,76,68,76,68,73,80,68,76,81,68,73,80,73)
            for ((i, pitch) in notes.withIndex()) add(BackingVoice.Keys, pitch, start + i * .25, .19, .18f)
        }
        val dance = part == LevelsPart.Hook || closing
        for (beat in 0..3) {
            val at = start + beat
            if (dance || building || (intro && beat % 2 == 0)) {
                val gain = if (building) .36f + (bar - 60) * .055f else if (intro) .43f else .91f
                if (!(building && bar == 67 && beat >= 2)) add(BackingVoice.Kick, 36, at, .36, gain * fade)
            }
            if (dance && beat % 2 == 1) add(BackingVoice.Snare, 39, at, .22, .65f * fade)
            if (!suspended && (!intro || beat % 2 == 1)) {
                add(BackingVoice.Hat, if (vocal) 70 else 42, at + .5, .15,
                    (if (vocal) .19f else .31f) * fade)
            }
            if (dance && beat == 3) add(BackingVoice.Hat, 46, at + .75, .23, .23f * fade)
        }
        if (building && bar >= 64) {
            val count = if (bar >= 66) 8 else 4
            for (i in 0 until count) add(BackingVoice.Snare, 37, start + i * 4.0 / count, .12,
                (.20f + (bar - 64) * .055f) * if (i % 2 == 0) 1f else .70f)
        }
    }
    add(BackingVoice.Bass, 37, 416.0, 1.5, .48f)
    for (pitch in levelsHarmony[0].tones) add(BackingVoice.Pad, pitch - 12, 416.0, 1.5, .15f)
    val melody = lead.sortedBy { it.atMs }
    val pitchedAttacks = melody.map { it.atMs to it.midi }.toSet()
    val band = backing.filter { it.voice != BackingVoice.Keys || (it.atMs to it.midi) !in pitchedAttacks }
        .sortedBy { it.atMs }.distinctBy { Triple(it.atMs, it.midi, it.voice) }
    return Chart("Levels", levelsBpm, levelsTime(levelsBars * 4.0) + 1800,
        melody, band, authoredLandings = true)
}

internal val levelsSong = Song.arranged(
    id = "levels", title = "Levels", artist = "Avicii", bpm = levelsBpm,
    durationMs = levelsTime(levelsBars * 4.0) + 1800,
    backingAsset = "backing/levels.m4a", bundledRevision = 4, load = ::levelsChart,
)
