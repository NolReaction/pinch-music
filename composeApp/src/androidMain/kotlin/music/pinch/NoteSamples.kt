package music.pinch

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** The activity owns one reusable bank. Only a fully decoded pool reaches the game thread. */
internal class NoteSampleCache(private val assets: AssetManager) {
    private var cached: NoteSamples? = null

    suspend fun get(instrument: Instrument, pitches: Set<Int>, includeStrike: Boolean): NoteSamples {
        val roots = pitches.mapTo(mutableSetOf(), ::instrumentRoot)
        cached?.takeIf { it.covers(instrument, roots, includeStrike) }?.let { return it }

        var loaded: NoteSamples? = null
        try {
            // Keep ownership here even when the session closes during the hop back to Main.
            withContext(Dispatchers.IO) { loaded = NoteSamples.load(assets, instrument, roots, includeStrike) }
            val result = checkNotNull(loaded)
            cached?.release()
            cached = result
            loaded = null
            return result
        } finally {
            loaded?.release()
        }
    }

    fun close() {
        cached?.release()
        cached = null
    }
}

internal class NoteSamples private constructor(
    private val pool: SoundPool,
    private val instrument: Instrument,
    private val ids: Map<Int, Int>,
    private val strikeId: Int,
) {
    companion object {
        private val pitchRates = FloatArray(128) { midi ->
            2.0.pow((midi - instrumentRoot(midi)) / 12.0).toFloat()
        }

        suspend fun load(assets: AssetManager, instrument: Instrument, roots: Set<Int>, includeStrike: Boolean): NoteSamples {
            val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(gameAudioAttributes()).build()
            // A small asset may finish before all the IDs have been submitted.
            val results = Channel<Int>(Channel.UNLIMITED)
            try {
                pool.setOnLoadCompleteListener { _, _, status -> results.trySend(status) }
                fun submit(path: String): Int = assets.openFd(path).use { fd ->
                    pool.load(fd, 1).also { check(it != 0) { "Не удалось загрузить звук инструмента." } }
                }
                val name = instrument.name.lowercase()
                val ids = roots.associateWith { root ->
                    currentCoroutineContext().ensureActive()
                    submit("instruments/$name/$root.wav")
                }
                val strikeId = if (includeStrike) submit("feedback/$name.wav") else 0
                repeat(ids.size + if (includeStrike) 1 else 0) {
                    check(results.receive() == 0) { "Не удалось декодировать звук инструмента." }
                }
                pool.setOnLoadCompleteListener(null)
                return NoteSamples(pool, instrument, ids, strikeId)
            } catch (e: Throwable) {
                pool.release()
                throw e
            } finally {
                results.close()
            }
        }
    }

    private val streams = IntArray(8)
    private var next = 0

    fun covers(selected: Instrument, roots: Set<Int>, includeStrike: Boolean) =
        instrument == selected && ids.keys.containsAll(roots) && (!includeStrike || strikeId != 0)

    fun play(pitch: Int, gain: Float) {
        val sample = ids[instrumentRoot(pitch)] ?: return
        playSample(sample, pitchRates[pitch], gain)
    }

    fun strike(gain: Float) = playSample(strikeId, 1f, gain)

    private fun playSample(sample: Int, rate: Float, gain: Float) {
        if (sample == 0) return
        val level = gain.coerceIn(0f, 1f)
        val previous = streams[next]
        if (previous != 0) pool.stop(previous)
        streams[next] = 0
        val stream = pool.play(sample, level, level, 1, 0, rate)
        if (stream != 0) {
            streams[next] = stream
            next = (next + 1) % streams.size
        }
    }

    fun silence() {
        for (stream in streams) if (stream != 0) pool.stop(stream)
        streams.fill(0)
        next = 0
    }

    fun release() = pool.release()
}

internal fun gameAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_GAME)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()
