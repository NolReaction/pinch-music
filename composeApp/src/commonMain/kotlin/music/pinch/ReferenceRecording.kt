package music.pinch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ChosenRecording(val displayName: String, val durationMs: Long, val suffix: String)

internal data class ReferenceRecording(
    val id: String, val durationMs: Long, val displayName: String, val track: ArrangementTrack,
)

internal fun validReferenceId(id: String) =
    id.length == 36 && id.all { it in 'a'..'f' || it in '0'..'9' || it == '-' }

internal fun validAudioSuffix(suffix: String) =
    suffix.length in 1..12 && suffix.all { it in 'a'..'z' || it in '0'..'9' }

/** Metadata stores a relative file identity because the iOS application container can move on update. */
internal class ReferenceRecordingStore(private val platform: Platform) {
    private fun key(songId: String) = "rhythm.reference.file.$songId"
    fun current(songId: String): ReferenceRecording? {
        val raw = platform.readValue(key(songId)) ?: return null
        return runCatching {
            val data = Json.parseToJsonElement(raw).jsonObject
            val id = data.getValue("id").jsonPrimitive.content
            val suffix = data.getValue("suffix").jsonPrimitive.content
            val duration = data.getValue("duration").jsonPrimitive.long
            require(validReferenceId(id) && validAudioSuffix(suffix) && duration > 0)
            val track = platform.referenceFile(id, suffix) ?: return null
            ReferenceRecording(id, duration, data.getValue("name").jsonPrimitive.content, track)
        }.getOrNull()
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun choose(songId: String): ReferenceRecording? {
        val id = Uuid.random().toString()
        var installed = false
        try {
            val audio = platform.pickReferenceRecording(id) ?: return null
            currentCoroutineContext().ensureActive()
            require(audio.durationMs > 0 && validAudioSuffix(audio.suffix))
            val track = checkNotNull(platform.referenceFile(id, audio.suffix)) { "Копия записи недоступна." }
            val previous = current(songId)
            platform.writeValue(key(songId), buildJsonObject {
                put("id", id)
                put("name", audio.displayName)
                put("duration", audio.durationMs)
                put("suffix", audio.suffix)
            }.toString())
            installed = true
            previous?.let { platform.removeReferenceRecording(it.id) }
            return ReferenceRecording(id, audio.durationMs, audio.displayName, track)
        } finally {
            if (!installed) platform.removeReferenceRecording(id)
        }
    }

    fun remove(songId: String) {
        val old = current(songId)
        platform.removeValue(key(songId))
        old?.let { platform.removeReferenceRecording(it.id) }
    }
}
