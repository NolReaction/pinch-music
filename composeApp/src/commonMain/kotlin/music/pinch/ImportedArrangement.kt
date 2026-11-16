package music.pinch

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.markDetectedLandings

internal data class ImportedArrangement(
    val chart: Chart,
    val rhythmConfidence: Float,
    val quantizedNotes: Int,
)

private data class ImportGrid(val bpm: Int, val division: Int, val phase: Double, val confidence: Double) {
    val step: Double get() = 60_000.0 / bpm / division
    fun nearest(time: Long): Long = (phase + ((time - phase) / step).roundToLong() * step).roundToLong()
}

/** Re-score an extracted voice, rather than trying to subtract a singer from mixed audio.
 * The original chart and recording remain untouched. Uncertain rhythm keeps its original timing. */
internal fun adaptImportedChart(source: Chart): ImportedArrangement {
    val beat = 60_000.0 / source.bpm
    val useful = source.notes.filter { it.playable || it.durationMs >= 90 }
        .takeIf { it.size >= 3 } ?: source.notes
    val repaired = useful.indices.map { repairImportOctave(useful, it, beat) }
    val grid = importGrid(repaired, source.bpm)
    val lock = grid.confidence >= .74
    val maxShift = (60_000.0 / grid.bpm * .065).coerceAtMost(36.0)
    val starts = repaired.map { note ->
        val target = grid.nearest(note.atMs)
        if (lock && target >= 0 && target < source.durationMs && abs(target - note.atMs) <= maxShift) {
            target
        } else note.atMs
    }
    // Do not merge two real syllables or reorder a pickup when only its neighbour fits the grid.
    val safeStarts = starts.mapIndexed { index, time ->
        val before = repaired.getOrNull(index - 1)?.atMs ?: -1L
        val after = repaired.getOrNull(index + 1)?.atMs ?: source.durationMs
        if (time <= before + 35 || time >= after - 35) repaired[index].atMs else time
    }
    val notes = repaired.mapIndexed { index, note ->
        val start = safeStarts[index]
        val next = safeStarts.getOrNull(index + 1) ?: source.durationMs
        note.copy(
            atMs = start,
            durationMs = note.durationMs.coerceAtMost(next - start).coerceAtLeast(1),
            // Keep expression, but stop quiet recogniser frames becoming inaudible ghost notes.
            velocity = (.50f + .40f * note.velocity).coerceIn(.64f, .9f),
        )
    }
    val changes = notes.zip(repaired).count { (adapted, original) -> adapted.atMs != original.atMs }
    val bpm = if (lock) grid.bpm else source.bpm
    val arrangement = Chart(source.title, bpm, source.durationMs, notes,
        importedBacking(notes, bpm, if (lock) grid.division else 0, source.durationMs)).markDetectedLandings()
    return ImportedArrangement(arrangement, grid.confidence.toFloat(), changes)
}

private fun repairImportOctave(notes: List<Note>, index: Int, beat: Double): Note {
    val note = notes[index]
    val previous = notes.getOrNull(index - 1) ?: return note
    val next = notes.getOrNull(index + 1) ?: return note
    // Only a weak, brief octave island inside a continuous phrase is an extraction error.
    // A sustained octave leap or a deliberate accent must survive the arrangement.
    if (note.durationMs > minOf(220.0, beat * .45) ||
        (note.playable && note.velocity >= minOf(previous.velocity, next.velocity) * .88f) ||
        note.atMs - previous.atMs > beat || next.atMs - note.atMs > beat ||
        abs(previous.midi - next.midi) > 5) return note
    val corrected = listOf(note.midi - 12, note.midi + 12).firstOrNull {
        it in 0..127 && abs(it - previous.midi) <= 4 && abs(it - next.midi) <= 4 &&
            abs(note.midi - previous.midi) >= 9 && abs(note.midi - next.midi) >= 9
    } ?: return note
    return note.copy(midi = corrected)
}

private fun importGrid(notes: List<Note>, bpm: Int): ImportGrid {
    if (notes.size < 6 || notes.last().atMs - notes.first().atMs < 120_000.0 / bpm) {
        return ImportGrid(bpm, 2, notes.first().atMs.toDouble(), 0.0)
    }
    val anchors = notes.filter { it.playable }.takeIf { it.size >= 6 } ?: notes
    val original = fitImportGrid(anchors, bpm)
    // Flux autocorrelation can hear a two-beat accent as one slow beat. Only change that
    // interpretation when the actual note onsets clearly support the alternative; a slow
    // melody fitting both grids equally well must retain its original tempo.
    val alternatives = listOf(bpm * 2, (bpm / 2.0).roundToLong().toInt())
        .filter { it in 40..220 }.map { fitImportGrid(anchors, it) }
    return alternatives.filter { it.confidence >= .74 && it.confidence - original.confidence >= .12 }
        .maxByOrNull { it.confidence } ?: original
}

private fun fitImportGrid(anchors: List<Note>, bpm: Int): ImportGrid {
    var best = ImportGrid(bpm, 2, 0.0, 0.0)
    var bestScore = -1.0
    // Compare straight and triplet timing. A swung/triplet phrase must not be flattened to eighths.
    for (candidate in (bpm - 3).coerceAtLeast(40)..(bpm + 3).coerceAtMost(220)) {
        for (division in listOf(2, 3)) {
            val step = 60_000.0 / candidate / division
            val tolerance = minOf(26.0, step * .12)
            for (anchor in anchors.take(80)) {
                val phase = anchor.atMs % step
                var score = 0.0
                var weight = 0.0
                for (note in anchors) {
                    val distance = abs((note.atMs - phase) / step - ((note.atMs - phase) / step).roundToLong()) * step
                    val importance = (.35 + note.velocity) * (note.durationMs / step).coerceIn(.5, 1.5)
                    score += importance * exp(-.5 * distance * distance / (tolerance * tolerance))
                    weight += importance
                }
                val confidence = score / weight
                val preference = confidence - abs(candidate - bpm) * .006 - if (division == 3) .008 else 0.0
                if (preference > bestScore) {
                    bestScore = preference
                    best = ImportGrid(candidate, division, phase, confidence)
                }
            }
        }
    }
    return best
}

/** Harmony follows notes actually present in the melody. Do not invent a major/minor chord
 * from an ambiguous vocal line, or fill a real rest with a rigid drum loop. */
private fun importedBacking(notes: List<Note>, bpm: Int, division: Int, duration: Long): List<BackingNote> {
    val beat = 60_000.0 / bpm
    val result = mutableListOf<BackingNote>()
    fun add(at: Long, midi: Int, length: Long, velocity: Float, voice: BackingVoice) {
        if (at < 0 || at >= duration) return
        result += BackingNote(at, midi.coerceIn(0, 127), length.coerceIn(1, duration - at), velocity, voice)
    }
    var lastBass = -100_000L
    notes.forEachIndexed { index, note ->
        val previous = notes.getOrNull(index - 1)
        val phraseStart = previous == null || note.atMs - previous.atMs - previous.durationMs > beat * .9
        if (phraseStart || (division > 0 && note.atMs - lastBass >= beat * 1.8 && note.playable)) {
            add(note.atMs, 36 + note.midi % 12, minOf(note.durationMs, (beat * .60).roundToLong()),
                if (phraseStart) .48f else .38f, BackingVoice.Bass)
            lastBass = note.atMs
        }
        if (note.durationMs >= beat * 1.45) {
            add(note.atMs + 30, note.midi - 12, (note.durationMs * .78).roundToLong(), .23f, BackingVoice.Pad)
        }
    }
    if (division > 0) {
        val first = notes.first().atMs
        val end = notes.last().let { it.atMs + it.durationMs }
        var tick = 0
        while (true) {
            val time = first + (tick * beat / division).roundToLong()
            if (time >= end) break
            val sounding = notes.any { it.atMs <= time + beat * .25 && it.atMs + it.durationMs >= time - beat * .25 }
            if (sounding) {
                if (tick % (division * 2) == 0) add(time, 36, 135, .43f, BackingVoice.Kick)
                if (tick % (division * 2) == division) add(time, 38, 95, .25f, BackingVoice.Snare)
                if (division == 2 || tick % division != 1) {
                    add(time, 42, 45, if (tick % division == 0) .24f else .17f, BackingVoice.Hat)
                }
            }
            tick++
        }
    }
    return result.sortedBy { it.atMs }
}
