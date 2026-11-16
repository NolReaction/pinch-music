package music.pinch.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Select landings on real onsets. The full score is retained separately for the soundtrack. */
internal fun Chart.cascadeChart(): Chart {
    if (authoredLandings) {
        if (notes.all { it.playable }) return this
        return copy(notes = notes.filter { it.playable })
    }
    val beatMs = 60_000.0 / bpm
    return fitLandings(beatMs, (beatMs * .90).roundToLong().coerceIn(400L, 580L))
}

/** Give clear eighth-note answers room in a new recording while keeping every detected note for listening. */
internal fun Chart.markDetectedLandings(): Chart {
    val beatMs = 60_000.0 / bpm
    val gap = (beatMs * .44).roundToLong().coerceIn(205L, 350L)
    val keys = fitLandings(beatMs, gap).notes.mapTo(HashSet()) { it.id }
    return copy(notes = notes.map { it.copy(playable = it.id in keys) }, authoredLandings = true)
}

private fun Chart.fitLandings(beatMs: Double, minFlightMs: Long): Chart {
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

/** Give the marble a full approach. Silence already present in the recording counts towards it. */
internal fun Chart.cascadePreRollMs(): Long {
    val approachMs = (300_000.0 / bpm).roundToLong().coerceIn(2400L, 3200L)
    return (approachMs - notes.first().atMs).coerceAtLeast(0L)
}

/** Sustains and longer breaks can support a passive crossing between actual
 * strikes. Ordinary attacks within the beat pattern keep an airborne transfer. */
internal fun Chart.hasGuideAfter(index: Int): Boolean {
    if (index < 0 || index >= notes.lastIndex) return false
    val note = notes[index]
    val next = notes[index + 1]
    val gap = next.atMs - note.atMs
    // A free flight this long would produce a very high arc on slow imports.
    if (gap >= 1550L) return true
    val beat = 60_000.0 / bpm
    if (gap < (beat * 2.05).coerceIn(1000.0, 1650.0)) return false
    val previous = notes.getOrNull(index - 1)?.let { note.atMs - it.atMs } ?: gap
    val following = notes.getOrNull(index + 2)?.let { it.atMs - next.atMs } ?: gap
    return gap >= minOf(previous, following) * 1.35 ||
        gap - note.durationMs >= beat * 1.15 || note.durationMs >= beat * 1.5
}
