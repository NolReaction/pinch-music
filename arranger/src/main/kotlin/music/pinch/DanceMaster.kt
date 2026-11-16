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

internal enum class DanceStyle { GypsyWoman, Levels }
internal enum class DanceLeadVoice { OrganStab, ElectricPiano, Mallet, PluckedString, Piano, Synth }

internal fun danceLeadVoice(style: DanceStyle, atMs: Long): DanceLeadVoice = when (style) {
    DanceStyle.GypsyWoman -> when (gypsyTextureAt(atMs)) {
        GypsyTexture.Organ -> DanceLeadVoice.OrganStab
        GypsyTexture.ElectricPiano -> DanceLeadVoice.ElectricPiano
        GypsyTexture.Mallet -> DanceLeadVoice.Mallet
    }
    DanceStyle.Levels -> when {
        atMs * requireNotNull(levelsSong.bundledBpm) < 16L * 60_000 -> DanceLeadVoice.PluckedString
        levelsSoftLeadAt(atMs) -> DanceLeadVoice.Piano
        else -> DanceLeadVoice.Synth
    }
}

private const val DanceRate = 32_000
private const val DanceCycle = 4_294_967_296.0
private const val DanceTableSize = 2048
private val danceSine = FloatArray(DanceTableSize + 1) { sin(it * (2 * PI / DanceTableSize)).toFloat() }
private fun danceFrames(ms: Long) = ((ms * DanceRate + 500) / 1000).toInt()
private fun danceHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
private fun danceStep(hz: Double) = (hz * DanceCycle / DanceRate).toInt()
private fun danceDecay(seconds: Double) = exp(-1.0 / (DanceRate * seconds)).toFloat()
private fun danceSample(table: FloatArray, phase: Int): Float {
    val i = phase ushr 21
    val fraction = (phase and 0x1fffff) / 2097152f
    return table[i] + fraction * (table[i + 1] - table[i])
}

internal class DanceStudio(chart: Chart, private val style: DanceStyle) {
    private val length = danceFrames(chart.durationMs)
    private val left = FloatArray(length)
    private val right = FloatArray(length)
    private val kicks = FloatArray(length)
    private val sawTables = mutableMapOf<Int, FloatArray>()

    init {
        val recovery = danceDecay(.075)
        for (kick in chart.accompaniment.filter { it.voice == BackingVoice.Kick }) {
            val first = danceFrames(kick.atMs)
            var level = kick.velocity
            for (i in first until min(length, first + danceFrames(350))) {
                kicks[i] = maxOf(kicks[i], level * min(1f, (i - first) / 56f))
                level *= recovery
            }
        }
    }

    private fun place(mono: FloatArray, start: Int, gain: Float, pan: Double = 0.0, duck: Float = 0f) {
        val l = (cos((pan + 1) * PI / 4) * gain).toFloat()
        val r = (sin((pan + 1) * PI / 4) * gain).toFloat()
        for (i in 0 until min(mono.size, length - start)) {
            val frame = start + i
            val value = mono[i] * (1f - duck * kicks[frame])
            left[frame] += value * l
            right[frame] += value * r
        }
    }

    fun leadSamples(note: Note): FloatArray = when (danceLeadVoice(style, note.atMs)) {
        DanceLeadVoice.OrganStab -> organ(note.midi, note.durationMs, note.atMs, note.velocity)
        DanceLeadVoice.ElectricPiano -> studioElectricPiano(note.midi, note.durationMs, note.velocity)
        DanceLeadVoice.Mallet -> studioMallet(note.midi, note.durationMs, note.velocity)
        DanceLeadVoice.PluckedString -> studioPluckedString(note.midi, note.durationMs, note.velocity)
        DanceLeadVoice.Piano -> studioPiano(note.midi, note.durationMs, note.velocity)
        DanceLeadVoice.Synth -> levelsSynth(note.midi, note.durationMs)
    }

    fun lead(note: Note) {
        val start = danceFrames(note.atMs)
        val tone = leadSamples(note)
        if (style == DanceStyle.GypsyWoman) {
            // The riff remains the foreground throughout. Material changes are
            // deliberate eight-bar sections, always playing the same harmonic role.
            val gain = when (gypsyTextureAt(note.atMs)) {
                GypsyTexture.Organ -> .54f
                GypsyTexture.ElectricPiano -> .57f
                GypsyTexture.Mallet -> .79f
            } * note.velocity
            place(tone, start, gain, -.025, .10f)
            place(tone, start + danceFrames(29), gain * .070f, .58)
            place(tone, start + danceFrames(53), gain * .045f, -.58)
        } else if (levelsSoftLeadAt(note.atMs)) {
            place(tone, start, .73f * note.velocity, -.035)
            place(tone, start + danceFrames(43), .060f * note.velocity, .58)
            place(tone, start + danceFrames(79), .035f * note.velocity, -.58)
        } else {
            val gain = .81f
            place(tone, start, gain * note.velocity, -.025, .10f)
            place(tone, start + danceFrames(27), gain * note.velocity * .065f, .61)
            place(tone, start + danceFrames(49), gain * note.velocity * .047f, -.61)
        }
    }

    fun backing(note: BackingNote) {
        val start = danceFrames(note.atMs)
        when (note.voice) {
            BackingVoice.Keys -> {
                val pan = ((note.midi - 60) / 34.0).coerceIn(-.50, .50)
                val tone = if (style == DanceStyle.GypsyWoman) when (gypsyTextureAt(note.atMs)) {
                    GypsyTexture.Organ -> organ(note.midi, note.durationMs, note.atMs, note.velocity)
                    GypsyTexture.ElectricPiano, GypsyTexture.Mallet ->
                        studioElectricPiano(note.midi, note.durationMs, note.velocity)
                } else chordVoice(note.midi, note.durationMs)
                val gain = if (style == DanceStyle.GypsyWoman) when (gypsyTextureAt(note.atMs)) {
                    GypsyTexture.ElectricPiano -> .31f
                    GypsyTexture.Mallet -> .36f
                    GypsyTexture.Organ -> .39f
                } else .39f
                place(tone, start, gain * note.velocity, pan, .10f)
                place(tone, start + danceFrames(37), .022f * note.velocity, -pan, .10f)
            }
            BackingVoice.Pad -> {
                val colour = if (style == DanceStyle.GypsyWoman) gypsyColourAt(note.atMs) else GypsyColour.Strings
                val tone = when (colour) {
                    GypsyColour.Plucked -> studioPluckedString(note.midi, note.durationMs, note.velocity)
                    GypsyColour.Mallet -> studioMallet(note.midi, note.durationMs, note.velocity)
                    GypsyColour.Strings -> pad(note.midi, note.durationMs)
                }
                val pan = ((note.midi * 7 % 17) - 8) / 15.0
                place(tone, start, (if (colour == GypsyColour.Strings) .27f else .60f) * note.velocity, pan, .20f)
                place(tone, start + danceFrames(31), .035f * note.velocity, -pan)
            }
            BackingVoice.Bass -> {
                place(bass(note), start, .66f * note.velocity, 0.0, .39f)
                if (style == DanceStyle.GypsyWoman) {
                    // A small upper-octave string body keeps the bass legible on a phone speaker.
                    place(studioPluckedString(note.midi + 12, note.durationMs, note.velocity),
                        start, .11f * note.velocity, 0.0, .39f)
                }
            }
            BackingVoice.Kick -> place(drum(note), start, .79f * note.velocity)
            BackingVoice.Snare -> {
                val tone = drum(note)
                place(tone, start, .66f * note.velocity, -.04)
                place(tone, start + danceFrames(21), .055f * note.velocity, .43)
            }
            BackingVoice.Hat -> {
                val pan = if (note.midi == 70) -.31 else .27
                place(drum(note), start, .44f * note.velocity, pan)
            }
        }
    }

    private fun chordVoice(midi: Int, durationMs: Long): FloatArray = when (style) {
        DanceStyle.GypsyWoman -> organ(midi, durationMs)
        DanceStyle.Levels -> levelsSynth(midi, durationMs)
    }

    private fun organ(midi: Int, durationMs: Long, atMs: Long = 0, velocity: Float = .8f): FloatArray {
        val gate = danceFrames(durationMs)
        val result = FloatArray(gate + danceFrames(380))
        val inc = danceStep(danceHz(midi))
        // Free-running tonewheels and a restrained chorus keep repeated stabs from
        // being identical phase-reset beeps, while the written attack stays exact.
        var phase = (danceFrames(atMs).toLong() * inc).toInt()
        val rankInc = danceStep(danceHz(midi) * 1.0009)
        var rank = phase + 0x18000000
        val rotorStep = danceStep(3.15)
        var rotor = (danceFrames(atMs).toLong() * rotorStep).toInt()
        var key = 1f
        var body = 1f
        var envelope = 0f
        val keyDecay = danceDecay(.038)
        val bodyDecay = danceDecay(.83)
        val attack = 1f - danceDecay(.0027)
        val release = danceDecay(.061)
        val hz = danceHz(midi)
        val touch = .55f + .45f * velocity
        for (i in result.indices) {
            phase += inc
            rank += rankInc
            rotor += rotorStep
            envelope = if (i < gate) envelope + attack * (1f - envelope) else envelope * release
            val main = .85f * danceSample(danceSine, phase) + .15f * danceSample(danceSine, rank)
            var tone = .86f * main
            if (hz * 2 < DanceRate * .46) tone += (.34f + .23f * key * touch) * danceSample(danceSine, phase * 2)
            if (hz * 3 < DanceRate * .46) tone += (.095f + .075f * key * touch) * danceSample(danceSine, phase * 3)
            if (hz * 4 < DanceRate * .46) tone += (.065f + .09f * key * touch) * danceSample(danceSine, phase * 4)
            if (hz * 6 < DanceRate * .46) tone += .035f * key * danceSample(danceSine, phase * 6)
            val rounded = tone / (1f + .16f * abs(tone))
            val motion = .97f + .03f * danceSample(danceSine, rotor)
            result[i] = rounded * envelope * (.28f + .72f * body) * motion * .82f * endFade(i, result.size)
            key *= keyDecay
            body *= bodyDecay
        }
        return result
    }

    private fun levelsTable(midi: Int): FloatArray = sawTables.getOrPut(midi) {
        val hz = danceHz(midi)
        val table = FloatArray(DanceTableSize + 1)
        val partials = min(20, (DanceRate * .46 / hz).toInt())
        for (h in 1..partials) {
            val strength = (if (h % 2 == 0) .54 else .79) * exp(-h / 8.0) / h
            for (i in 0 until DanceTableSize) {
                table[i] += (strength * sin(i * (2 * PI * h / DanceTableSize))).toFloat()
            }
        }
        val peak = table.maxOf { abs(it) }.coerceAtLeast(.75f)
        for (i in 0 until DanceTableSize) table[i] /= peak
        table[DanceTableSize] = table[0]
        table
    }

    private fun levelsSynth(midi: Int, durationMs: Long): FloatArray {
        val gate = danceFrames(durationMs)
        val result = FloatArray(gate + danceFrames(330))
        val hz = danceHz(midi)
        val inc = danceStep(hz)
        val upperInc = danceStep(hz * 1.0020)
        val lowerInc = danceStep(hz * .9980)
        val table = levelsTable(midi)
        var phase = 0
        var upperPhase = 0x27000000
        var lowerPhase = -0x35000000
        var envelope = 0f
        var bright = 1f
        val attack = 1f - danceDecay(.0036)
        val release = danceDecay(.061)
        val colourDecay = danceDecay(.32)
        for (i in result.indices) {
            phase += inc
            upperPhase += upperInc
            lowerPhase += lowerInc
            envelope = if (i < gate) envelope + attack * (1f - envelope) else envelope * release
            val unison = .62f * danceSample(table, phase) + .19f * danceSample(table, upperPhase) +
                .19f * danceSample(table, lowerPhase)
            val tone = unison * (.69f + .31f * bright) + .095f * danceSample(danceSine, phase)
            result[i] = tone * envelope * endFade(i, result.size)
            bright *= colourDecay
        }
        return result
    }

    private fun bass(note: BackingNote): FloatArray {
        val gate = danceFrames(note.durationMs)
        val result = FloatArray(gate + danceFrames(160))
        val inc = danceStep(danceHz(note.midi))
        val attack = 1f - danceDecay(.0025)
        val release = danceDecay(.023)
        val decay = danceDecay(.24)
        var phase = 0
        var envelope = 0f
        var body = 1f
        for (i in result.indices) {
            phase += inc
            envelope = if (i < gate) envelope + attack * (1f - envelope) else envelope * release
            val tone = .82f * danceSample(danceSine, phase) + .17f * body * danceSample(danceSine, phase * 2) +
                .08f * body * danceSample(danceSine, phase * 3)
            result[i] = tone * envelope * (.62f + .38f * body) * endFade(i, result.size)
            body *= decay
        }
        return result
    }

    private fun pad(midi: Int, durationMs: Long): FloatArray {
        val gate = danceFrames(durationMs)
        val result = FloatArray(gate + danceFrames(550))
        val hz = danceHz(midi)
        val inc = danceStep(hz)
        val outerInc = danceStep(hz * 1.0014)
        val attack = 1f - danceDecay(.055)
        val release = danceDecay(.085)
        var phase = 0
        var outerPhase = 0
        var envelope = 0f
        for (i in result.indices) {
            phase += inc
            outerPhase += outerInc
            envelope = if (i < gate) envelope + attack * (1f - envelope) else envelope * release
            val tone = .57f * danceSample(danceSine, phase) + .31f * danceSample(danceSine, outerPhase) +
                .12f * danceSample(danceSine, phase * 2)
            result[i] = tone * envelope * endFade(i, result.size)
        }
        return result
    }

    private fun drum(note: BackingNote): FloatArray {
        val kick = note.voice == BackingVoice.Kick
        val snare = note.voice == BackingVoice.Snare
        val rim = snare && note.midi == 37
        val open = note.midi == 46
        val shaker = note.midi == 70
        val size = danceFrames(when {
            kick -> 390L
            rim -> 165L
            snare -> 300L
            open -> 410L
            else -> 160L
        })
        val result = FloatArray(size)
        val decay = danceDecay(when {
            kick -> .095
            rim -> .021
            snare -> .048
            open -> .066
            shaker -> .029
            else -> .019
        })
        val sweepDecay = danceDecay(.026)
        val clickDecay = danceDecay(.003)
        val clapDecay = danceDecay(.006)
        val clapTwo = danceFrames(11)
        val clapThree = danceFrames(23)
        var random = note.atMs.toInt() * 1103515245 + note.midi * 7919
        var phase = 0
        var sweep = danceStep(112.0).toFloat()
        val base = danceStep(when { kick -> 49.0; rim -> 760.0; else -> 185.0 })
        var envelope = 1f
        var click = 1f
        var slap = 1f
        var previous = 0f
        var air = 0f
        for (i in result.indices) {
            random = random * 1664525 + 1013904223
            val noise = random / 2147483648f
            val high = (noise - previous) * .5f
            previous = noise
            air += .38f * (noise - air)
            phase += base + if (kick) sweep.toInt() else 0
            val tone = when {
                kick -> danceSample(danceSine, phase) + .17f * click * high
                rim -> .61f * danceSample(danceSine, phase) + .18f * danceSample(danceSine, phase * 2) + .16f * click * high
                snare -> {
                    if (i == clapTwo || i == clapThree) slap = .94f
                    (.76f * air + .44f * high) * (.58f + .66f * slap) + .13f * danceSample(danceSine, phase)
                }
                shaker -> (.62f * high + .27f * air) * min(1f, i / 130f)
                else -> .79f * high + .18f * (noise - air)
            }
            result[i] = tone * envelope * min(1f, i / 24f) * endFade(i, size)
            envelope *= decay
            sweep *= sweepDecay
            click *= clickDecay
            slap *= clapDecay
        }
        return result
    }

    private fun endFade(i: Int, size: Int) = min(1f, (size - 1 - i) / (DanceRate * .008f))

    fun write(destination: File) {
        val outputGain = if (style == DanceStyle.Levels) .735f else .80f
        destination.outputStream().buffered().use { output ->
            output.write(waveHeader(DanceRate, 2, length * 4))
            val bytes = ByteArray(4 * 4096)
            var offset = 0
            fun channel(sample: Float, byte: Int, fade: Float) {
                val over = abs(sample) - .75f
                val value = if (over <= 0f) sample else {
                    val limited = .75f + .19f * over / (.19f + over)
                    if (sample < 0f) -limited else limited
                }
                val word = (value * outputGain * fade * 32767).roundToInt()
                bytes[byte] = word.toByte()
                bytes[byte + 1] = (word shr 8).toByte()
            }
            for (i in 0 until length) {
                val fade = min(1f, (length - 1 - i) / (DanceRate * .16f))
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

internal fun writeDanceMaster(destination: File, chart: Chart, style: DanceStyle) {
    val studio = DanceStudio(chart, style)
    for (note in chart.accompaniment) studio.backing(note)
    for (note in chart.notes) studio.lead(note)
    studio.write(destination)
}
