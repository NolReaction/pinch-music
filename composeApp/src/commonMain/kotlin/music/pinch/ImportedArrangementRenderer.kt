package music.pinch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import music.pinch.core.BackingVoice
import music.pinch.core.Chart

private const val ImportRate = 22_050
private const val ImportBlock = 2048
private const val ImportPhaseScale = 2147483648.0
private val importSine = FloatArray(2048) { sin(2 * PI * it / 2048).toFloat() }
private fun importWave(phase: Int) = importSine[(phase ushr 20) and 2047]
private fun importFrames(ms: Long) = (ms * ImportRate + 500) / 1000

/** A compact, deterministic instrument rack. A null voice is the warm electric-mallet lead.
 * No recording samples enter this renderer, so reconstructed music contains no original voice. */
private class ImportTone(
    atMs: Long, midi: Int, durationMs: Long, velocity: Float,
    private val voice: BackingVoice?, private val instrument: Instrument,
) {
    val start = importFrames(atMs)
    private val gate = importFrames(durationMs).coerceAtLeast(1)
    private val release = when (voice) {
        null -> when (instrument) { Instrument.Piano -> 2646; Instrument.Mallet -> 1985; Instrument.Synth -> 3308 }
        BackingVoice.Pad -> 3969
        BackingVoice.Bass -> 1103
        else -> 441
    }
    val end = start + gate + release
    private val attack = when (voice) {
        null -> when (instrument) { Instrument.Piano -> 110; Instrument.Mallet -> 154; Instrument.Synth -> 330 }
        BackingVoice.Pad -> 1985
        BackingVoice.Bass -> 176
        else -> 40
    }
    private val gain = velocity * when (voice) {
        null -> .43f
        BackingVoice.Bass -> .29f
        BackingVoice.Pad -> .23f
        BackingVoice.Kick -> .30f
        BackingVoice.Snare -> .19f
        BackingVoice.Hat -> .13f
        BackingVoice.Keys -> .26f
    }
    private val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private val step = (hz * ImportPhaseScale / ImportRate).toInt()
    private val shimmerStep = (hz * (if (instrument == Instrument.Synth && voice == null) 1.003 else 2.003) *
        ImportPhaseScale / ImportRate).toInt()
    private val upper = if (hz * 3 < ImportRate * .43) 1f else 0f
    private var phase = 0
    private var shimmer = 0
    private var noiseState = (atMs.toInt() xor (midi shl 10)) + 173
    private var noiseLow = 0f
    private var previousNoise = 0f
    private var level = 1f
    private var brightness = .08f + .14f * velocity
    private var sweep = (95 * ImportPhaseScale / ImportRate).toFloat()
    private val decay = when (voice) {
        null -> when (instrument) { Instrument.Piano -> .99996f; Instrument.Mallet -> .99987f; Instrument.Synth -> .999985f }
        BackingVoice.Keys -> .99994f
        BackingVoice.Kick -> .9991f
        BackingVoice.Snare -> .9984f
        BackingVoice.Hat -> .9968f
        else -> 1f
    }

    fun mix(buffer: FloatArray, from: Int, to: Int, initialAge: Long) {
        var age = initialAge
        for (index in from until to) {
            val rise = min(1f, age.toFloat() / attack)
            val fall = min(1f, (gate + release - age).toFloat() / release).coerceAtLeast(0f)
            // Smooth endpoints remove clicks without blunting the marble's note onset.
            val envelope = rise * rise * (3 - 2 * rise) * fall * fall * (3 - 2 * fall)
            val value = when (voice) {
                null, BackingVoice.Keys -> .79f * importWave(phase) +
                    upper * (brightness * importWave(shimmer) + .055f * level * importWave(phase * 3))
                BackingVoice.Bass -> .90f * importWave(phase) + .10f * upper * importWave(phase * 2)
                BackingVoice.Pad -> .86f * importWave(phase) + .14f * upper * importWave(shimmer)
                BackingVoice.Kick -> importWave(phase)
                BackingVoice.Snare, BackingVoice.Hat -> {
                    noiseState = noiseState * 1664525 + 1013904223
                    val noise = noiseState.toFloat() / 2147483648f
                    val high = (noise - previousNoise) * .5f
                    previousNoise = noise
                    noiseLow += .22f * (noise - noiseLow)
                    if (voice == BackingVoice.Hat) high * .60f else noiseLow * .66f + importWave(phase) * .18f
                }
            }
            buffer[index] += value * gain * envelope * level
            phase += if (voice == BackingVoice.Kick) (46 * ImportPhaseScale / ImportRate).toInt() + sweep.toInt() else step
            shimmer += shimmerStep
            sweep *= .9965f
            level *= decay
            brightness *= .99965f
            age++
        }
    }
}

/** Return a complete local reconstruction, with chart time zero equal to sample zero.
 * Called on a worker dispatcher; cancellation is checked each block. */
internal suspend fun renderImportedArrangement(
    chart: Chart, instrument: Instrument = Instrument.Mallet,
): ImportRecording {
    require(chart.durationMs <= 30_000) { "Для аранжировки выбери фрагмент до 30 секунд." }
    val total = importFrames(chart.durationMs)
    val events = (chart.notes.map { ImportTone(it.atMs, it.midi, it.durationMs, it.velocity, null, instrument) } +
        chart.accompaniment.map { ImportTone(it.atMs, it.midi, it.durationMs, it.velocity, it.voice, instrument) }).sortedBy { it.start }
    val active = mutableListOf<ImportTone>()
    val dry = FloatArray(ImportBlock)
    val pcm = ByteArray(total.toInt() * 2)
    // Quiet damped room reflections glue the lead and percussion together without an echo train.
    val roomA = FloatArray(1039)
    val roomB = FloatArray(1481)
    var lowA = 0f
    var lowB = 0f
    var nextEvent = 0
    var position = 0L
    while (position < total) {
        currentCoroutineContext().ensureActive()
        val count = min(ImportBlock.toLong(), total - position).toInt()
        dry.fill(0f, 0, count)
        while (nextEvent < events.size && events[nextEvent].start < position + count) active += events[nextEvent++]
        for (tone in active) {
            val begin = maxOf(position, tone.start)
            val end = minOf(position + count, tone.end)
            if (begin < end) tone.mix(dry, (begin - position).toInt(), (end - position).toInt(), begin - tone.start)
        }
        for (i in 0 until count) {
            val frame = position + i
            val a = (frame % roomA.size).toInt()
            val b = (frame % roomB.size).toInt()
            lowA += .26f * (roomA[a] - lowA)
            lowB += .21f * (roomB[b] - lowB)
            roomA[a] = dry[i] * .22f + lowA * .24f
            roomB[b] = dry[i] * .17f + lowB * .20f
            val mixed = dry[i] + .20f * lowA + .17f * lowB
            val fade = ((total - 1 - frame).toFloat() / 882).coerceIn(0f, 1f)
            val sample = mixed * .94f / (1 + .30f * abs(mixed)) * fade
            val quantized = (sample * 32767).roundToInt().coerceIn(-32768, 32767)
            val offset = frame.toInt() * 2
            pcm[offset] = quantized.toByte()
            pcm[offset + 1] = (quantized shr 8).toByte()
        }
        position += count
        active.removeAll { it.end <= position }
    }
    return ImportRecording(ImportRate, 1, pcm)
}
