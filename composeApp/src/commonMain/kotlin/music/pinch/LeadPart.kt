package music.pinch

import kotlin.math.abs
import music.pinch.core.Chart
import music.pinch.core.FORGIVING_WINDOW_MS
import music.pinch.core.Note

/** Route the melodic voices between the player's keys and the score clock. */
internal class LeadPart(source: Chart, landings: Chart) {
    val timed = ArrayList<Note>()
    val pitches: Set<Int>
    private val followsKey = mutableMapOf<Long, Long>()
    private val chords = mutableMapOf<Long, List<Note>>()

    init {
        val keys = landings.notes
        val all = source.notes.groupBy { it.atMs }
        var nextKey = 0
        for ((time, voices) in all) {
            val chord = voices.distinctBy { it.midi }
            while (nextKey < keys.size && keys[nextKey].atMs < time) nextKey++
            val next = keys.getOrNull(nextKey)
            if (next?.atMs == time) {
                // A late finger still plays one chord. Its harmony must not attack first on the clock.
                chords[time] = listOf(next) + chord.filterNot { it.midi == next.midi }
                continue
            }
            val previous = keys.getOrNull(nextKey - 1)
            // An ornament just before the next key could already have sounded when
            // its early finger arrives. After a key, the actual down can decide instead.
            if (next != null && next.atMs - time <= FORGIVING_WINDOW_MS + 65) continue
            if (previous != null && time - previous.atMs <= FORGIVING_WINDOW_MS + 65) {
                followsKey[time] = previous.atMs
            }
            for (voice in chord) {
                if (!crowdsKey(voice, previous) && !crowdsKey(voice, next)) timed.add(voice)
            }
        }
        pitches = buildSet {
            for (voice in timed) add(voice.midi)
            for (key in keys) for (voice in chord(key)) add(voice.midi)
        }
    }

    private fun crowdsKey(ornament: Note, key: Note?): Boolean {
        if (key == null) return false
        val separation = abs(ornament.atMs - key.atMs)
        // A repeated pitch here could retrigger within a few ms of a valid early/late hit.
        // Preserve longer, distinct melodic steps and the notes in gaps between landings.
        return (ornament.midi == key.midi && separation <= FORGIVING_WINDOW_MS + 75) ||
            (separation <= 65 && ornament.durationMs <= 110)
    }

    fun maySound(note: Note, lastKey: Long?, sinceDown: Long): Boolean {
        val required = followsKey[note.atMs]
        return sinceDown >= 65 && (required == null || lastKey == required)
    }

    fun chord(key: Note): List<Note> = chords[key.atMs] ?: listOf(key)
}
