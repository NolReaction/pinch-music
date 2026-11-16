package music.pinch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

class SamplePreviewTest {
    private fun root(rate: Int, samples: ShortArray): ByteArray {
        // Real WAV readers must skip the pad byte for an odd-sized metadata chunk.
        val wav = ByteBuffer.allocate(54 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(name: String) { wav.put(name.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); wav.putInt(wav.capacity() - 8); tag("WAVE")
        tag("JUNK"); wav.putInt(1); wav.put(42); wav.put(0)
        tag("fmt "); wav.putInt(16); wav.putShort(1); wav.putShort(1); wav.putInt(rate)
        wav.putInt(rate * 2); wav.putShort(2); wav.putShort(16)
        tag("data"); wav.putInt(samples.size * 2)
        for (sample in samples) wav.putShort(sample)
        return wav.array()
    }

    private suspend fun pcm(chart: Chart, instrument: Instrument?, load: (Instrument, Int) -> ByteArray): ShortArray {
        val stream = ByteArrayOutputStream()
        renderArrangement(chart, instrument, load) { block, size -> stream.write(block, 0, size) }
        val wav = ByteBuffer.wrap(stream.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(if (instrument == null) 22_050 else 44_100, wav.getInt(24))
        assertEquals(wav.capacity() - 44, wav.getInt(40))
        wav.position(44)
        return ShortArray(wav.remaining() / 2) { wav.short }
    }

    @Test fun previewPlaysChordOnceAndRendersTheOrnamentAcrossBlockBoundaries() = runBlocking {
        val chart = Chart("Import", 120, 411, listOf(
            Note("lead", 185, 60, 24, .5f),
            Note("same voice", 185, 60, 31, .4f),
            Note("chord", 185, 72, 12, .25f, playable = false),
            Note("ornament", 311, 61, 10, .3f, playable = false),
        ))
        val loads = mutableListOf<Int>()
        val samples = pcm(chart, Instrument.Mallet) { instrument, midi ->
            assertEquals(Instrument.Mallet, instrument)
            loads += midi
            root(44_100, ShortArray(5_000) { if (midi == 60) 10_000 else 4_000 })
        }
        assertEquals(listOf(60, 72), loads)
        fun at(ms: Long) = previewFrame(ms).toInt()
        assertTrue(samples.take(at(185)).all { it.toInt() == 0 })
        assertEquals(6_000, samples[at(185)].toInt())
        assertEquals(6_000, samples[at(193)].toInt())
        assertTrue(samples[at(185 + 24) - 1].toInt() in 5200..5300)
        assertTrue(samples[at(185 + 24 + 8)].toInt() in 2450..2550)
        assertTrue(samples.slice(at(225) until at(311)).all { it.toInt() == 0 })
        assertEquals(3_000, samples[at(311)].toInt())
        assertTrue(samples.drop(at(337) + 1).all { it.toInt() == 0 })
        assertEquals(at(411), samples.size)
    }

    @Test fun transposesTheRootWithLinearInterpolationAndStopsAtItsNaturalEnd() = runBlocking {
        val chart = Chart("Root", 120, 25, listOf(Note("n", 0, 65, 25, .8f)))
        val source = ShortArray(8) { (it * 1_000).toShort() }
        val samples = pcm(chart, Instrument.Synth) { _, rootMidi ->
            assertEquals(60, rootMidi)
            root(22_050, source)
        }
        val step = .5 * 2.0.pow(5 / 12.0)
        for (i in 0..8) {
            val expected = (i * step * 1_000 * .8).roundToInt()
            assertTrue(abs(samples[i].toInt() - expected) <= 1, "output frame $i")
        }
        assertTrue(samples.drop(ceil(source.size / step).toInt()).all { it.toInt() == 0 })
    }

    @Test fun previewRetainsTheBandSamplesAcrossChunksAndUsesTheDeclaredTrackLength() = runBlocking {
        val chart = Chart("Band", 120, 741, listOf(Note("quiet", 100, 60, 40, 0f)),
            listOf(BackingNote(150, 48, 532, .8f, BackingVoice.Bass)))
        val band = pcm(chart, null) { _, _ -> error("Band-only playback does not load roots") }
        val full = pcm(chart, Instrument.Piano) { _, _ -> root(44_100, shortArrayOf(0)) }
        assertEquals(previewFrame(741).toInt(), full.size)
        for (i in band.indices) {
            if (2 * i < full.size) assertEquals(band[i], full[2 * i], "band frame $i")
        }
        assertTrue(full.any { it.toInt() != 0 })
    }
}
