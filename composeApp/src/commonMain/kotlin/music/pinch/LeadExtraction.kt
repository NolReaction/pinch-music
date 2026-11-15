package music.pinch

import kotlin.math.*
import music.pinch.core.Chart
import music.pinch.core.Note

private const val analysisRate = 11025
private const val frameSize = 1024
private const val hopSize = 256
private const val minimumPitch = 48
private const val maximumPitch = 91
private const val minimumTapGap = 580L
private data class PitchFrame(val midi: Int, val strength: Double, val rms: Double)
private data class Attack(val timeMs: Long, val midi: Int, val lengthMs: Long, val strength: Double)

/** Extract a playable lead from a short, decoded mono excerpt. Call off the UI thread. */
internal fun extractLeadChart(samples: FloatArray, sampleRate: Int, title: String): Chart {
    require(sampleRate in 8000..192000 && title.isNotBlank()) { "Неподдерживаемый формат аудио." }
    val excerpt = resample(samples, sampleRate)
    require(excerpt.size >= analysisRate / 2) { "Слишком короткая запись." }
    val analyzer = LeadSpectrum()
    val frames = (0..excerpt.size - frameSize step hopSize).map { analyzer.analyze(excerpt, it) }
    val peakRms = frames.maxOfOrNull { it.rms } ?: 0.0
    val gate = max(0.0004, peakRms * 0.045)
    val pitches = frames.map { if (it.rms < gate) -1 else it.midi }.toMutableList()
    for (i in 1 until pitches.lastIndex) {
        if (pitches[i - 1] == pitches[i + 1] && pitches[i] != pitches[i - 1]) pitches[i] = pitches[i - 1]
    }
    val attacks = mutableListOf<Attack>()
    var start = 0
    for (end in 1..frames.size) {
        // A second attack of the same pitch can begin before the first has gone completely silent.
        val reattack = end < frames.size && end - start >= 6 &&
            frames[end].rms > frames[end - 1].rms * 1.8 && frames[end - 1].rms < frames[start].rms * 0.55
        if (end < frames.size && pitches[end] == pitches[start] && !reattack) continue
        if (end - start >= 3 && pitches[start] >= 0) {
            val ms = start * hopSize * 1000L / analysisRate
            val duration = (end - start) * hopSize * 1000L / analysisRate
            val volume = (start until end).maxOf { frames[it].rms } / peakRms
            val prominence = (start until end).map { frames[it].strength }.average()
            attacks += Attack(ms, pitches[start], duration, volume * prominence)
        }
        start = end
    }
    require(attacks.size >= 3 && attacks.any { it.midi != attacks.first().midi }) {
        "Не удалось выделить мелодию. Выбери фрагмент, где хорошо слышен голос или ведущий инструмент."
    }

    // Thin dense passages for one-finger play while retaining the source time of each chosen attack.
    val chosen = mutableListOf(attacks.first())
    var index = 1
    while (index < attacks.size) {
        val threshold = chosen.last().timeMs + minimumTapGap
        while (index < attacks.size && attacks[index].timeMs < threshold) index++
        if (index >= attacks.size) break
        var best = attacks[index++]
        val horizon = best.timeMs + 130
        while (index < attacks.size && attacks[index].timeMs < horizon) {
            val candidate = attacks[index++]
            if (candidate.lengthMs.coerceAtMost(600) * candidate.strength >
                best.lengthMs.coerceAtMost(600) * best.strength * 1.25) best = candidate
        }
        chosen += best
    }
    require(chosen.size >= 3) { "Слишком мало различимых ударов. Попробуй более длинный фрагмент." }
    val onset = chosen.map { 2200L + (it.timeMs - chosen.first().timeMs) }
    val notes = chosen.mapIndexed { i, attack ->
        val next = onset.getOrNull(i + 1) ?: (onset[i] + 1000)
        val duration = attack.lengthMs.coerceIn(100, next - onset[i])
        val gain = (0.68 + 0.25 * sqrt(attack.strength.coerceIn(0.0, 1.0))).toFloat()
        Note("local-${i + 1}", onset[i], attack.midi, duration, gain)
    }
    return Chart(title, estimatePulse(attacks), notes.last().atMs + notes.last().durationMs + 1200, notes)
}

/** Use source onsets, before thinning for one finger. Pitched onsets alone cannot disambiguate
 * two beat octaves, so imported tracks show this as an estimate. */
private fun estimatePulse(source: List<Attack>): Int {
    val gaps = source.zipWithNext { a, b -> b.timeMs - a.timeMs }.filter { it in 120L..2_000L }.sorted()
    if (gaps.isEmpty()) return 120
    val typical = (gaps[(gaps.size - 1) / 2] + gaps[gaps.size / 2]) / 2.0
    var bpm = 60_000.0 / typical
    while (bpm > 180) bpm /= 2
    while (bpm < 60) bpm *= 2
    return bpm.roundToInt()
}

private fun resample(input: FloatArray, inputRate: Int): FloatArray {
    val n = input.size.coerceAtMost(inputRate * 30)
    if (inputRate == analysisRate) return input.copyOf(n)
    val size = (n.toLong() * analysisRate / inputRate).toInt()
    return FloatArray(size) { i ->
        val a = (i.toLong() * inputRate / analysisRate).toInt()
        val b = (((i + 1L) * inputRate / analysisRate).toInt()).coerceAtLeast(a + 1).coerceAtMost(n)
        var sum = 0.0
        for (j in a until b) sum += input[j]
        (sum / (b - a)).toFloat()
    }
}

/** Windowed spectrum and a harmonic comb. Frequency interpolation avoids rounding detuned notes to bins. */
private class LeadSpectrum {
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val magnitude = DoubleArray(frameSize / 2)
    private val taper = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2 * PI * it / (frameSize - 1)) }
    private val cosines = DoubleArray(frameSize / 2) { cos(-2 * PI * it / frameSize) }
    private val sines = DoubleArray(frameSize / 2) { sin(-2 * PI * it / frameSize) }
    private val bins = DoubleArray(maximumPitch - minimumPitch + 1) {
        440.0 * 2.0.pow((minimumPitch + it - 69) / 12.0) * frameSize / analysisRate
    }

    fun analyze(samples: FloatArray, start: Int): PitchFrame {
        var energy = 0.0
        for (i in re.indices) {
            val value = samples[start + i].let { if (it.isFinite()) it.toDouble() else 0.0 }
            re[i] = value * taper[i]
            im[i] = 0.0
            energy += value * value
        }
        val rms = sqrt(energy / frameSize)
        if (rms < 0.0002) return PitchFrame(-1, 0.0, rms)
        fft()
        for (i in magnitude.indices) magnitude[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        val maxBin = magnitude.max()
        var bestPitch = -1
        var best = 0.0
        for (i in bins.indices) {
            val bin = bins[i]
            val fundamental = magnitudeAt(bin)
            val score = fundamental + 0.48 * magnitudeAt(bin * 2) + 0.28 * magnitudeAt(bin * 3) +
                0.14 * magnitudeAt(bin * 4) - 0.32 * magnitudeAt(bin / 2)
            if (fundamental > maxBin * 0.08 && score > best) {
                best = score
                bestPitch = minimumPitch + i
            }
        }
        // Flat noise and percussive transients rarely concentrate meaningful energy at one fundamental.
        val total = magnitude.sum().coerceAtLeast(1e-8)
        val concentration = best / total
        return PitchFrame(if (concentration >= 0.033) bestPitch else -1,
            (concentration * 6).coerceIn(0.0, 1.0), rms)
    }

    private fun magnitudeAt(bin: Double): Double {
        val lo = bin.toInt()
        if (lo !in 0 until magnitude.lastIndex) return 0.0
        return magnitude[lo] + (magnitude[lo + 1] - magnitude[lo]) * (bin - lo)
    }

    private fun fft() {
        var j = 0
        for (i in 1 until frameSize) {
            var bit = frameSize / 2
            while (j and bit != 0) { j = j xor bit; bit /= 2 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val c = im[i]; im[i] = im[j]; im[j] = c
            }
        }
        var size = 2
        while (size <= frameSize) {
            val half = size / 2
            val stride = frameSize / size
            for (base in 0 until frameSize step size) {
                for (k in 0 until half) {
                    val left = base + k
                    val right = left + half
                    val cos = cosines[k * stride]
                    val sin = sines[k * stride]
                    val x = cos * re[right] - sin * im[right]
                    val y = sin * re[right] + cos * im[right]
                    re[right] = re[left] - x
                    im[right] = im[left] - y
                    re[left] += x
                    im[left] += y
                }
            }
            size *= 2
        }
    }
}
