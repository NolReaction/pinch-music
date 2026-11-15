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
    private val rates = FloatArray(128)
    private var strikeId = 0
    private var released = false

    fun load(assets: AssetManager, instrument: Instrument, pitches: Set<Int>, includeStrike: Boolean, done: () -> Unit, failed: (String) -> Unit) {
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
        if (includeStrike) {
            strikeId = assets.openFd("feedback/${instrument.name.lowercase()}.wav").use { pool.load(it, 1) }
            if (strikeId == 0) {
                fail()
                return
            }
            pending += strikeId
        }
        for (midi in pitches) rates[midi] = 2.0.pow((midi - instrumentRoot(midi)) / 12.0).toFloat()
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
        val root = instrumentRoot(pitch)
        val sample = idsByPitch[root] ?: return
        playSample(sample, rates[pitch], gain)
    }

    fun strike(gain: Float) = playSample(strikeId, 1f, gain)

    private fun playSample(sample: Int, rate: Float, gain: Float) {
        if (released || sample == 0 || rate <= 0f) return
        val level = gain.coerceIn(0f, 1f)
        // SoundPool recycles the oldest equal-priority voice when the eight slots fill up.
        pool.play(sample, level, level, 1, 0, rate)
    }

    fun silence() {
        // Resuming starts fresh notes. Leave interrupted envelopes silent until their slots recycle.
        pool.autoPause()
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
