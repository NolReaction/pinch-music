package music.pinch

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
        private const val VOICE_COUNT = 8
        private const val MAX_AUDIO_DELAY_MS = 80L
        // On Android 7 even play() can make a blocking AudioService Binder call.
        private val output = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Pinch-SoundPool").apply { isDaemon = true }
        }
        private val outputScope = CoroutineScope(output.asCoroutineDispatcher())

        private val pitchRates = FloatArray(128) { midi ->
            2.0.pow((midi - instrumentRoot(midi)) / 12.0).toFloat()
        }

        suspend fun load(assets: AssetManager, instrument: Instrument, roots: Set<Int>, includeStrike: Boolean): NoteSamples {
            val pool = SoundPool.Builder().setMaxStreams(VOICE_COUNT).setAudioAttributes(gameAudioAttributes()).build()
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

    private data class Voice(val sample: Int, val rate: Float, val gain: Float, val requestedAt: Long)

    // Only the game thread swaps inboxes. The worker owns all stream IDs.
    private var inbox: Channel<Voice>? = null
    private var released = false
    @Volatile private var generation = 0L
    private val streams = IntArray(VOICE_COUNT)
    private var next = 0

    fun covers(selected: Instrument, roots: Set<Int>, includeStrike: Boolean) =
        instrument == selected && ids.keys.containsAll(roots) && (!includeStrike || strikeId != 0)

    fun play(pitch: Int, gain: Float) {
        val sample = ids[instrumentRoot(pitch)] ?: return
        enqueue(sample, pitchRates[pitch], gain)
    }

    fun strike(gain: Float) = enqueue(strikeId, 1f, gain)

    private fun enqueue(sample: Int, rate: Float, gain: Float) {
        if (released || sample == 0) return
        val queue = inbox ?: Channel<Voice>(VOICE_COUNT, BufferOverflow.DROP_OLDEST).also { channel ->
            inbox = channel
            val active = generation
            outputScope.launch {
                for (voice in channel) playOnWorker(voice, active)
            }
        }
        queue.trySend(Voice(sample, rate, gain.coerceIn(0f, 1f), SystemClock.uptimeMillis()))
    }

    private fun current(voice: Voice, active: Long) =
        active == generation && SystemClock.uptimeMillis() - voice.requestedAt <= MAX_AUDIO_DELAY_MS

    private fun playOnWorker(voice: Voice, active: Long) {
        if (!current(voice, active)) return
        val previous = streams[next]
        if (previous != 0) {
            pool.stop(previous)
            streams[next] = 0
            if (!current(voice, active)) return
        }
        val stream = pool.play(voice.sample, voice.gain, voice.gain, 1, 0, voice.rate)
        if (stream == 0) return
        // A pause, cache replacement or slow Binder transaction can overtake play().
        if (!current(voice, active)) {
            pool.stop(stream)
            return
        }
        streams[next] = stream
        next = (next + 1) % streams.size
    }

    fun silence() {
        val old = inbox ?: return
        generation++
        inbox = null
        old.cancel()
        output.execute {
            for (stream in streams) if (stream != 0) pool.stop(stream)
            streams.fill(0)
            next = 0
        }
    }

    fun release() {
        if (released) return
        released = true
        silence()
        output.execute { pool.release() }
    }
}

internal fun gameAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_GAME)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()
