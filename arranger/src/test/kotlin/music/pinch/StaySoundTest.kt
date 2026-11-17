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

class StaySoundTest {
    @Test fun fastAndHeldSynthNotesKeepSoftEdgesAndAStableBody() {
        for (midi in listOf(53, 60, 68, 73, 84)) for (duration in listOf(90L, 430L, 900L)) {
            val samples = stayLeadSamples(Note("voice", 0, midi, duration, .9f))
            assertTrue(samples.all { it.isFinite() && abs(it) < 1.05f })
            assertTrue(abs(samples.first()) < .004f)
            assertTrue(samples.last() == 0f, "Both signs of floating-point zero represent silence")
            assertTrue(rms(samples, 25, 65) > .10, "The attack must remain audible at MIDI $midi")
            if (duration == 900L) {
                // Detuned ranks naturally beat, so compare a held note with its released
                // counterpart instead of requiring an arbitrary fraction of the attack.
                val released = stayLeadSamples(Note("released", 0, midi, 430, .9f))
                val heldBody = rms(samples, 650, 710)
                assertTrue(heldBody > .10, "The held body must remain audible at MIDI $midi")
                assertTrue(heldBody > rms(released, 650, 710) * 20,
                    "A held note must sustain distinctly beyond an already released note")
            }
        }
    }

    @Test fun musicalPositionDoesNotIntroduceAnAbruptInstrumentSwitch() {
        val first = Note("same", 0, 65, 700, .9f)
        val intro = stayLeadSamples(first)
        for (at in listOf(11_294L, 33_882L, 56_471L, 90_353L, 101_647L)) {
            assertTrue(intro.contentEquals(stayLeadSamples(first.copy(atMs = at))))
        }
    }

    @Test fun renderedStereoMasterHasExactLengthAudibleAttacksAndASilentTail() {
        val notes = listOf(Note("a", 0, 61, 160, .85f), Note("b", 600, 68, 300, .9f),
            Note("c", 1300, 65, 200, .8f))
        val band = listOf(BackingNote(0, 42, 300, .65f, BackingVoice.Bass),
            BackingNote(0, 36, 120, .8f, BackingVoice.Kick),
            BackingNote(600, 38, 90, .7f, BackingVoice.Snare))
        val chart = Chart("Stay render check", 170, 2300, notes, band, authoredLandings = true)
        val file = Files.createTempFile("stay-studio-", ".wav").toFile()
        try {
            writeStayMaster(file, chart)
            val bytes = file.readBytes()
            assertEquals(44 + 2300 * 32 * 4, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
            val samples = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(samples.remaining()).also(samples::get)
            assertTrue(pcm.all { abs(it.toInt()) < 27_000 })
            for (note in notes) {
                val from = note.atMs.toInt() * 32 * 2 + 640
                assertTrue(pcm.sliceArray(from until from + 1280).any { abs(it.toInt()) > 1200 })
            }
            assertTrue(pcm.takeLast(3200).all { it == 0.toShort() })
        } finally {
            file.delete()
        }
    }

    private fun rms(samples: FloatArray, fromMs: Int, toMs: Int): Double {
        val range = fromMs * 32 until minOf(toMs * 32, samples.size)
        return sqrt(range.sumOf { samples[it].toDouble() * samples[it] } / range.count())
    }
}
