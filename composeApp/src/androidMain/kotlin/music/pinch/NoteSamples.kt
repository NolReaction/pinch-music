package music.pinch

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.pow

/** Created on the main looper, so load completions and gameplay share one thread. */
internal class NoteSamples {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(gameAudioAttributes())
        .build()
    private val idsByPitch = mutableMapOf<Int, Int>()
    private val active = ArrayDeque<Int>()
    private var released = false

    fun load(assets: AssetManager, instrument: Instrument, pitches: Set<Int>, done: () -> Unit, failed: (String) -> Unit) {
        check(idsByPitch.isEmpty())
        if (pitches.isEmpty()) {
            done()
            return
        }
        val pending = mutableSetOf<Int>()
        var loadingFailed = false
        fun fail() {
            if (!loadingFailed && !released) {
                loadingFailed = true
                failed("Не удалось загрузить звуки мелодии.")
            }
        }
        pool.setOnLoadCompleteListener { _, id, status ->
            if (released || loadingFailed) return@setOnLoadCompleteListener
            if (status != 0) fail()
            else if (pending.remove(id) && pending.isEmpty()) done()
        }
        for (pitch in pitches.map(::instrumentRoot).toSet()) {
            val id = assets.openFd("instruments/${instrument.name.lowercase()}/$pitch.wav").use { pool.load(it, 1) }
            if (id == 0) {
                fail()
                return
            }
            idsByPitch[pitch] = id
            pending += id
        }
    }

    fun play(pitch: Int, gain: Float) {
        if (released) return
        val root = instrumentRoot(pitch)
        val sample = idsByPitch[root] ?: return
        val rate = 2.0.pow((pitch - root) / 12.0).toFloat()
        val level = gain.coerceIn(0f, 1f)
        val stream = pool.play(sample, level, level, 1, 0, rate)
        if (stream == 0) return
        if (active.size == 8) pool.stop(active.removeFirst())
        active.addLast(stream)
    }

    fun silence() {
        active.forEach(pool::stop)
        active.clear()
    }

    fun release() {
        if (released) return
        released = true
        pool.setOnLoadCompleteListener(null)
        silence()
        pool.release()
    }
}

internal fun gameAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_GAME)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()
