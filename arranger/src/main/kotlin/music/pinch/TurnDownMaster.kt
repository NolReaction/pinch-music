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

private const val TurnRate = 32_000
private const val TurnCycle = 4_294_967_296.0
private const val TurnTableSize = 2048
private val turnSine = FloatArray(TurnTableSize + 1) { sin(it * 2 * PI / TurnTableSize).toFloat() }
private fun turnFrames(ms: Long) = ((ms * TurnRate + 500) / 1000).toInt()
private fun turnHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun turnStep(hz: Double) = (hz * TurnCycle / TurnRate).toInt()
private fun turnDecay(seconds: Double) = exp(-1.0 / (TurnRate * seconds)).toFloat()
private fun turnWave(phase: Int): Float {
    val index = phase ushr 21
    val fraction = (phase and 0x1fffff) / 2097152f
    return turnSine[index] + fraction * (turnSine[index + 1] - turnSine[index])
}
private fun turnEnd(index: Int, size: Int) = min(1f, (size - 1 - index) / (TurnRate * .009f))

/** Rounded, slightly nasal electronic reed. The same voice connects calls and drops. */
internal fun turnDownLeadSamples(note: Note, energy: Float = .85f): FloatArray {
    val gate = turnFrames(note.durationMs)
    val result = FloatArray(gate + turnFrames(160))
    val hz = turnHz(note.midi)
    val inc = turnStep(hz)
    val attack = 1f - turnDecay(.0028)
    val release = turnDecay(.032)
    val colourFall = turnDecay(.105)
    val scoopFall = turnDecay(.021)
    val velocity = note.velocity.coerceIn(0f, 1f)
    val bodyFall = turnDecay(.70)
    var phase = 0
    var airPhase = 0
    var envelope = 0f
    var body = 1f
    var colour = (.52f + .34f * velocity) * energy.coerceIn(.2f, 1f)
    var scoop = .018f
    val airStep = turnStep(5.3)
    for (i in result.indices) {
        val vibrato = .0011f * min(1f, i / (TurnRate * .16f)) * turnWave(airPhase)
        phase += (inc * (1.0 + scoop + vibrato)).toInt()
        airPhase += airStep
        envelope = if (i < gate) envelope + (1f - envelope) * attack else envelope * release
        var tone = .77f * turnWave(phase)
        if (hz * 2 < TurnRate * .42) tone += (.10f + .12f * colour) * turnWave(phase * 2)
        if (hz * 3 < TurnRate * .42) tone += .16f * colour * turnWave(phase * 3)
        if (hz * 4 < TurnRate * .42) tone += .055f * colour * turnWave(phase * 4)
        if (hz * 5 < TurnRate * .42) tone += .048f * colour * turnWave(phase * 5)
        result[i] = tone * envelope * (.66f + .34f * body) * turnEnd(i, result.size) * .80f
        body *= bodyFall
        colour *= colourFall
        scoop *= scoopFall
    }
    return result
}

private class TurnDownStudio(private val chart: Chart) {
    private val length = turnFrames(chart.durationMs)
    private val left = FloatArray(length)
    private val right = FloatArray(length)
    private val kicks = FloatArray(length)

    init {
        val recovery = turnDecay(.080)
        for (note in chart.accompaniment) if (note.voice == BackingVoice.Kick) {
            val first = turnFrames(note.atMs)
            var level = note.velocity
            for (i in first until min(length, first + turnFrames(420))) {
                kicks[i] = maxOf(kicks[i], level * min(1f, (i - first) / 48f))
                level *= recovery
            }
        }
    }

    private fun place(mono: FloatArray, atMs: Long, gain: Float, pan: Double = 0.0, duck: Float = 0f) {
        val start = turnFrames(atMs)
        val l = (cos((pan + 1) * PI / 4) * gain).toFloat()
        val r = (sin((pan + 1) * PI / 4) * gain).toFloat()
        for (i in 0 until min(mono.size, length - start)) {
            val sample = mono[i] * (1f - duck * kicks[start + i])
            left[start + i] += sample * l
            right[start + i] += sample * r
        }
    }

    fun lead(note: Note) {
        val energy = when (turnDownPartAt(note.atMs)) {
            TurnDownPart.Intro, TurnDownPart.Break -> .54f
            TurnDownPart.Build -> .73f
            else -> .91f
        }
        val mono = turnDownLeadSamples(note, energy)
        val gain = .70f * note.velocity
        place(mono, note.atMs, gain, -.025, .12f)
        // Short reflections leave each written attack at the centre and do not add scored notes.
        place(mono, note.atMs + 23, gain * .075f, .66)
        place(mono, note.atMs + 47, gain * .040f, -.58)
    }

    fun backing(note: BackingNote) {
        when (note.voice) {
            BackingVoice.Kick -> place(drum(note), note.atMs, .76f * note.velocity)
            BackingVoice.Snare -> {
                val mono = drum(note)
                place(mono, note.atMs, .53f * note.velocity, -.08)
                place(mono, note.atMs + 19, .044f * note.velocity, .55)
            }
            BackingVoice.Hat -> place(drum(note), note.atMs, .34f * note.velocity,
                if (note.midi == 70) -.39 else .32)
            BackingVoice.Bass -> place(bass(note), note.atMs, .56f * note.velocity, duck = .34f)
            BackingVoice.Keys -> {
                val mono = studioMallet(note.midi, note.durationMs, note.velocity)
                place(mono, note.atMs, .35f * note.velocity, .23, .16f)
                place(mono, note.atMs + 39, .030f * note.velocity, -.48, .16f)
            }
            BackingVoice.Pad -> {
                val mono = pad(note)
                val pan = ((note.midi % 7) - 3) / 7.0
                place(mono, note.atMs, .19f * note.velocity, pan, .23f)
                place(mono, note.atMs + 31, .035f * note.velocity, -pan, .23f)
            }
        }
    }

    private fun bass(note: BackingNote): FloatArray {
        val gate = turnFrames(note.durationMs)
        val result = FloatArray(gate + turnFrames(120))
        val inc = turnStep(turnHz(note.midi))
        val attack = 1f - turnDecay(.0045)
        val release = turnDecay(.024)
        val damping = turnDecay(.22)
        var phase = 0
        var envelope = 0f
        var brightness = 1f
        for (i in result.indices) {
            phase += inc
            envelope = if (i < gate) envelope + (1f - envelope) * attack else envelope * release
            // The octave carries the bass on a phone, without subsonic energy or hard saturation.
            val tone = .77f * turnWave(phase) + .21f * turnWave(phase * 2) +
                .075f * brightness * turnWave(phase * 3)
            result[i] = tone * envelope * turnEnd(i, result.size)
            brightness *= damping
        }
        return result
    }

    private fun pad(note: BackingNote): FloatArray {
        val gate = turnFrames(note.durationMs)
        val result = FloatArray(gate + turnFrames(360))
        val inc = turnStep(turnHz(note.midi))
        val detuned = (inc * 1.0018).toInt()
        val attack = 1f - turnDecay(.045)
        val release = turnDecay(.070)
        var phase = 0
        var other = 0
        var envelope = 0f
        for (i in result.indices) {
            phase += inc
            other += detuned
            envelope = if (i < gate) envelope + (1f - envelope) * attack else envelope * release
            result[i] = (.70f * turnWave(phase) + .25f * turnWave(other) + .05f * turnWave(phase * 2)) *
                envelope * turnEnd(i, result.size)
        }
        return result
    }

    private fun drum(note: BackingNote): FloatArray {
        val kick = note.voice == BackingVoice.Kick
        val snare = note.voice == BackingVoice.Snare
        val rim = snare && note.midi == 37
        val open = note.voice == BackingVoice.Hat && note.midi == 46
        val shaker = note.voice == BackingVoice.Hat && note.midi == 70
        val size = turnFrames(note.durationMs + if (kick) 130 else if (snare) 160 else 55)
        val result = FloatArray(size)
        val fall = turnDecay(if (kick) .118 else if (snare) (if (rim) .029 else .066)
            else if (open) .070 else if (shaker) .035 else .020)
        val sweepFall = turnDecay(.020)
        val noiseFall = turnDecay(if (kick) .007 else .045)
        var random = note.atMs.toInt() * 1103515245 + note.midi * 7919 + 193
        var phase = 0
        val base = turnStep(if (kick) 54.0 else if (rim) 590.0 else 190.0)
        var sweep = turnStep(if (kick) 104.0 else 22.0).toFloat()
        var level = 1f
        var click = 1f
        var low = 0f
        var smooth = 0f
        for (i in result.indices) {
            random = random * 1664525 + 1013904223
            val noise = ((random ushr 8) / 8_388_607.5 - 1.0).toFloat()
            low += .12f * (noise - low)
            smooth += .47f * (noise - smooth)
            val air = smooth - low
            phase += base + sweep.toInt()
            val sound = when {
                kick -> .94f * turnWave(phase) + .10f * click * air
                rim -> .56f * turnWave(phase) + .23f * turnWave(phase * 2) + .19f * air
                snare -> .68f * air + .22f * smooth + .25f * turnWave(phase)
                else -> air * if (shaker) .80f else .95f
            }
            result[i] = sound * level * min(1f, i / 40f) * turnEnd(i, result.size)
            sweep *= sweepFall
            level *= fall
            click *= noiseFall
        }
        return result
    }

    fun write(destination: File) {
        destination.outputStream().buffered().use { output ->
            output.write(waveHeader(TurnRate, 2, length * 4))
            val bytes = ByteArray(4096 * 4)
            var offset = 0
            fun channel(sample: Float, byte: Int, fade: Float) {
                val over = abs(sample) - .74f
                val limited = if (over <= 0f) sample else {
                    val value = .74f + .20f * over / (.20f + over)
                    if (sample < 0f) -value else value
                }
                val word = (limited * .78f * fade * 32767).roundToInt()
                bytes[byte] = word.toByte()
                bytes[byte + 1] = (word shr 8).toByte()
            }
            for (i in 0 until length) {
                val fade = min(1f, (length - 1 - i) / (TurnRate * .18f))
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

internal fun writeTurnDownMaster(destination: File, chart: Chart) {
    val studio = TurnDownStudio(chart)
    chart.accompaniment.forEach(studio::backing)
    chart.notes.forEach(studio::lead)
    studio.write(destination)
}
