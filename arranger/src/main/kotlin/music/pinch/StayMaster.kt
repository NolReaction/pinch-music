package music.pinch

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val StayRate = 32_000
private const val StayCycle = 4_294_967_296.0
private const val StayTableSize = 2048
private val staySine = FloatArray(StayTableSize + 1) { sin(it * (2 * PI / StayTableSize)).toFloat() }
private fun stayFrames(ms: Long) = ((ms * StayRate + 500) / 1000).toInt()
private fun stayHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun stayStep(hz: Double) = (hz * StayCycle / StayRate).toInt()
private fun stayFall(seconds: Double) = exp(-1.0 / (StayRate * seconds)).toFloat()
private fun stayWave(phase: Int): Float {
    val index = phase ushr 21
    val blend = (phase and 0x1fffff) / 2097152f
    return staySine[index] + blend * (staySine[index + 1] - staySine[index])
}

/** A rounded analogue voice: the same body carries the synth motif and the sung-note reduction. */
internal fun stayLeadSamples(note: Note): FloatArray {
    val hz = stayHz(note.midi)
    val gate = stayFrames(note.durationMs)
    val samples = FloatArray(gate + stayFrames(300))
    val pitch = stayStep(hz)
    val upperPitch = stayStep(hz * 1.0018)
    val lowerPitch = stayStep(hz * .9987)
    val attack = 1f - stayFall(.0028)
    val release = stayFall(.057)
    val colourFall = stayFall(.110)
    val bodyFall = stayFall(2.4)
    val allowSecond = hz * 2 < StayRate * .44
    val allowThird = hz * 3 < StayRate * .44
    val allowFifth = hz * 5 < StayRate * .44
    var phase = 0
    var upper = 0x09000000
    var lower = -0x09000000
    var pressure = 0f
    var colour = .45f + .35f * note.velocity
    var body = 1f
    for (i in samples.indices) {
        phase += pitch
        upper += upperPitch
        lower += lowerPitch
        pressure = if (i < gate) pressure + (1f - pressure) * attack else pressure * release
        val center = .70f * stayWave(phase) + .15f * (stayWave(upper) + stayWave(lower))
        val second = if (allowSecond) (.12f + .10f * colour) * stayWave(phase * 2) else 0f
        val third = if (allowThird) (.064f + .065f * colour) * stayWave(phase * 3) else 0f
        val fifth = if (allowFifth) .035f * colour * stayWave(phase * 5) else 0f
        samples[i] = (center + second + third + fifth) * pressure * body * .71f *
            min(1f, (samples.lastIndex - i) / (StayRate * .012f))
        colour *= colourFall
        body *= bodyFall
    }
    return samples
}

internal class StayStudio(private val chart: Chart) {
    private val length = stayFrames(chart.durationMs)
    private val left = FloatArray(length)
    private val right = FloatArray(length)
    private val kickEnvelope = FloatArray(length)

    init {
        val release = stayFall(.062)
        for (note in chart.accompaniment) if (note.voice == BackingVoice.Kick) {
            val start = stayFrames(note.atMs)
            var pressure = note.velocity
            for (i in start until min(length, start + stayFrames(320))) {
                kickEnvelope[i] = maxOf(kickEnvelope[i], pressure * min(1f, (i - start) / 48f))
                pressure *= release
            }
        }
    }

    private fun place(samples: FloatArray, start: Int, gain: Float, pan: Double = 0.0, duck: Float = 0f) {
        val l = (cos((pan + 1) * PI / 4) * gain).toFloat()
        val r = (sin((pan + 1) * PI / 4) * gain).toFloat()
        for (i in 0 until min(samples.size, length - start)) {
            val at = start + i
            val sample = samples[i] * (1f - duck * kickEnvelope[at])
            left[at] += sample * l
            right[at] += sample * r
        }
    }

    fun lead(note: Note) {
        val samples = stayLeadSamples(note)
        val at = stayFrames(note.atMs)
        val gain = .63f * note.velocity
        place(samples, at, gain, -.025, .055f)
        place(samples, at + stayFrames(23), gain * .072f, .60)
        place(samples, at + stayFrames(47), gain * .044f, -.66)
        place(samples, at + stayFrames(83), gain * .022f, .34)
    }

    fun backing(note: BackingNote) {
        val at = stayFrames(note.atMs)
        val gain = note.velocity
        when (note.voice) {
            BackingVoice.Kick -> place(drum(note), at, .71f * gain)
            BackingVoice.Snare -> {
                val samples = drum(note)
                place(samples, at, .46f * gain, -.045)
                place(samples, at + stayFrames(19), .053f * gain, .53)
            }
            BackingVoice.Hat -> place(drum(note), at, .36f * gain, if (note.midi == 70) -.35 else .34)
            BackingVoice.Bass -> place(harmony(note), at, .49f * gain, 0.0, .31f)
            BackingVoice.Pad -> {
                val samples = harmony(note)
                val pan = ((note.midi * 5 % 17) - 8) / 16.0
                place(samples, at, .22f * gain, pan, .17f)
                place(samples, at + stayFrames(31), .028f * gain, -pan, .17f)
            }
            BackingVoice.Keys -> {
                val samples = studioPluckedString(note.midi, note.durationMs, gain)
                val pan = ((note.midi - 64) / 30.0).coerceIn(-.45, .45)
                place(samples, at, .34f * gain, pan, .14f)
                place(samples, at + stayFrames(41), .026f * gain, -pan)
            }
        }
    }

    private fun harmony(note: BackingNote): FloatArray {
        val bass = note.voice == BackingVoice.Bass
        val gate = stayFrames(note.durationMs)
        val samples = FloatArray(gate + stayFrames(if (bass) 95 else 270))
        val pitch = stayStep(stayHz(note.midi))
        val rankPitch = stayStep(stayHz(note.midi) * 1.0027)
        val attack = 1f - stayFall(if (bass) .0030 else .045)
        val release = stayFall(if (bass) .017 else .050)
        val brightnessFall = stayFall(.16)
        var phase = 0
        var rank = 0
        var pressure = 0f
        var brightness = 1f
        for (i in samples.indices) {
            phase += pitch
            rank += rankPitch
            pressure = if (i < gate) pressure + (1f - pressure) * attack else pressure * release
            val tone = if (bass) .82f * stayWave(phase) + .14f * stayWave(phase * 2) +
                .06f * brightness * stayWave(phase * 3)
            else .65f * stayWave(phase) + .24f * stayWave(rank) + .11f * stayWave(phase * 3)
            samples[i] = tone * pressure * min(1f, (samples.lastIndex - i) / 250f)
            brightness *= brightnessFall
        }
        return samples
    }

    private fun drum(note: BackingNote): FloatArray {
        val kick = note.voice == BackingVoice.Kick
        val snare = note.voice == BackingVoice.Snare
        val rim = snare && note.midi == 37
        val openHat = note.voice == BackingVoice.Hat && note.midi == 46
        val gate = if (kick) 250L else if (snare && !rim) 185L else if (openHat) 150L else 90L
        val samples = FloatArray(stayFrames(gate))
        val release = stayFall(if (kick) .082 else if (rim) .021 else if (snare) .039 else if (openHat) .035 else .012)
        val sweepRelease = stayFall(.023)
        val clickRelease = stayFall(.0025)
        var seed = note.atMs.toInt() * 1103515245 + note.midi * 1987
        var phase = 0
        var sweep = stayStep(if (kick) 110.0 else 17.0).toFloat()
        val pitch = stayStep(if (kick) 49.0 else if (rim) 740.0 else 185.0)
        var previous = 0f
        var air = 0f
        var envelope = 1f
        var click = 1f
        for (i in samples.indices) {
            seed = seed * 1664525 + 1013904223
            val white = seed / 2147483648f
            val high = (white - previous) * .5f
            previous = white
            air += .28f * (white - air)
            phase += pitch + sweep.toInt()
            val value = when {
                kick -> .94f * stayWave(phase) + .11f * click * high
                rim -> .58f * stayWave(phase) + .21f * stayWave(phase * 2) + .12f * high * click
                snare -> .67f * air + .37f * high + .22f * stayWave(phase)
                else -> .74f * high + .20f * (white - air)
            }
            samples[i] = value * envelope * min(1f, i / 30f) * min(1f, (samples.lastIndex - i) / 220f)
            envelope *= release
            sweep *= sweepRelease
            click *= clickRelease
        }
        return samples
    }

    fun write(destination: File) {
        destination.outputStream().buffered().use { output ->
            output.write(waveHeader(StayRate, 2, length * 4))
            val bytes = ByteArray(4096 * 4)
            var offset = 0
            fun channel(sample: Float, at: Int, fade: Float) {
                val over = abs(sample) - .72f
                val limited = if (over <= 0f) sample else {
                    val compressed = .72f + .20f * over / (.20f + over)
                    if (sample < 0f) -compressed else compressed
                }
                val word = (limited * .79f * fade * 32767).roundToInt()
                bytes[at] = word.toByte()
                bytes[at + 1] = (word shr 8).toByte()
            }
            for (i in 0 until length) {
                val fade = min(1f, (length - 1 - i) / (StayRate * .18f))
                channel(left[i], offset, fade)
                channel(right[i], offset + 2, fade)
                offset += 4
                if (offset == bytes.size || i == length - 1) {
                    output.write(bytes, 0, offset)
                    offset = 0
                }
            }
        }
    }
}

internal fun writeStayMaster(destination: File, chart: Chart) {
    val studio = StayStudio(chart)
    chart.accompaniment.forEach(studio::backing)
    chart.notes.forEach(studio::lead)
    studio.write(destination)
}
