package music.pinch

import kotlinx.serialization.json.*
import music.pinch.core.Chart
import music.pinch.core.Note
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Preserve the melody's rhythm while stretching the whole phrase to a comfortable tap speed. */
internal fun Chart.atPace(pace: Pace): Chart {
    val closest = notes.zipWithNext { a,b -> b.atMs - a.atMs }.minOrNull() ?: pace.minGapMs
    require(closest > 0) { "Нужна одноголосная мелодия" }
    val factor = maxOf(if (pace == Pace.Relaxed) 1.15 else 1.0, pace.minGapMs.toDouble() / closest)
    val leadIn = notes.first().atMs
    fun scale(t: Long) = leadIn + ((t - leadIn) * factor).roundToLong()
    return copy(bpm = (bpm / factor).roundToInt().coerceAtLeast(1),
        durationMs = scale(durationMs),
        notes = notes.map { it.copy(atMs = scale(it.atMs), durationMs = (it.durationMs * factor).roundToLong().coerceAtLeast(1)) })
}

internal fun encodeChart(chart: Chart): String = buildJsonObject {
    put("version",1); put("bpm", chart.bpm); put("durationMs", chart.durationMs)
    putJsonArray("notes") {
        chart.notes.forEach { n -> add(buildJsonArray {
            add(n.atMs); add(n.midi); add(n.durationMs); add(n.velocity)
        }) }
    }
}.toString()

internal fun decodeChart(text: String, title: String): Chart {
    require(text.length <= 128_000)
    val json = Json.parseToJsonElement(text).jsonObject
    require(json.getValue("version").jsonPrimitive.int == 1)
    val notes = json.getValue("notes").jsonArray.mapIndexed { i, el ->
        val n = el.jsonArray
        Note("n$i", n[0].jsonPrimitive.long, n[1].jsonPrimitive.int, n[2].jsonPrimitive.long, n[3].jsonPrimitive.float)
    }
    require(notes.size in 1..1000 && notes.zipWithNext().all { (a,b) -> b.atMs > a.atMs })
    return Chart(title, json.getValue("bpm").jsonPrimitive.int, json.getValue("durationMs").jsonPrimitive.long, notes)
}
