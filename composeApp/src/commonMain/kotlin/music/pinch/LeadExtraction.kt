package music.pinch

import kotlin.math.*
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.markDetectedLandings

private const val rate = 11025
private const val window = 1024
private const val hop = 128
private const val lowest = 45
private const val pitchCount = 47
private const val rest = pitchCount
private data class Frame(val salience: DoubleArray, val flux: DoubleArray)
private data class Attack(val onset: Long, val until: Long, val midi: Int, val strength: Double)
private fun frameMs(index: Double) = (index * hop * 1000 / rate).roundToLong()

/** All times refer to the PCM excerpt, including any silence before its first note. */
internal fun extractLeadChart(samples: FloatArray, sampleRate: Int, title: String): Chart {
    require(sampleRate in 8000..192000 && title.isNotBlank()) { "Неподдерживаемый формат аудио." }
    val pcm = resample(samples, sampleRate)
    require(pcm.size >= rate / 2) { "Слишком короткая запись." }
    val spectrum = LeadSpectrum()
    val frames = Array(pcm.size / hop + 1) { spectrum.analyze(pcm, it * hop) }
    val duration = pcm.size * 1000L / rate
    val attacks = segmentMelody(frames, trackMelody(frames), duration)
    require(attacks.size >= 3 && attacks.any { it.midi != attacks.first().midi }) {
        "Не удалось выделить устойчивую мелодию. Выбери фрагмент, где отчётливо слышен голос или ведущий инструмент."
    }
    val bpm = estimatePulse(frames, attacks)
    val beat = 60_000.0 / bpm
    val peak = attacks.maxOf { it.strength }.coerceAtLeast(1e-6)
    val notes = attacks.mapIndexed { i, note ->
        val nextOnset = attacks.getOrNull(i + 1)?.onset ?: duration
        val length = min(note.until, nextOnset) - note.onset
        val strength = sqrt((note.strength / peak).coerceIn(0.0, 1.0))
        val clear = length >= beat * .25 || (length >= beat * .16 && strength >= .55)
        Note("local-${i + 1}", note.onset, note.midi, length, (.62 + .34 * strength).toFloat(), clear)
    }
    val chart = Chart(title, bpm, duration, notes).markDetectedLandings()
    require(chart.notes.count { it.playable } >= 3) { "Слишком мало различимых ударов. Попробуй более длинный фрагмент." }
    return chart
}

private fun resample(input: FloatArray, inputRate: Int): FloatArray {
    val n = input.size.coerceAtMost(inputRate * 30)
    if (inputRate == rate) return if (n == input.size) input else input.copyOf(n)
    val size = (n.toLong() * rate / inputRate).toInt()
    return FloatArray(size) { i ->
        val a = (i.toLong() * inputRate / rate).toInt()
        val b = (((i + 1L) * inputRate / rate).toInt()).coerceAtLeast(a + 1).coerceAtMost(n)
        var sum = 0.0
        for (j in a until b) sum += input[j]
        (sum / (b - a)).toFloat()
    }
}

/** Subtract the local standing chord, then follow one continuous voice or an explicit rest. */
private fun trackMelody(frames: Array<Frame>): IntArray {
    val size = frames.size
    val scores = Array(size) { DoubleArray(pitchCount) }
    val strongest = DoubleArray(size)
    val prefix = DoubleArray(size + 1)
    for (p in 0 until pitchCount) {
        val register = 2.0.pow((p + lowest - 66) / 18.0).coerceIn(.45, 1.4)
        for (i in frames.indices) prefix[i + 1] = prefix[i] + frames[i].salience[p]
        for (i in frames.indices) {
            val a = (i - 41).coerceAtLeast(0)
            val b = (i + 42).coerceAtMost(size)
            val background = (prefix[b] - prefix[a]) / (b - a)
            val value = frames[i].salience[p]
            scores[i][p] = register * max(.25 * value, value - .94 * background)
            strongest[i] = max(strongest[i], scores[i][p])
        }
    }
    // A short unpitched transient shouldn't steal the voice from the frames on either side.
    val transitions = Array(pitchCount + 1) { to ->
        DoubleArray(pitchCount + 1) { from ->
            when {
                to == from -> 0.0
                to == rest -> .65
                from == rest -> .75
                else -> min(3.0, 1.7 + .095 * abs(to - from))
            }
        }
    }
    val parents = Array(size) { ByteArray(pitchCount + 1) }
    var last = DoubleArray(pitchCount + 1)
    var next = DoubleArray(pitchCount + 1)
    for (i in frames.indices) {
        var nearbyPeak = 0.0
        for (j in max(0, i - 72)..min(size - 1, i + 72)) nearbyPeak = max(nearbyPeak, strongest[j])
        val scale = maxOf(strongest[i], .52 * nearbyPeak, .035)
        for (to in 0..rest) {
            var winner = to
            var best = last[to]
            val cost = transitions[to]
            for (from in 0..rest) {
                val candidate = last[from] - cost[from]
                if (candidate > best) { best = candidate; winner = from }
            }
            parents[i][to] = winner.toByte()
            next[to] = best + if (to == rest) .42 else scores[i][to] / scale
        }
        val tmp = last; last = next; next = tmp
    }
    var pitch = last.indices.maxBy { last[it] }
    val path = IntArray(size)
    for (i in frames.indices.reversed()) {
        path[i] = pitch
        pitch = parents[i][pitch].toInt()
    }
    return path
}

private fun segmentMelody(frames: Array<Frame>, path: IntArray, duration: Long): List<Attack> {
    val notes = mutableListOf<Attack>()
    var begin = 0
    while (begin < path.size) {
        val pitch = path[begin]
        var end = begin + 1
        while (end < path.size && path[end] == pitch) end++
        if (pitch != rest && end - begin >= 5) {
            fun amplitude(i: Int) = frames[i].salience[pitch]
            var start = begin
            fun append(stop: Int) {
                if (stop - start < 4) return
                val left = if (start == begin) max(0, start - 5) else start
                val valley = (left..start).minBy { amplitude(it) }
                val peak = (valley..min(stop - 1, start + 7)).maxBy { amplitude(it) }
                // Rewind the winning pitch to the rising edge of its own harmonics.
                var onset = start.toDouble()
                if (peak > valley && amplitude(peak) > 1.24 * amplitude(valley)) {
                    val threshold = amplitude(valley) + .16 * (amplitude(peak) - amplitude(valley))
                    val k = (valley + 1..peak).first { amplitude(it) >= threshold }
                    onset = k - 1 + (threshold - amplitude(k - 1)) / (amplitude(k) - amplitude(k - 1))
                }
                val ms = if (start < 3) 0 else frameMs(onset)
                val until = min(duration, frameMs(stop.toDouble()))
                if (ms < until && (notes.lastOrNull()?.onset ?: -1) < ms) {
                    notes += Attack(ms, until, lowest + pitch, (start until stop).maxOf { amplitude(it) })
                }
            }
            for (k in begin + 2 until end - 2) {
                // A repeated syllable has a new envelope even when the drums mask its dip in total volume.
                if (k - start < 7 || amplitude(k) >= amplitude(k - 1) || amplitude(k) > amplitude(k + 1)) continue
                val before = (max(start, k - 9) until k).maxOf { amplitude(it) }
                val after = (k + 1..min(end - 1, k + 9)).maxOf { amplitude(it) }
                if (amplitude(k) < .56 * min(before, after) && after > .48 * before) {
                    append(k)
                    start = k
                }
            }
            append(end)
        }
        begin = end
    }
    return notes
}

private fun estimatePulse(frames: Array<Frame>, attacks: List<Attack>): Int {
    val values = DoubleArray(frames.size)
    for (band in 0..2) {
        val rise = DoubleArray(values.size)
        var energy = 0.0
        for (i in values.indices) {
            val left = max(0, i - 15)
            val right = min(values.lastIndex, i + 15)
            var sum = 0.0
            for (j in left..right) sum += frames[j].flux[band]
            rise[i] = max(0.0, frames[i].flux[band] - sum / (right - left + 1))
            energy += rise[i] * rise[i]
        }
        val scale = sqrt(energy / values.size) + .015
        for (i in rise.indices) values[i] += rise[i] / scale
    }
    val mean = values.average()
    for (i in values.indices) values[i] -= mean
    val maxLag = min(values.size / 2, (4.0 * rate / hop).toInt() + 2)
    val correlation = DoubleArray(maxLag + 1) { lag ->
        var product = 0.0
        for (i in lag until values.size) product += values[i] * values[i - lag]
        product / (values.size - lag)
    }
    val variance = correlation[0].coerceAtLeast(1e-8)
    val multiples = doubleArrayOf(.5, 1.0, 2.0, 3.0, 4.0)
    val weights = doubleArrayOf(.24, 1.0, .82, .57, .48)
    var best = 0.0
    var bpm = 120
    for (tempo in 60..180) {
        val lag = 60.0 * rate / hop / tempo
        var weighted = 0.0
        var total = 0.0
        for (k in multiples.indices) {
            val pos = lag * multiples[k]
            val lo = pos.toInt()
            if (lo + 1 > maxLag) continue
            val v = correlation[lo] + (pos - lo) * (correlation[lo + 1] - correlation[lo])
            weighted += v * weights[k]
            total += weights[k]
        }
        val octave = log2(tempo / 115.0) / .43
        val estimate = weighted / max(1e-8, variance * total) * (.93 + .07 * exp(-.5 * octave * octave))
        if (estimate > best) { best = estimate; bpm = tempo }
    }
    if (best >= .16) return bpm
    val intervals = attacks.zipWithNext { a,b -> b.onset - a.onset }.filter { it in 120L..2000L }.sorted()
    if (intervals.isEmpty()) return 120
    var fallback = 60_000.0 / intervals[intervals.size / 2]
    while (fallback > 180) fallback /= 2
    while (fallback < 60) fallback *= 2
    return fallback.roundToInt()
}

private class LeadSpectrum {
    private val re = DoubleArray(window)
    private val im = DoubleArray(window)
    private val mag = DoubleArray(window / 2 + 1)
    private val prefix = DoubleArray(mag.size + 1)
    private val prevLog = DoubleArray(mag.size)
    private val taper = DoubleArray(window) { .5 - .5 * cos(2 * PI * it / (window - 1)) }
    private val cosines = DoubleArray(window / 2) { cos(-2 * PI * it / window) }
    private val sines = DoubleArray(window / 2) { sin(-2 * PI * it / window) }
    private val harmonicOffset = DoubleArray(5) { 12 * log2((it + 1).toDouble()) }
    private val harmonicWeight = doubleArrayOf(1.0, .55, .38, .22, .14)
    private val bandStart = intArrayOf(3, 18, 69)
    private val bandEnd = intArrayOf(18, 69, 365)

    fun analyze(samples: FloatArray, center: Int): Frame {
        for (i in re.indices) {
            val source = center + i - window / 2
            val x = if (source in samples.indices) samples[source] else 0f
            re[i] = if (x.isFinite()) x * taper[i] else 0.0
            im[i] = 0.0
        }
        fft()
        for (i in mag.indices) {
            mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
            prefix[i + 1] = prefix[i] + mag[i]
        }
        val flux = DoubleArray(3)
        for (band in flux.indices) {
            for (bin in bandStart[band] until bandEnd[band]) {
                val current = ln(1.0 + 3.0 * mag[bin])
                if (center > 0) flux[band] += max(0.0, current - prevLog[bin])
                prevLog[bin] = current
            }
            flux[band] /= (bandEnd[band] - bandStart[band]).toDouble()
        }
        val salience = DoubleArray(pitchCount)
        // In broadband hiss isolated bins can briefly resemble harmonics. Require a
        // concentrated partial before allowing this frame to vote for a tonal voice.
        val tonalPeak = (8 until 384).maxOf { mag[it] }
        if (tonalPeak < .013 * (prefix[384] - prefix[8])) return Frame(salience, flux)
        val fundamental = DoubleArray(pitchCount)
        for (i in 1 until mag.lastIndex) {
            if (mag[i] <= mag[i - 1] || mag[i] < mag[i + 1] || mag[i] < .001) continue
            val a = ln(max(1e-12, mag[i - 1]))
            val b = ln(mag[i])
            val c = ln(max(1e-12, mag[i + 1]))
            val delta = (a - c) / min(-1e-9, 2 * (a + c - 2 * b))
            val height = exp(b - .25 * (a - c) * delta)
            val left = max(0, i - 7)
            val right = min(mag.size, i + 8)
            val excess = height - 2.3 * (prefix[right] - prefix[left]) / (right - left)
            if (excess <= 0.0) continue
            val midi = 69.0 + 12.0 * log2((i + delta) * rate / (window * 440.0))
            for (h in harmonicOffset.indices) {
                val candidate = midi - harmonicOffset[h]
                val nearest = candidate.roundToInt()
                val pitch = nearest - lowest
                if (pitch !in salience.indices) continue
                val detuning = 1.75 * abs(candidate - nearest)
                val weight = excess * max(0.0, 1.0 - detuning * detuning) * harmonicWeight[h]
                salience[pitch] += weight
                if (h == 0) fundamental[pitch] += weight
            }
        }
        for (i in salience.indices) salience[i] = min(salience[i], 3.5 * fundamental[i])
        return Frame(salience, flux)
    }

    private fun fft() {
        var j = 0
        for (i in 1 until window) {
            var bit = window / 2
            while (j and bit != 0) { j = j xor bit; bit /= 2 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val c = im[i]; im[i] = im[j]; im[j] = c
            }
        }
        var size = 2
        while (size <= window) {
            val half = size / 2
            val stride = window / size
            for (base in 0 until window step size) {
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
