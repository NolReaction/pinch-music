package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart

internal fun waveHeader(outputRate: Int, channels: Int, dataBytes: Int): ByteArray {
    val header = ByteArray(44)
    fun word(offset: Int, value: Int, bytes: Int) {
        repeat(bytes) { header[offset + it] = (value ushr (it * 8)).toByte() }
    }
    fun tag(offset: Int, value: String) { value.encodeToByteArray().copyInto(header, offset) }
    tag(0, "RIFF"); word(4, 36 + dataBytes, 4); tag(8, "WAVE"); tag(12, "fmt ")
    word(16, 16, 4); word(20, 1, 2); word(22, channels, 2); word(24, outputRate, 4)
    word(28, outputRate * channels * 2, 4); word(32, channels * 2, 2); word(34, 16, 2)
    tag(36, "data"); word(40, dataBytes, 4)
    return header
}

/** Chart time zero is also the first sample of this instrumental track. */
private const val SampleRate = PreviewSampleRate / 2
private const val Block = 4096
private const val TableSize = 2048
private val sine = FloatArray(TableSize) { sin(2 * PI * it / TableSize).toFloat() }
private const val PhaseScale = 2147483648.0
private fun phaseStep(hz: Double) = (hz * PhaseScale / SampleRate).toInt()
private fun wave(phase: Int) = sine[(phase ushr 20) and (TableSize - 1)]
private fun frames(ms: Long) = (ms * SampleRate + 500) / 1000

private class BandTone(note: BackingNote, midi: Int) {
    val start = frames(note.atMs)
    private val kind = note.voice
    private val gate = frames(note.durationMs).coerceAtLeast(1)
    private val attack = when (kind) {
        BackingVoice.Pad -> 620
        BackingVoice.Keys -> 90
        BackingVoice.Bass -> 75
        else -> 15
    }
    private val release = when (kind) {
        BackingVoice.Pad -> 2315
        BackingVoice.Keys -> 1320
        BackingVoice.Bass -> 650
        else -> 160
    }
    val end = start + gate + release
    private val gain = note.velocity * when (kind) {
        BackingVoice.Bass -> .35f
        BackingVoice.Keys -> .24f
        BackingVoice.Pad -> .16f
        BackingVoice.Kick -> .51f
        BackingVoice.Snare -> .40f
        BackingVoice.Hat -> .24f
    }
    private val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private val step = phaseStep(hz)
    private val detuned = phaseStep(hz * 1.004)
    private var phase = 0
    private var secondPhase = 0
    private var kickSweep = phaseStep(112.0).toFloat()
    private val kickBase = phaseStep(45.0)
    private var noiseState = (note.atMs.toInt() xor (note.midi shl 12)) + 1
    private var previousNoise = 0f
    private var air = 0f
    private var level = 1f
    private val decay = when (kind) {
        BackingVoice.Keys -> .99992f
        BackingVoice.Kick -> .99915f
        BackingVoice.Snare -> .99895f
        BackingVoice.Hat -> .9978f
        else -> 1f
    }

    fun mix(block: FloatArray, from: Int, to: Int, ageAtStart: Long) {
        var age = ageAtStart
        for (i in from until to) {
            val amplitude = min(1f, age.toFloat() / attack) * min(1f, (gate + release - age).toFloat() / release)
            val sample = when (kind) {
                BackingVoice.Bass -> .79f * wave(phase) + .16f * wave(phase * 2) + .05f * wave(phase * 3)
                BackingVoice.Keys -> wave(phase) * .67f + level * (.23f * wave(phase * 2) + .1f * wave(phase * 4))
                BackingVoice.Pad -> .50f * wave(phase) + .34f * wave(secondPhase) + .16f * wave(phase * 3)
                BackingVoice.Kick -> wave(phase)
                BackingVoice.Snare, BackingVoice.Hat -> {
                    noiseState = noiseState * 1664525 + 1013904223
                    val noise = noiseState.toFloat() / 2147483648f
                    val high = .5f * (noise - previousNoise)
                    previousNoise = noise
                    air += .34f * (noise - air)
                    if (kind == BackingVoice.Hat) high else .53f * air + .33f * high + .14f * wave(phase)
                }
            }
            block[i] += sample * gain * amplitude * level
            if (kind == BackingVoice.Kick) {
                phase += kickBase + kickSweep.toInt()
                kickSweep *= .9968f
            } else {
                phase += step
                secondPhase += detuned
            }
            level *= decay
            age++
        }
    }
}

/** Keep the chord's pitch class without doubling the player's attack in the lead register. */
private fun BackingNote.harmonyPitch(leadTimes: Map<Int, List<Long>>): Int {
    if (voice != BackingVoice.Keys && voice != BackingVoice.Pad) return midi
    val times = leadTimes[midi] ?: return midi
    val index = times.binarySearch(atMs - 250)
    val next = if (index >= 0) index else -index - 1
    if (next >= times.size || times[next] > atMs + 250) return midi
    return if (midi >= 12) midi - 12 else midi + 12
}

/** The band runs at 22.05 kHz; a full preview retains the sample bank's original 44.1 kHz. */
internal suspend fun renderArrangement(
    chart: Chart, previewInstrument: Instrument?, loadRoot: (Instrument, Int) -> ByteArray,
    write: (ByteArray, Int) -> Unit,
) {
    val preview = previewInstrument?.let { SamplePreview(chart.notes, it, loadRoot) }
    val outputRate = if (preview == null) SampleRate else PreviewSampleRate
    require(chart.durationMs <= (Int.MAX_VALUE - 44L) * 500 / outputRate)
    val total = (chart.durationMs * outputRate + 500) / 1000
    val bandFrames = if (preview == null) total else (total + 1) / 2
    val header = waveHeader(outputRate, 1, 2 * total.toInt())
    write(header, header.size)
    val leadTimes = chart.notes.groupBy({ it.midi }, { it.atMs })
    val active = mutableListOf<BandTone>()
    val band = FloatArray(Block)
    val output = if (preview == null) band else FloatArray(Block * 2 + 1)
    val pcm = ByteArray(output.size * 2)
    var index = 0
    var position = 0L
    var outPosition = 0L
    var previous = 0f
    while (position < bandFrames) {
        currentCoroutineContext().ensureActive()
        val count = min(Block.toLong(), bandFrames - position).toInt()
        band.fill(0f, 0, count)
        while (index < chart.accompaniment.size && frames(chart.accompaniment[index].atMs) < position + count) {
            val note = chart.accompaniment[index++]
            active += BandTone(note, note.harmonyPitch(leadTimes))
        }
        for (tone in active) {
            val begin = maxOf(position, tone.start)
            val end = minOf(position + count, tone.end)
            tone.mix(band, (begin - position).toInt(), (end - position).toInt(), begin - tone.start)
        }
        var outCount = 0
        for (i in 0 until count) {
            val x = band[i]
            val sample = .75f * x / (1f + .50f * abs(x))
            // Interpolate the half-sample between consecutive band samples, also across a block boundary.
            if (preview != null && (position > 0 || i > 0)) output[outCount++] = (previous + sample) * .5f
            output[outCount++] = sample
            previous = sample
        }
        position += count
        if (position == bandFrames && outPosition + outCount < total) output[outCount++] = previous
        preview?.mix(output, outPosition, outCount)
        for (i in 0 until outCount) {
            val sample = output[i]
            // Only the loudest combined peaks need limiting. Normal sample attacks retain their recorded gain.
            val excess = abs(sample) - .9f
            val limited = if (preview == null || excess <= 0f) sample else {
                val magnitude = .9f + .1f * excess / (.1f + excess)
                if (sample < 0f) -magnitude else magnitude
            }
            val quantized = (32768f * limited).roundToInt().coerceIn(-32768, 32767)
            pcm[2 * i] = quantized.toByte()
            pcm[2 * i + 1] = (quantized shr 8).toByte()
        }
        write(pcm, 2 * outCount)
        outPosition += outCount
        active.removeAll { it.end <= position }
    }
}
