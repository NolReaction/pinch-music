package music.pinch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import music.pinch.core.Chart
import music.pinch.core.Note

class ImportedArrangementRendererTest {
    private val chart = Chart("Reconstruction", 120, 1800, listOf(
        Note("one", 200, 60, 280, .8f), Note("two", 700, 64, 280, .9f), Note("three", 1200, 67, 280, .8f),
    ))

    private fun ImportRecording.samples(): IntArray {
        val source = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(pcm16.size / 2) { source.short.toInt() }
    }

    @Test fun producesTheExactTimelineWithHeadroomAndNoSoundBeforeTheFirstNote() = runBlocking {
        val result = renderImportedArrangement(adaptImportedChart(chart).chart)
        assertEquals(22_050, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(39_690 * 2, result.pcm16.size)
        val pcm = result.samples()
        assertTrue(pcm.take(4410).all { it == 0 })
        assertTrue(pcm.maxOf { abs(it) } in 4000..26000)
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        assertTrue(rms in 1000.0..12000.0, "RMS $rms")
        assertTrue(pcm.asList().zipWithNext().maxOf { (a, b) -> abs(a - b) } < 5000, "No discontinuous clicks")
        assertEquals(0, pcm.last())
    }

    @Test fun threeInstrumentColorsAreDeterministicAndAllContainTheLead() = runBlocking {
        val voices = Instrument.entries.map { renderImportedArrangement(chart, it).pcm16 }
        for ((index, instrument) in Instrument.entries.withIndex()) {
            assertContentEquals(voices[index], renderImportedArrangement(chart, instrument).pcm16)
            assertTrue(voices[index].any { it != 0.toByte() })
        }
        assertTrue(voices.indices.all { a -> voices.indices.all { b -> a == b || !voices[a].contentEquals(voices[b]) } })
    }

    @Test fun aFullThirtySecondImportKeepsEveryAttackAndHasNoClippedSamples() = runBlocking {
        val melody = listOf(60, 64, 67, 72, 69, 67, 64, 62)
        val source = Chart("Full import", 120, 30_000, (0 until 120).map { index ->
            val time = index * 250L
            Note("note-$index", time, melody[index % melody.size], 180, .76f + (index % 4) * .04f)
        })
        val began = System.nanoTime()
        val adapted = adaptImportedChart(source).chart
        val result = renderImportedArrangement(adapted)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(1_323_000, result.pcm16.size)
        assertEquals(source.notes.size, adapted.notes.size)
        val pcm = result.samples()
        assertTrue(pcm.maxOf { abs(it) } < 30_000)
        for (note in adapted.notes) {
            val first = (note.atMs * result.sampleRate / 1000).toInt()
            val end = first + result.sampleRate / 20
            assertTrue((first until end).any { abs(pcm[it]) > 1000 }, "Missing audible attack at ${note.atMs}")
        }
        println("30-second instrumental adaptation + render: $elapsedMs ms; PCM bytes: ${result.pcm16.size}; notes: ${adapted.notes.size}")
    }

    @Test fun cancellationDoesNotDeliverAPartiallyRenderedRecording() = runBlocking {
        val work = async {
            cancel()
            renderImportedArrangement(chart.copy(durationMs = 30_000))
        }
        assertFailsWith<CancellationException> { work.await() }
        Unit
    }
}
