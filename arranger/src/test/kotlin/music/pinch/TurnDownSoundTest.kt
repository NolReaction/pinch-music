package music.pinch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

class TurnDownSoundTest {
    @Test fun electronicLeadKeepsSmoothAttacksShortReleasesAndVelocityColour() {
        for (midi in listOf(50, 52, 53, 62, 64, 65, 67, 84)) {
            val note = Note("probe", 0, midi, 282, .88f)
            val samples = turnDownLeadSamples(note)
            assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(samples.first()) < .02f && samples.last() == 0f)
            assertTrue(rms(samples, 10, 120) > .12)
            assertTrue(rms(samples, 405, 440) < rms(samples, 50, 150) * .02)
            val quiet = turnDownLeadSamples(note.copy(velocity = .3f))
            assertTrue(!samples.contentEquals(quiet), "Velocity changes the reed's upper colour")
        }
    }

    @Test fun masterStartsAtWrittenAttacksAndLeavesQuietSpaceAtTheEnd() {
        val notes = listOf(Note("first", 150, 64, 230, .9f), Note("second", 900, 65, 282, .85f),
            Note("last", 1650, 52, 600, .5f))
        val band = listOf(BackingNote(150, 40, 650, .7f, BackingVoice.Bass),
            BackingNote(150, 36, 180, .9f, BackingVoice.Kick),
            BackingNote(750, 38, 150, .65f, BackingVoice.Snare),
            BackingNote(1350, 42, 70, .4f, BackingVoice.Hat))
        val chart = Chart("Master check", 100, 3000, notes, band)
        val target = Files.createTempFile("turn-down-master", ".wav").toFile()
        try {
            writeTurnDownMaster(target, chart)
            val data = target.readBytes()
            assertEquals(44 + 3000 * 32 * 4, data.size)
            assertEquals("RIFF", data.copyOfRange(0, 4).decodeToString())
            val pcm = ByteBuffer.wrap(data, 44, data.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(pcm.remaining()).also(pcm::get)
            assertTrue(samples.take(150 * 32 * 2).all { it == 0.toShort() }, "No sound anticipates the score")
            assertTrue(samples.drop(160 * 32 * 2).take(100 * 32 * 2).any { abs(it.toInt()) > 1000 })
            assertTrue(samples.all { abs(it.toInt()) < 28_000 })
            assertTrue(samples.takeLast(50 * 32 * 2).all { it == 0.toShort() })
        } finally {
            target.delete()
        }
    }

    private fun rms(samples: FloatArray, firstMs: Int, lastMs: Int): Double {
        val part = samples.sliceArray(firstMs * 32 until minOf(lastMs * 32, samples.size))
        return sqrt(part.sumOf { it.toDouble() * it } / part.size)
    }
}
