package music.pinch

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val MaterialCycle = 4_294_967_296.0
private const val MaterialTableSize = 2048
private val materialSine = FloatArray(MaterialTableSize + 1) { sin(it * 2 * PI / MaterialTableSize).toFloat() }
private fun materialWave(phase: Int): Float {
    val index = phase ushr 21
    val blend = (phase and 0x1fffff) / 2097152f
    return materialSine[index] + blend * (materialSine[index + 1] - materialSine[index])
}
private fun materialStep(hz: Double, rate: Int) = (hz * MaterialCycle / rate).toInt()
private fun materialDecay(seconds: Double, rate: Int) = exp(-1.0 / (rate * seconds)).toFloat()
private fun materialHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun materialFrames(ms: Long, rate: Int) = ((ms * rate + 500) / 1000).toInt()

private fun materialBuffer(midi: Int, durationMs: Long, rate: Int, tailMs: Long): FloatArray {
    require(midi in 0..127 && durationMs in 1..30_000 && rate in 8_000..96_000)
    return FloatArray(materialFrames(durationMs + tailMs, rate))
}

/** A tine's short metallic strike settles into a warm fundamental and lightly beating pickup. */
internal fun studioElectricPiano(midi: Int, durationMs: Long, velocity: Float, rate: Int = 32_000): FloatArray {
    val result = materialBuffer(midi, durationMs, rate, 750)
    val hz = materialHz(midi)
    if (hz >= rate * .46) return result
    val gate = materialFrames(durationMs, rate)
    val inc = materialStep(hz, rate)
    val companion = materialStep(hz * 1.0009, rate)
    val tineInc = materialStep(hz * 6.99, rate)
    val bodyFall = materialDecay(1.65 * 2.0.pow((60 - midi) / 36.0), rate)
    val tineFall = materialDecay(.075, rate)
    val colourFall = materialDecay(.15, rate)
    val attack = 1f - materialDecay(.0024, rate)
    val releaseFall = materialDecay(.115, rate)
    var phase = 0
    var outer = 0
    var tine = 0
    var body = 1f
    var metal = 1f
    var colour = (.22f + .32f * velocity.coerceIn(0f, 1f))
    var opening = 0f
    var release = 1f
    val allowSecond = hz * 2 < rate * .43
    val allowFm = hz * 5 < rate * .43
    val allowTine = hz * 6.99 < rate * .43
    for (i in result.indices) {
        phase += inc
        outer += companion
        tine += tineInc
        opening += (1f - opening) * attack
        if (i >= gate) release *= releaseFall
        val modulation = if (allowFm) (materialWave(phase * 2) * colour * MaterialCycle / (2 * PI)).toInt() else 0
        val pickup = .74f * materialWave(phase + modulation) + .26f * materialWave(outer)
        val second = if (allowSecond) .075f * body * materialWave(phase * 2) else 0f
        val strike = if (allowTine) .065f * metal * materialWave(tine) else 0f
        val end = min(1f, (result.lastIndex - i) / (rate * .012f))
        result[i] = (pickup * body + second + strike) * opening * release * end * .76f
        body *= bodyFall
        metal *= tineFall
        colour *= colourFall
    }
    return result
}

/** A rosewood bar: dark tuned body, with higher flexural modes disappearing after the strike. */
internal fun studioMallet(midi: Int, durationMs: Long, velocity: Float, rate: Int = 32_000): FloatArray {
    val result = materialBuffer(midi, durationMs, rate, 430)
    val hz = materialHz(midi)
    val gate = materialFrames(durationMs, rate)
    val decayScale = 2.0.pow((60 - midi) / 40.0)
    val modes = listOf(Triple(1.0, .83, .48 * decayScale), Triple(4.01, .16, .055), Triple(9.98, .032, .018))
    for ((ratio, weight, seconds) in modes) {
        if (hz * ratio >= rate * .44) continue
        val inc = materialStep(hz * ratio, rate)
        val fall = materialDecay(seconds, rate)
        var phase = 0
        var amplitude = (weight * if (ratio == 1.0) 1.0 else .55 + .45 * velocity.coerceIn(0f, 1f)).toFloat()
        for (i in result.indices) {
            phase += inc
            result[i] += materialWave(phase) * amplitude
            amplitude *= fall
        }
    }
    val attack = 1f - materialDecay(.0019, rate)
    val damper = materialDecay(.070, rate)
    var opening = 0f
    var release = 1f
    for (i in result.indices) {
        opening += (1f - opening) * attack
        if (i >= gate) release *= damper
        result[i] *= opening * release * min(1f, (result.lastIndex - i) / (rate * .010f))
    }
    return result
}

/** A finger-plucked nylon string. The pluck position removes selected harmonics naturally. */
internal fun studioPluckedString(midi: Int, durationMs: Long, velocity: Float, rate: Int = 32_000): FloatArray {
    val result = materialBuffer(midi, durationMs, rate, 560)
    val hz = materialHz(midi)
    val gate = materialFrames(durationMs, rate)
    val pluckPosition = .225 + .018 * (1 - velocity.coerceIn(0f, 1f))
    val register = 1.48 * 2.0.pow((60 - midi) / 36.0)
    for (harmonic in 1..12) {
        if (hz * harmonic >= rate * .44) break
        val inc = materialStep(hz * harmonic, rate)
        var phase = 0
        var amplitude = (sin(PI * harmonic * pluckPosition) / harmonic.toDouble().pow(1.24) * .74).toFloat()
        val fall = materialDecay(register / harmonic.toDouble().pow(.92), rate)
        for (i in result.indices) {
            phase += inc
            result[i] += materialWave(phase) * amplitude
            amplitude *= fall
        }
    }
    val attack = 1f - materialDecay(.0018, rate)
    val damper = materialDecay(.086, rate)
    var opening = 0f
    var release = 1f
    for (i in result.indices) {
        opening += (1f - opening) * attack
        if (i >= gate) release *= damper
        result[i] *= opening * release * min(1f, (result.lastIndex - i) / (rate * .012f))
    }
    return result
}
