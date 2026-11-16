package music.pinch

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import music.pinch.core.Chart

/** A constant-peak bandpass. Bypass stays on the dry path, so its latency is zero. */
private class Bandpass(hz: Double, q: Double, rate: Double) {
    private val omega = 2 * PI * hz / rate
    private val alpha = sin(omega) / (2 * q)
    private val b = alpha / (1 + alpha)
    private val a1 = 2 * cos(omega) / (1 + alpha)
    private val a2 = (alpha - 1) / (1 + alpha)
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun sample(x: Double): Double {
        val y = b * (x - x2) + a1 * y1 + a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/** Remaster the in-repo performance, preserving the stereo field, chart zero and every written attack. */
internal fun masterStereoLove(decoded: File, mastered: File, chart: Chart) {
    AudioSystem.getAudioInputStream(decoded).use { input ->
        val format = input.format
        require(format.channels == 2 && format.sampleSizeInBits == 16 && !format.isBigEndian)
        val rate = format.sampleRate.toDouble()
        val frames = (chart.durationMs * rate / 1000).roundToInt()
        // AAC decoders may expose a partial final packet. Stop at exactly the written duration.
        require(abs(input.frameLength - frames) <= 2048)
        val pcm = input.readNBytes(frames * 4).let { if (it.size == frames * 4) it else it.copyOf(frames * 4) }
        val lowMid = Bandpass(360.0, .83, rate)
        val presence = Bandpass(1290.0, .82, rate)
        val reed = Bandpass(815.0, .60, rate)
        val onsets = chart.notes.map { (it.atMs * rate / 1000).roundToLong() }
        val rise = 1 - exp(-1 / (.0025 * rate))
        val decay = exp(-1 / (.041 * rate))
        var target = 0.0
        var articulation = 0.0
        var index = 0
        fun read(offset: Int): Double =
            ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 255)) / 32768.0
        fun write(offset: Int, value: Double) {
            val word = (value * 32768).roundToInt().coerceIn(-32768, 32767)
            pcm[offset] = word.toByte()
            pcm[offset + 1] = (word shr 8).toByte()
        }
        for (frame in 0 until frames) {
            if (index < onsets.size && frame.toLong() >= onsets[index]) {
                target = min(1.0, chart.notes[index].velocity / .82)
                index++
            }
            articulation += rise * (target - articulation)
            target *= decay
            val p = frame * 4
            val left = read(p)
            val right = read(p + 2)
            val mid = .5 * (left + right)
            // Give the repeated reed a front edge. Between notes, ease off its boxed-in
            // low mids. Only the centered signal is shaped; the stereo room retains its tail.
            val clear = mid - .23 * lowMid.sample(mid) + .12 * presence.sample(mid) +
                (.44 * articulation - .035) * reed.sample(mid)
            val side = .5 * (left - right) * (1.04 - .20 * articulation)
            write(p, clear + side)
            write(p + 2, clear - side)
        }
        AudioInputStream(ByteArrayInputStream(pcm), format, frames.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, mastered)
        }
    }
}
