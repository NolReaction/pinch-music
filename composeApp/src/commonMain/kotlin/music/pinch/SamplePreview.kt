package music.pinch

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import music.pinch.core.Note

internal const val PreviewSampleRate = 44_100
internal fun previewFrame(ms: Long) = (ms * PreviewSampleRate + 500L) / 1000L

/** Read the mono PCM16 roots from the app's WAV bank. RIFF metadata chunks are optional and padded. */
private class RootSample(val rate: Int, val samples: ShortArray) {
    companion object {
        fun read(wav: ByteArray): RootSample {
            fun tag(offset: Int, id: String) = id.indices.all { wav[offset + it].toInt() == id[it].code }
            fun u16(at: Int) = (wav[at].toInt() and 255) or ((wav[at + 1].toInt() and 255) shl 8)
            fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            require(wav.size >= 12 && tag(0, "RIFF") && tag(8, "WAVE")) { "Некорректный WAV инструмента." }
            val end = 8L + u32(4)
            require(end in 12..wav.size.toLong())
            var offset = 12
            var rate = 0
            var data = -1
            var length = 0
            while (offset.toLong() + 8 <= end) {
                val payload = offset + 8
                val size = u32(offset + 4)
                val next = payload + size
                require(next <= end) { "Повреждённый WAV инструмента." }
                when {
                    tag(offset, "fmt ") -> {
                        require(size >= 16 && u16(payload) == 1 && u16(payload + 2) == 1 &&
                            u16(payload + 12) == 2 && u16(payload + 14) == 16) { "Ожидался моно PCM16." }
                        val sampleRate = u32(payload + 4)
                        require(sampleRate in 1..Int.MAX_VALUE.toLong())
                        rate = sampleRate.toInt()
                    }
                    tag(offset, "data") -> {
                        require(size >= 2 && size % 2 == 0L) { "Пустой или повреждённый сэмпл." }
                        data = payload
                        length = size.toInt() / 2
                    }
                }
                if (rate > 0 && data >= 0) break
                val paddedEnd = next + (size and 1L)
                require(paddedEnd <= end)
                offset = paddedEnd.toInt()
            }
            require(rate > 0 && data >= 0) { "В сэмпле отсутствуют данные WAV." }
            return RootSample(rate, ShortArray(length) { u16(data + it * 2).toShort() })
        }
    }
}

private class SampleVoice(note: Note, private val root: RootSample, rootMidi: Int) {
    val start = previewFrame(note.atMs)
    private val gate = (previewFrame(note.atMs + note.durationMs) - start).coerceAtLeast(1)
    private val release = previewFrame(16)
    private val step = root.rate.toDouble() / PreviewSampleRate * 2.0.pow((note.midi - rootMidi) / 12.0)
    private val gain = note.velocity / 32768f
    val end = start + minOf(gate + release, ceil(root.samples.size / step).toLong())

    fun mix(target: FloatArray, blockStart: Long, count: Int) {
        val first = (start - blockStart).coerceAtLeast(0).toInt()
        val last = min(blockStart + count, end) - blockStart
        var age = blockStart + first - start
        for (i in first until last.toInt()) {
            val source = age * step
            val lower = source.toInt().coerceAtMost(root.samples.lastIndex)
            val a = root.samples[lower].toInt()
            val b = root.samples[min(lower + 1, root.samples.lastIndex)].toInt()
            val sample = a + (source - lower).toFloat() * (b - a)
            val envelope = if (age < gate) 1f else (gate + release - 1 - age).toFloat() / (release - 1)
            target[i] += sample * gain * envelope
            age++
        }
    }
}

/** The score stays on its native sample clock even if no display frames arrive. */
internal class SamplePreview(
    private val notes: List<Note>, private val instrument: Instrument,
    private val load: (Instrument, Int) -> ByteArray,
) {
    private val roots = mutableMapOf<Int, RootSample>()
    private val active = mutableListOf<SampleVoice>()
    // Simultaneous unisons in a score have a single attack, as in live playback.
    private val lastOnset = LongArray(128) { -1L }
    private var next = 0

    fun mix(target: FloatArray, position: Long, count: Int) {
        while (next < notes.size && previewFrame(notes[next].atMs) < position + count) {
            val note = notes[next++]
            if (lastOnset[note.midi] == note.atMs) continue
            lastOnset[note.midi] = note.atMs
            val root = instrumentRoot(note.midi)
            val sample = roots.getOrPut(root) { RootSample.read(load(instrument, root)) }
            active += SampleVoice(note, sample, root)
        }
        for (voice in active) voice.mix(target, position, count)
        active.removeAll { it.end <= position + count }
    }
}
