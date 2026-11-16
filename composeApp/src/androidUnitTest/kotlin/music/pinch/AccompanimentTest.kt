package music.pinch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

class AccompanimentTest {
    private val chart = Chart("Band", 120, 600, listOf(Note("key", 200, 72, 100, .8f)))

    private suspend fun samples(score: Chart): List<Int> {
        val bytes = ByteArrayOutputStream()
        renderArrangement(score, null, { _, _ -> error("Unexpected sample load") }) { block, length ->
            bytes.write(block, 0, length)
        }
        val wav = bytes.toByteArray()
        val pcm = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, pcm.getShort(22).toInt())
        assertEquals(22_050, pcm.getInt(24))
        assertEquals(16, pcm.getShort(34).toInt())
        assertEquals(wav.size - 44, pcm.getInt(40))
        pcm.position(44)
        return List(pcm.remaining() / 2) { pcm.short.toInt() }
    }

    @Test
    fun voicesHaveDifferentAttacksAndLeaveSilenceOnEitherSideOfTheirNote() = runBlocking {
        val attacks = mutableSetOf<List<Int>>()
        for (kind in BackingVoice.entries) {
            val part = chart.copy(accompaniment = listOf(BackingNote(100, 60, 100, .8f, kind)))
            val pcm = samples(part)
            assertEquals(13_230, pcm.size)
            assertTrue(pcm.take(2_205).all { it == 0 })
            assertTrue(pcm.drop(7_000).all { it == 0 })
            assertTrue(pcm.maxOf { abs(it) } in 200..24_000)
            attacks.add(pcm.subList(2_205, 2_305))
        }
        assertEquals(BackingVoice.entries.size, attacks.size)
    }

    @Test
    fun keyboardHarmonyMovesAnOctaveOutOfThePlayableRegisterOnlyNearAKey() = runBlocking {
        val note = BackingNote(200, 72, 100, .6f, BackingVoice.Keys)
        val withUnison = samples(chart.copy(accompaniment = listOf(note)))
        val independent = chart.copy(notes = listOf(chart.notes.single().copy(midi = 81)))
        assertEquals(samples(independent.copy(accompaniment = listOf(note.copy(midi = 60)))), withUnison)
        val independentLater = note.copy(atMs = 480)
        assertEquals(samples(independent.copy(accompaniment = listOf(independentLater))),
            samples(chart.copy(accompaniment = listOf(independentLater))))
    }

    @Test
    fun packagedSourcesChooseTheRequestedStemAndNeverLookUpAnImportedTitle() {
        val paths = setOf("arrangements/stay/band.wav", "arrangements/stay/piano.m4a", "arrangements/stay/piano.wav")
        assertEquals("arrangements/stay/band.wav", packagedArrangement("stay", null, paths::contains))
        assertEquals("arrangements/stay/piano.m4a", packagedArrangement("stay", Instrument.Piano, paths::contains))
        assertEquals(null, packagedArrangement("stay", Instrument.Synth, paths::contains))
        assertEquals(null, packagedArrangement(null, Instrument.Piano) { error("Lookup of an import") })
    }

    @Test
    fun simultaneousRequestsReuseACompleteFileAndAMissingFileIsRebuilt() = runBlocking {
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val files = mutableSetOf<String>()
        val cache = AccompanimentCache({ _, _, slot ->
            writes += slot
            began.complete(Unit)
            finish.await()
            "band-$slot".also(files::add)
        }, files::contains)
        val first = async { cache.file(chart) }
        began.await()
        val concurrent = async { cache.file(chart) }
        finish.complete(Unit)
        assertEquals(first.await(), concurrent.await())
        assertEquals(listOf(1), writes)
        files.clear()
        assertEquals("band-0", cache.file(chart))
        assertEquals(listOf(1, 0), writes)
        assertEquals("band-3", cache.file(chart, Instrument.Piano))
        assertEquals("band-2", cache.file(chart, Instrument.Synth))
        assertEquals("band-2", cache.file(chart, Instrument.Synth))
        assertEquals("band-0", cache.file(chart))
        assertEquals(listOf(1, 0, 3, 2), writes)
    }
}
