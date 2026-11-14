package music.pinch

import kotlinx.serialization.json.*
import music.pinch.core.Chart
import music.pinch.core.Note

internal fun decodeDemoChart(json: String): Chart {
    val root = Json.parseToJsonElement(json).jsonObject
    fun JsonObject.field(name: String) = getValue(name).jsonPrimitive
    require(root.field("schemaVersion").int == 1 && root.field("id").content.isNotBlank())
    val video = root.getValue("video").jsonObject
    require(video.field("file").content == "demo-ball-muted.mp4" && video.field("chartTimeAtVideoZeroMs").int == 0)
    require(video.field("width").int > 0 && video.field("height").int > 0 && video.field("fps").double > 0)
    val audio = root.getValue("audio").jsonObject
    require(audio.field("mode").content == "oneShotSamples" && audio.field("sampleDirectory").content == "samples")
    require(audio.field("sampleRateHz").int >= 8000)
    val meter = root.getValue("timeSignature").jsonObject
    require(meter.field("numerator").int > 0 && meter.field("denominator").int > 0)
    val duration = root.field("durationMs").long
    require(root.field("leadInMs").long in 0..duration)
    val notes = root.getValue("notes").jsonArray.map { entry ->
        val note = entry.jsonObject
        Note(note.field("id").content, note.field("atMs").long, note.field("midi").int,
            note.field("durationMs").long, note.field("velocity").float)
    }
    return Chart(root.field("title").content, root.field("bpm").int, duration, notes)
}
