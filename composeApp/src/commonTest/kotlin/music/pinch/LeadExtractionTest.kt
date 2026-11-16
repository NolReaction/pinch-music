package music.pinch

import kotlin.math.*
import kotlin.test.*
import music.pinch.core.Chart
import music.pinch.core.Note
import music.pinch.core.cascadeChart
import music.pinch.core.markDetectedLandings

class LeadExtractionTest {
    @Test fun preservesPitchPhraseAndEnforcesComfortablePacing() {
        val rate = 22050
        val expected = listOf(60, 64, 67, 67, 69, 65, 62, 60)
        val samples = FloatArray(expected.size * rate)
        for ((index, pitch) in expected.withIndex()) {
            val freq = 440 * 2.0.pow((pitch - 69) / 12.0)
            for (i in 0 until rate * 2 / 3) {
                val t = i.toDouble() / rate
                samples[index * rate + i] = (0.6 * exp(-2 * t) * (sin(2 * PI * freq * t) +
                    0.28 * sin(4 * PI * freq * t))).toFloat()
            }
        }
        val chart = extractLeadChart(samples, rate, "Фраза")
        assertEquals(expected, chart.notes.map { it.midi })
        assertTrue(chart.notes.zipWithNext().all { (a,b) -> b.atMs - a.atMs >= 579 })
        assertTrue(chart.notes.first().atMs in 0..25)
        assertEquals(8000L, chart.durationMs)
    }
    @Test fun aClearQuarterNotePhraseKeepsEveryBeatAtItsOriginalOnset() {
        val rate = 11_025
        val stepSamples = (rate * 60.0 / 127).roundToInt()
        val pitches = listOf(60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74)
        val samples = FloatArray(pitches.size * stepSamples)
        pitches.forEachIndexed { step, pitch ->
            val freq = 440 * 2.0.pow((pitch - 69) / 12.0)
            repeat(stepSamples * 3 / 4) { index ->
                val time = index.toDouble() / rate
                samples[step * stepSamples + index] = (.65 * exp(-3 * time) * sin(2 * PI * freq * time)).toFloat()
            }
        }
        val chart = extractLeadChart(samples, rate, "Быстрая фраза")
        assertEquals(pitches, chart.notes.filter { it.playable }.map { it.midi })
        assertTrue(chart.bpm in 123..131, "Estimated BPM from original onsets: ${chart.bpm}")
        val firstPitch = chart.notes.first().midi
        for (note in chart.notes) {
            val expectedGapMs = (note.midi - firstPitch) * stepSamples * 1_000L / rate
            val actualGapMs = note.atMs - chart.notes.first().atMs
            assertTrue(abs(expectedGapMs - actualGapMs) <= 100, "pitch ${note.midi}: $actualGapMs vs $expectedGapMs")
        }
    }

    @Test fun aRapidCallAndAnswerSurvivesImportAndReloadWithThePickupStillAudible() {
        val eighths = (0 until 8).map {
            Note("lead-$it", 2200L + (it * 60_000.0 / 127 / 2).roundToLong(),
                listOf(68, 73, 72, 70)[it % 4], 172, .94f)
        }
        val pickup = Note("passing", 2293, 70, 74, .67f, playable = false)
        val source = Chart("Ответ", 127, 5200, (eighths + pickup).sortedBy { it.atMs })
        val marked = source.markDetectedLandings()
        val loaded = decodeChart(encodeChart(marked), marked.title)
        assertEquals(eighths.map { it.atMs }, loaded.cascadeChart().notes.map { it.atMs })
        assertEquals(source.notes.map { it.atMs to it.midi }, loaded.notes.map { it.atMs to it.midi })
    }

    @Test fun followsRepeatedMelodyOverBassChordsAndDrumsWithoutLosingItsSourcePosition() {
        val rate = 11_025
        val lead = listOf(72, 72, 74, 76, 76, 76, 79, 77, 77, 74, 76, 72, 69, 69, 72, 71)
        val step = 60.0 / 112 / 2
        val intro = .63
        val samples = FloatArray(((intro + (lead.size + 1) * step) * rate).toInt())
        fun tone(midi: Int, time: Double) = 2 * PI * 440 * 2.0.pow((midi - 69) / 12.0) * time
        val chords = listOf(listOf(52, 55, 60), listOf(50, 55, 59))
        val noise = kotlin.random.Random(331)
        for (i in samples.indices) {
            val time = i.toDouble() / rate - intro
            if (time < 0) continue
            val eighth = (time / step).toInt()
            if (eighth >= lead.size) continue
            val age = time - step * eighth
            val phase = tone(lead[eighth], age)
            val release = ((step * .96 - age) / .035).coerceIn(0.0, 1.0)
            var value = .25 * (1 - exp(-age / .008)) * exp(-1.8 * age) * release *
                (sin(phase) + .42 * sin(2 * phase) + .24 * sin(3 * phase))
            val chordAge = age + eighth % 4 * step
            val chord = chords[eighth / 4 % 2]
            for (pitch in chord) {
                val p = tone(pitch, chordAge)
                value += .18 * (1 - exp(-chordAge / .08)) * ((4 * step - chordAge) / .07).coerceIn(0.0, 1.0) *
                    (sin(p) + .30 * sin(2 * p))
            }
            if (eighth % 2 == 0) {
                value += .58 * exp(-7 * age) * sin(2 * PI * 78 * age)
                value += .8 * exp(-24 * age) * sin(2 * PI * (55 * age + 1.8 * (1 - exp(-30 * age))))
            }
            samples[i] = (.55 * (value + .12 * (noise.nextDouble() * 2 - 1) * exp(-75 * age))).toFloat()
        }
        val chart = extractLeadChart(samples, rate, "Фрагмент")
        assertEquals(lead, chart.notes.map { it.midi })
        assertEquals(lead, chart.notes.filter { it.playable }.map { it.midi })
        assertTrue(chart.bpm in 110..114)
        chart.notes.forEachIndexed { i, note ->
            val audible = ((intro + i * step) * 1000).roundToLong()
            assertTrue(abs(note.atMs - audible) <= 38, "note $i at ${note.atMs}, expected $audible")
        }
        assertTrue(abs(chart.durationMs - samples.size * 1_000L / rate) <= 1)
    }

    @Test fun refusesQuietAudioAndIsolatedDrumLikeClicks() {
        val clicks = FloatArray(11025 * 3) { if (it % 2800 == 0) 1f else 0f }
        assertFailsWith<IllegalArgumentException> { extractLeadChart(clicks, 11025, "Щелчки") }
        assertFailsWith<IllegalArgumentException> { extractLeadChart(FloatArray(22050), 11025, "Тишина") }
        val noise = kotlin.random.Random(27)
        val hiss = FloatArray(11_025 * 5) { noise.nextFloat() * .4f - .2f }
        assertFailsWith<IllegalArgumentException> { extractLeadChart(hiss, 11025, "Шум") }
    }
}
