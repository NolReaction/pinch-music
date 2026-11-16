package music.pinch

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val PianoRate = 32_000
private const val PianoCycle = 4_294_967_296.0
private const val PianoTableSize = 2048
private val pianoSine = FloatArray(PianoTableSize + 1) { sin(it * (2 * PI / PianoTableSize)).toFloat() }
private fun pianoFrames(ms: Long) = ((ms * PianoRate + 500) / 1000).toInt()
private fun pianoFrequency(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun pianoStep(hz: Double) = (hz * PianoCycle / PianoRate).toInt()
private fun pianoDecay(seconds: Double) = exp(-1.0 / (PianoRate * seconds)).toFloat()

private fun pianoSample(table: FloatArray, phase: Int): Float {
    val i = phase ushr 21
    val fraction = (phase and 0x1fffff) / 2097152f
    return table[i] + fraction * (table[i + 1] - table[i])
}

/** A struck string loses its bright overtones first; the damper then closes over the body.
 * Each partial has its own slightly stiff frequency and two nearly unison strings.
 * This keeps a short written articulation from chopping the waveform at the next key. */
internal fun studioPiano(midi: Int, durationMs: Long, velocity: Float): FloatArray {
    val gate = pianoFrames(durationMs)
    val tail = pianoFrames(720)
    val mono = FloatArray(gate + tail)
    val hz = pianoFrequency(midi)
    val stiffness = .000035 * 2.0.pow((midi - 60) / 18.0)
    val fundamentalCorrection = sqrt(1.0 + stiffness)
    val registerDecay = 1.85 * 2.0.pow((60 - midi) / 28.0)
    val weights = doubleArrayOf(.92, .62, .37, .21, .15, .080, .065, .045, .025, .024, .018, .012)
    for ((index, weight) in weights.withIndex()) {
        val partial = index + 1
        val partialHz = hz * partial * sqrt(1.0 + stiffness * partial * partial) / fundamentalCorrection
        if (partialHz >= PianoRate * .46) break
        val inc = pianoStep(partialHz)
        val unisonInc = pianoStep(partialHz * 1.00064)
        val phaseOffset = (.18 * sin(partial * 1.43) * PianoCycle / (2 * PI)).toInt()
        var phase = phaseOffset
        var unisonPhase = phaseOffset
        val brightness = (.62 + .65 * velocity).pow((partial - 1) * .52)
        val amplitude = (weight * brightness / 1.72).toFloat()
        var body = amplitude * .76f
        var hammer = amplitude * .24f
        val bodyDecay = pianoDecay(registerDecay / partial.toDouble().pow(.68))
        val hammerDecay = pianoDecay(.075 / partial.toDouble().pow(.31))
        for (i in mono.indices) {
            phase += inc
            unisonPhase += unisonInc
            mono[i] += (.58f * pianoSample(pianoSine, phase) + .42f * pianoSample(pianoSine, unisonPhase)) * (body + hammer)
            body *= bodyDecay
            hammer *= hammerDecay
        }
    }
    val attack = 1f - pianoDecay(.0017)
    val damper = pianoDecay(.120)
    var open = 0f
    var release = 1f
    for (i in mono.indices) {
        open += (1f - open) * attack
        if (i >= gate) release *= damper
        // The residual is already below -50 dB. Bring the last 10 ms precisely to zero.
        val end = min(1f, (mono.lastIndex - i) / (PianoRate * .010f))
        mono[i] *= open * release * end
    }
    return mono
}

