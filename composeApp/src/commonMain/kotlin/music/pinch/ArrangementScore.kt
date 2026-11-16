package music.pinch

import music.pinch.core.Chart

enum class Instrument(val label: String) { Piano("Фортепиано"), Mallet("Маримба"), Synth("Синтезатор") }

class SongScore internal constructor(val bpm: Int, val durationMs: Long, internal val chart: Lazy<Chart>,
    internal val supplied: Chart? = null)

data class Song(
    val id: String, val title: String, val artist: String, val backingAsset: String?,
    val bundledRevision: Int, private val score: SongScore?,
) {
    constructor(id: String, title: String, artist: String, bundled: Chart? = null, backingAsset: String? = null,
        bundledRevision: Int = 1) : this(id, title, artist, backingAsset, bundledRevision,
        bundled?.let { SongScore(it.bpm, it.durationMs, lazyOf(it), supplied = it) })

    val bundled: Chart? get() = score?.chart?.value
    val hasBundled: Boolean get() = score != null
    val bundledBpm: Int? get() = score?.bpm
    val bundledDurationMs: Long? get() = score?.durationMs
    internal val suppliedChart: Chart? get() = score?.supplied
    internal val loadedBundled: Chart? get() = score?.chart?.takeIf { it.isInitialized() }?.value

    fun copy(id: String = this.id, title: String = this.title, artist: String = this.artist, bundled: Chart?,
        backingAsset: String? = this.backingAsset, bundledRevision: Int = this.bundledRevision) =
        Song(id, title, artist, bundled, backingAsset, bundledRevision)

    companion object {
        fun arranged(id: String, title: String, artist: String, bpm: Int, durationMs: Long,
            backingAsset: String? = null, bundledRevision: Int = 1, load: () -> Chart) =
            Song(id, title, artist, backingAsset, bundledRevision, SongScore(bpm, durationMs, lazy(load)))
    }
}

// Sparse octave samples cover MIDI 0…127 with no more than seven semitones of resampling.
internal fun instrumentRoot(midi: Int): Int = (((midi + 6) / 12) * 12).coerceIn(0, 120)
