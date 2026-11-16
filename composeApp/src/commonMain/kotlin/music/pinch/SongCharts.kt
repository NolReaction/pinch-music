package music.pinch

import kotlinx.serialization.json.*
import music.pinch.core.Chart
import music.pinch.core.Note

internal fun encodeChart(chart: Chart): String = buildJsonObject {
    put("version", if (chart.authoredLandings) 2 else 1); put("bpm", chart.bpm); put("durationMs", chart.durationMs)
    putJsonArray("notes") {
        chart.notes.forEach { n -> add(buildJsonArray {
            add(n.atMs); add(n.midi); add(n.durationMs); add(n.velocity)
            if (!n.playable) add(false)
        }) }
    }
}.toString()

internal fun decodeChart(text: String, title: String): Chart {
    require(text.length <= 128_000)
    val json = Json.parseToJsonElement(text).jsonObject
    val version = json.getValue("version").jsonPrimitive.int
    require(version in 1..2)
    val notes = json.getValue("notes").jsonArray.mapIndexed { i, el ->
        val n = el.jsonArray
        Note("n$i", n[0].jsonPrimitive.long, n[1].jsonPrimitive.int, n[2].jsonPrimitive.long,
            n[3].jsonPrimitive.float, playable = n.getOrNull(4)?.jsonPrimitive?.boolean ?: true)
    }
    require(notes.size in 1..1000 && notes.zipWithNext().all { (a,b) -> b.atMs > a.atMs })
    return Chart(title, json.getValue("bpm").jsonPrimitive.int, json.getValue("durationMs").jsonPrimitive.long, notes,
        authoredLandings = version == 2)
}
