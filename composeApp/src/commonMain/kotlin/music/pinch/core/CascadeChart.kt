package music.pinch.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Select landings on real onsets. The full score is retained separately for the soundtrack. */
internal fun Chart.cascadeChart(): Chart {
    val beatMs = 60_000.0 / bpm
    val minFlightMs = (beatMs * .90).roundToLong().coerceIn(400L, 580L)
    if ((1 until notes.size).all { notes[it].atMs - notes[it - 1].atMs >= minFlightMs }) return this

    // A chord has one landing. Keep its written target, then the strongest sustained voice.
    val voicePriority = compareBy<Note>({ it.playable }, { it.velocity }, { it.durationMs }, { it.midi })
    val attacks = notes.groupBy { it.atMs }.values.map { chord -> chord.maxWith(voicePriority) }
    val compatible = IntArray(attacks.size)
    val selected = BooleanArray(attacks.size)
    val best = DoubleArray(attacks.size + 1)
    var earlier = 0
    for (i in attacks.indices) {
        val note = attacks[i]
        while (earlier < i && note.atMs - attacks[earlier].atMs >= minFlightMs) earlier++
        compatible[i] = earlier
        val previous = attacks.getOrNull(i - 1)
        val beat = (note.atMs - attacks.first().atMs) / beatMs
        val nearest = beat.roundToLong()
        val onBeat = (1.0 - abs(beat - nearest) * 4).coerceAtLeast(0.0)
        val metricalWeight = if (nearest % 2L == 0L) 1.75 else .90
        val phraseStart = previous == null || note.atMs - previous.atMs > 1.4 * beatMs
        val contour = previous != null && abs(previous.midi - note.midi) >= 3
        val prominence = onBeat * metricalWeight + (note.durationMs / beatMs).coerceAtMost(1.25) +
            note.velocity + (if (contour) .4 else 0.0) + (if (phraseStart || i == attacks.lastIndex) 2.0 else 0.0)
        // A written accent outranks the two optional neighbours it can displace. Isolated
        // notes are always reachable. Weak passing notes inside a run can be omitted.
        val isolated = earlier == i && (i == attacks.lastIndex || attacks[i + 1].atMs - note.atMs >= minFlightMs)
        val optional = if (isolated || phraseStart) maxOf(.1, prominence - 2.4) else prominence - 2.4
        val weight = if (note.playable) optional + 12.0 else optional
        val withLanding = best[earlier] + weight
        selected[i] = withLanding > best[i]
        best[i + 1] = if (selected[i]) withLanding else best[i]
    }
    val landings = ArrayList<Note>()
    var i = attacks.lastIndex
    while (i >= 0) {
        if (selected[i]) {
            landings += attacks[i]
            i = compatible[i] - 1
        } else i--
    }
    landings.reverse()
    return copy(notes = landings)
}
