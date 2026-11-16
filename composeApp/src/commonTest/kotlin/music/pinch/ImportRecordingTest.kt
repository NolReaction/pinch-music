package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportRecordingTest {
    @Test fun stereoInOppositePhaseStillHasAUsableMelodyAndTheSameTimeOrigin() {
        val rate = 48_000
        val bytes = ByteArray(rate * 4)
        val onset = rate / 4
        fun sample(at: Int, value: Int) {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
        }
        for (i in onset until rate) {
            val v = (16_000 * sin(2 * PI * 440 * (i - onset) / rate)).roundToInt()
            sample(i * 4, v)
            sample(i * 4 + 2, -v)
        }
        val input = ImportRecording(rate, 2, bytes)
        val audio = input.picked("Stereo.wav")
        assertSame(input, audio.recording)
        assertEquals(11_025, audio.samples.size)
        val start = audio.samples.indexOfFirst { abs(it) > .05f }
        assertTrue(abs(start * 1000.0 / 11_025 - 250) <= 1.0)
        assertTrue(audio.samples.max() in .47f..0.50f)
        val header = waveHeader(input.sampleRate, input.channels, bytes.size)
        assertEquals(2, header[22].toInt())
        assertEquals(4, header[32].toInt())
    }
}
