package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PickedAudio(val displayName: String?, val samples: FloatArray, val recording: ImportRecording? = null)

/** The copy for listening retains the original channels. Analysis uses a filtered mono view of the same frames. */
class ImportRecording(val sampleRate: Int, val channels: Int, val pcm16: ByteArray) {
    init {
        require(sampleRate in 4000..48000 && channels in 1..2)
        require(pcm16.size in (sampleRate * channels * 2)..(30 * sampleRate * channels * 2) &&
            pcm16.size % (channels * 2) == 0) { "Пустой или повреждённый фрагмент записи." }
    }

    internal fun picked(name: String?): PickedAudio {
        val frames = pcm16.size / (channels * 2)
        fun value(index: Int): Float {
            val at = 2 * index
            val s = (pcm16[at].toInt() and 255) or (pcm16[at + 1].toInt() shl 8)
            return s / 32768f
        }
        // A wide stereo mix can cancel when summed. Estimate this from both channels
        // and analyse the louder channel when the mid signal has lost most of its power.
        var leftPower = 0.0
        var rightPower = 0.0
        var midPower = 0.0
        if (channels == 2) for (frame in 0 until frames step 7) {
            val left = value(frame * 2)
            val right = value(frame * 2 + 1)
            leftPower += left * left
            rightPower += right * right
            midPower += (left + right) * (left + right) * .25
        }
        val single = midPower < .10 * maxOf(leftPower, rightPower)
        val mono = FloatArray(frames) { i ->
            when {
                channels == 1 -> value(i)
                single -> value(2 * i + if (rightPower > leftPower) 1 else 0)
                else -> (value(2 * i) + value(2 * i + 1)) * .5f
            }
        }
        val rate = 11_025
        if (sampleRate == rate) return PickedAudio(name, mono, this)
        val radius = 16
        val phases = 64
        val cutoff = .46 * minOf(1.0, rate.toDouble() / sampleRate)
        val filters = Array(phases) { phase ->
            val weights = FloatArray(2 * radius) { j ->
                val x = j - radius + 1 - phase.toDouble() / phases
                val lowpass = if (abs(x) < 1e-7) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                (lowpass * (.5 + .5 * cos(PI * x / radius))).toFloat()
            }
            val norm = weights.sum()
            for (j in weights.indices) weights[j] /= norm
            weights
        }
        val converted = FloatArray((frames.toLong() * rate / sampleRate).toInt()) { i ->
            val pos = i.toLong() * sampleRate
            val base = (pos / rate).toInt() - radius + 1
            val weights = filters[((pos % rate) * phases / rate).toInt()]
            var sample = 0f
            for (j in weights.indices) sample += weights[j] * mono[(base + j).coerceIn(0, frames - 1)]
            sample
        }
        return PickedAudio(name, converted, this)
    }
}

internal fun importFilename(id: String): String {
    require(id.startsWith("import-") && id.length in 8..90 &&
        id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
    return "$id.wav"
}
