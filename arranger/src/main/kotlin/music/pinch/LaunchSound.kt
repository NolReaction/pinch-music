package music.pinch

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Three felt mallet contacts. Onsets correspond to the three contacts in BeatMark. */
internal fun writeLaunchSound(file: File) {
    val rate = 32_000
    val length = rate * 740 / 1000
    val bytes = ByteBuffer.allocate(44 + length * 2).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(s: String) { bytes.put(s.toByteArray(Charsets.US_ASCII)) }
    tag("RIFF"); bytes.putInt(bytes.capacity() - 8); tag("WAVE")
    tag("fmt "); bytes.putInt(16); bytes.putShort(1); bytes.putShort(1)
    bytes.putInt(rate); bytes.putInt(rate * 2); bytes.putShort(2); bytes.putShort(16)
    tag("data"); bytes.putInt(length * 2)
    val onsets = doubleArrayOf(.030, .215, .435)
    val pitches = doubleArrayOf(392.0, 523.251, 659.255)
    val gains = doubleArrayOf(.23, .20, .25)
    for (frame in 0 until length) {
        val t = frame.toDouble() / rate
        var sample = 0.0
        for (i in onsets.indices) {
            val age = t - onsets[i]
            if (age <= 0.0) continue
            val phase = age * pitches[i] * 2 * PI
            val attack = min(1.0, age / .005)
            val tine = .80 * sin(phase) + .14 * sin(2.0 * phase) + .06 * sin(3.96 * phase) * exp(-age * 22)
            sample += gains[i] * attack * exp(-age * (if (i == 2) 12.0 else 19.0)) * tine
        }
        val release = min(1.0, (length - 1 - frame) / (rate * .024))
        bytes.putShort((sample * release * 32767).roundToInt().toShort())
    }
    file.writeBytes(bytes.array())
}
