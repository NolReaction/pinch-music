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
import kotlin.math.sqrt
import music.pinch.core.BackingNote
import music.pinch.core.BackingVoice
import music.pinch.core.Chart
import music.pinch.core.Note

private const val FloorRate = 32_000
private const val Cycle = 4_294_967_296.0
private const val TableSize = 2048
private fun frames(ms: Long) = ((ms * FloorRate + 500) / 1000).toInt()
private fun frequency(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun step(hz: Double) = (hz * Cycle / FloorRate).toInt()
private fun decay(seconds: Double) = exp(-1.0 / (FloorRate * seconds)).toFloat()
private val sine = FloatArray(TableSize + 1) { sin(it * (2 * PI / TableSize)).toFloat() }

private fun sample(table: FloatArray, phase: Int): Float {
    val i = phase ushr 21
    val fraction = (phase and 0x1fffff) / 2097152f
    return table[i] + fraction * (table[i + 1] - table[i])
}

/** One period per pitch and registration. Leave headroom for harmonics and omit partials above Nyquist. */
private fun leadTable(voice: FloorLeadVoice, hz: Double): FloatArray {
    val harmonics = if (voice == FloorLeadVoice.Reed) {
        // The upper octave and fifth carry the accordion hook over the bass and kick.
        doubleArrayOf(.89, 1.04, 1.01, .47, .45, .28, .19, .14, .09, .055)
    } else {
        doubleArrayOf(.79, .76, .60, .37, .27, .15, .09, .04)
    }
    val table = FloatArray(TableSize + 1)
    for ((i, amplitude) in harmonics.withIndex()) {
        val h = i + 1
        if (h * hz >= FloorRate * .465) break
        val phase = .13 * sin(h * 1.72)
        for (t in 0 until TableSize) {
            table[t] += (amplitude * sin(t * (2 * PI * h / TableSize) + phase)).toFloat()
        }
    }
    val peak = table.maxOf { abs(it) }.coerceAtLeast(1f)
    for (t in 0 until TableSize) table[t] /= peak
    table[TableSize] = table[0]
    return table
}

private class FloorStudio(val score: Chart) {
    private val length = frames(score.durationMs)
    private val left = FloatArray(length)
    private val right = FloatArray(length)
    private val kickEnvelope = FloatArray(length)
    private val tables = mutableMapOf<Pair<FloorLeadVoice, Int>, FloatArray>()

    init {
        // Follow the kicks actually written, including the space where the drums drop out.
        val recovery = decay(.060)
        for (note in score.accompaniment) if (note.voice == BackingVoice.Kick) {
            val start = frames(note.atMs)
            var level = note.velocity
            for (i in start until min(length, start + frames(270))) {
                val attack = min(1f, (i - start) / 64f)
                kickEnvelope[i] = maxOf(kickEnvelope[i], level * attack)
                level *= recovery
            }
        }
    }

    private fun place(mono: FloatArray, start: Int, gain: Float, pan: Double, duck: Float = 0f) {
        val count = min(mono.size, length - start)
        val l = (cos((pan + 1) * (PI / 4)) * gain).toFloat()
        val r = (sin((pan + 1) * (PI / 4)) * gain).toFloat()
        for (i in 0 until count) {
            val frame = start + i
            val value = mono[i] * (1f - duck * kickEnvelope[frame])
            left[frame] += l * value
            right[frame] += r * value
        }
    }

    fun lead(note: Note) {
        val voice = floorLeadVoiceAt(note.atMs)
        // Both the calls and the sung melody are interpreted by the same piano.
        if (voice == FloorLeadVoice.Pluck || voice == FloorLeadVoice.Vocal) {
            val mono = piano(note.midi, note.durationMs, note.velocity)
            placePiano(mono, frames(note.atMs), .88f * note.velocity, -.035)
            return
        }
        val hz = frequency(note.midi)
        val tone = tables.getOrPut(voice to note.midi) { leadTable(voice, hz) }
        val gate = frames(note.durationMs)
        val reed = voice == FloorLeadVoice.Reed
        val attackMs = if (reed) 7L else 10L
        val tailMs = 55L
        val mono = FloatArray(gate + frames(tailMs))
        val reedSides = if (reed) FloatArray(mono.size) else null
        val attack = 1f - decay(attackMs * .00040)
        val release = decay(tailMs * .00017)
        val brightnessDecay = decay(.23)
        val scoopDecay = decay(.021)
        val separation = if (reed) 1.0048 else .9970
        val vibratoStep = step(5.67)
        val pitchStep = step(hz)
        var centerPhase = 0
        var rankPhase = step(hz * .09)
        // The two outer reed ranks speak independently. The center rank anchors the pitch on a phone speaker.
        val registrationPhase = ((note.atMs % 1024L).toInt() * 37 + note.midi * 131) shl 22
        if (reedSides != null) rankPhase = registrationPhase
        var lowerPhase = registrationPhase + 0x58000000
        var vibratoPhase = 0
        var pressure = 0f
        var brightness = 1f
        var scoop = .004f
        for (i in mono.indices) {
            pressure = if (i < gate) pressure + attack * (1f - pressure) else pressure * release
            val vibrato = .0030f * min(1f, i / 5300f) * sample(sine, vibratoPhase)
            val delta = (pitchStep * (1.0 + vibrato - scoop)).toInt()
            centerPhase += delta
            rankPhase += (delta * separation).toInt()
            vibratoPhase += vibratoStep
            scoop *= scoopDecay
            val main = sample(tone, centerPhase)
            val rank = sample(tone, rankPhase)
            val fundamental = sample(sine, centerPhase)
            val colour = if (reed) {
                lowerPhase += (delta * .9956).toInt()
                val lower = sample(tone, lowerPhase)
                reedSides?.set(i, .43f * (rank - lower) * pressure)
                .65f * main + .175f * (rank + lower)
            } else {
                (.70f * main + .30f * rank) * (1f - .28f * brightness) +
                    fundamental * (.28f * brightness)
            }
            val end = min(1f, (mono.lastIndex - i) / (FloorRate * .006f))
            mono[i] = colour * pressure * end
            if (reedSides != null) reedSides[i] *= end
            brightness *= brightnessDecay
        }
        val start = frames(note.atMs)
        val gain = note.velocity * if (reed) .69f else .57f
        place(mono, start, gain, -.035)
        if (reedSides != null) {
            place(reedSides, start, gain, -1.0)
            place(reedSides, start, -gain, 1.0)
        }
        // Early reflections carry the sustained line out to the sides. Leave the attack centered.
        place(mono, start + frames(17), gain * .115f, .78)
        place(mono, start + frames(29), gain * .082f, -.74)
        place(mono, start + frames(51), gain * .048f, .35)
    }

    fun backing(note: BackingNote) {
        val voice = note.voice
        val gate = frames(note.durationMs)
        val tailMs = when (voice) {
            BackingVoice.Kick -> 110L
            BackingVoice.Snare -> 105L
            BackingVoice.Hat -> 32L
            BackingVoice.Pad -> 135L
            BackingVoice.Keys -> 720L
            BackingVoice.Bass -> 30L
        }
        val size = gate + frames(tailMs)
        val mono = when (voice) {
            BackingVoice.Kick, BackingVoice.Snare, BackingVoice.Hat -> drum(note, size)
            BackingVoice.Keys -> piano(note.midi, note.durationMs, note.velocity)
            else -> harmony(note, size)
        }
        val start = frames(note.atMs)
        when (voice) {
            BackingVoice.Kick -> place(mono, start, .75f * note.velocity, 0.0)
            BackingVoice.Snare -> {
                place(mono, start, .52f * note.velocity, -.06)
                place(mono, start + frames(18), .085f * note.velocity, .54)
            }
            BackingVoice.Hat -> place(mono, start, .48f * note.velocity, if (note.midi == 70) -.38 else .31)
            BackingVoice.Bass -> place(mono, start, .52f * note.velocity, 0.0, .40f)
            BackingVoice.Keys -> {
                val pan = ((note.midi - 60) / 36.0).coerceIn(-.55, .55)
                placePiano(mono, start, .57f * note.velocity, pan)
            }
            BackingVoice.Pad -> {
                val pan = ((note.midi * 7 % 17) - 8) / 13.0
                val gain = .28f * note.velocity
                place(mono, start, gain, pan, .31f)
                place(mono, start + frames(22), gain * .14f, -pan, .31f)
            }
        }
    }

    /** A struck string loses its bright overtones first; the damper then closes over the body.
     * Each partial has its own slightly stiff frequency and two nearly unison strings.
     * This keeps a short written articulation from chopping the waveform at the next key. */
    private fun piano(midi: Int, durationMs: Long, velocity: Float): FloatArray {
        val gate = frames(durationMs)
        val tail = frames(720)
        val mono = FloatArray(gate + tail)
        val hz = frequency(midi)
        val stiffness = .000035 * 2.0.pow((midi - 60) / 18.0)
        val fundamentalCorrection = sqrt(1.0 + stiffness)
        val registerDecay = 1.85 * 2.0.pow((60 - midi) / 28.0)
        val weights = doubleArrayOf(.92, .62, .37, .21, .15, .080, .065, .045, .025, .024, .018, .012)
        for ((index, weight) in weights.withIndex()) {
            val partial = index + 1
            val partialHz = hz * partial * sqrt(1.0 + stiffness * partial * partial) / fundamentalCorrection
            if (partialHz >= FloorRate * .46) break
            val inc = step(partialHz)
            val unisonInc = step(partialHz * 1.00064)
            val phaseOffset = (.18 * sin(partial * 1.43) * Cycle / (2 * PI)).toInt()
            var phase = phaseOffset
            var unisonPhase = phaseOffset
            val brightness = (.62 + .65 * velocity).pow((partial - 1) * .52)
            val amplitude = (weight * brightness / 1.72).toFloat()
            var body = amplitude * .76f
            var hammer = amplitude * .24f
            val bodyDecay = decay(registerDecay / partial.toDouble().pow(.68))
            val hammerDecay = decay(.075 / partial.toDouble().pow(.31))
            for (i in mono.indices) {
                phase += inc
                unisonPhase += unisonInc
                mono[i] += (.58f * sample(sine, phase) + .42f * sample(sine, unisonPhase)) * (body + hammer)
                body *= bodyDecay
                hammer *= hammerDecay
            }
        }
        val attack = 1f - decay(.0017)
        val damper = decay(.120)
        var open = 0f
        var release = 1f
        for (i in mono.indices) {
            open += (1f - open) * attack
            if (i >= gate) release *= damper
            // The residual is already below -50 dB. Bring the last 10 ms precisely to zero.
            val end = min(1f, (mono.lastIndex - i) / (FloorRate * .010f))
            mono[i] *= open * release * end
        }
        return mono
    }

    private fun placePiano(mono: FloatArray, start: Int, gain: Float, pan: Double) {
        place(mono, start, gain, pan, .12f)
        // A few quiet, decorrelated room returns preserve the attack and connect the releases.
        place(mono, start + frames(37), gain * .075f, .65)
        place(mono, start + frames(61), gain * .055f, -.61)
        place(mono, start + frames(97), gain * .032f, -.25)
        place(mono, start + frames(139), gain * .020f, .29)
    }

    private fun harmony(note: BackingNote, size: Int): FloatArray {
        val kind = note.voice
        val gate = frames(note.durationMs)
        val inc = step(frequency(note.midi))
        val rankInc = (inc * 1.0039).toInt()
        val attack = 1f - decay(if (kind == BackingVoice.Pad) .052 else .0035)
        val release = decay(when (kind) {
            BackingVoice.Pad -> .022
            else -> .005
        })
        val damping = decay(if (kind == BackingVoice.Bass) .095 else .14)
        var phase = 0
        var rank = 0
        var pressure = 0f
        var bright = 1f
        return FloatArray(size) { i ->
            phase += inc
            rank += rankInc
            pressure = if (i < gate) pressure + (1f - pressure) * attack else pressure * release
            val f = sample(sine, phase)
            val sample = when (kind) {
                BackingVoice.Bass -> .77f * f + (.16f + .08f * bright) * sample(sine, phase * 2) +
                    .095f * bright * sample(sine, phase * 3)
                else -> .51f * f + .32f * sample(sine, rank) + .17f * sample(sine, phase * 3)
            }
            bright *= damping
            pressure * sample
        }
    }

    private fun drum(note: BackingNote, size: Int): FloatArray {
        val kind = note.voice
        var random = note.atMs.toInt() * 1103515245 + note.midi
        val rim = kind == BackingVoice.Snare && note.midi == 37
        val shaker = kind == BackingVoice.Hat && note.midi == 70
        val openHat = kind == BackingVoice.Hat && note.midi == 46
        val duration = when (kind) {
            BackingVoice.Kick -> .103
            BackingVoice.Snare -> if (rim) .022 else .054
            else -> when {
                openHat -> .059
                shaker -> .037
                else -> .018
            }
        }
        val fall = decay(duration)
        val sweepFall = decay(.028)
        val clickFall = decay(.003)
        val clapFall = decay(.006)
        val clapTwo = frames(11)
        val clapThree = frames(22)
        var phase = 0
        var air = 0f
        var previous = 0f
        var level = 1f
        var attackClick = 1f
        var slap = 1f
        var sweep = step(121.0).toFloat()
        val base = when {
            kind == BackingVoice.Kick -> step(48.0)
            rim -> step(760.0)
            else -> step(184.0)
        }
        val metalStep = step(6561.0)
        var metal = 0
        return FloatArray(size) { i ->
            random = random * 1664525 + 1013904223
            val white = random / 2147483648f
            val high = (white - previous) * .5f
            previous = white
            air += .42f * (white - air)
            phase += base + sweep.toInt()
            val sound = when (kind) {
                BackingVoice.Kick -> sample(sine, phase) + .22f * attackClick * high
                BackingVoice.Snare -> {
                    if (rim) .60f * sample(sine, phase) + .28f * sample(sine, phase * 2) +
                        .24f * attackClick * high
                    else {
                        if (i == clapTwo || i == clapThree) slap = .88f
                        (air * .78f + high * .52f) * (.58f + .68f * slap) + .23f * sample(sine, phase)
                    }
                }
                else -> if (shaker) (.58f * high + .39f * air) * min(1f, i / 180f)
                    else (.90f * high + .17f * (white - air)) * (.80f + .20f * sample(sine, metal))
            }
            val end = min(1f, (size - 1 - i) / 240f)
            val out = sound * level * min(1f, i / 30f) * end
            metal += metalStep
            sweep *= sweepFall
            level *= fall
            attackClick *= clickFall
            slap *= clapFall
            out
        }
    }

    fun write(destination: File) {
        destination.outputStream().buffered().use { output ->
            output.write(waveHeader(FloorRate, 2, length * 4))
            val bytes = ByteArray(4 * 4096)
            var offset = 0
            fun channel(value: Float, byte: Int, fade: Float) {
                val over = abs(value) - .73f
                val limited = if (over <= 0f) value else {
                    val compressed = .73f + .20f * over / (.20f + over)
                    if (value < 0f) -compressed else compressed
                }
                // Match the catalog's Stereo Love master at about -14 LUFS and leave room for AAC peaks.
                val word = (limited * .80f * fade * 32767).roundToInt()
                bytes[byte] = word.toByte()
                bytes[byte + 1] = (word shr 8).toByte()
            }
            for (i in 0 until length) {
                val fade = min(1f, (length - 1 - i) / (FloorRate * .16f))
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

internal fun writeOnTheFloorMaster(destination: File, chart: Chart) {
    val studio = FloorStudio(chart)
    for (note in chart.accompaniment) studio.backing(note)
    for (note in chart.notes) studio.lead(note)
    studio.write(destination)
}
