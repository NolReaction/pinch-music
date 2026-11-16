package music.pinch

import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Old imports were stored against a built-in song. Keep the original chart and record keys
 * for those entries, but give them their own selectable identity. */
internal data class PersonalSong(val id: String, val title: String, val legacyId: String? = null,
    val hasRecording: Boolean = false) {
    val storageId get() = legacyId ?: id
    fun asSong() = Song(id, title, "Своя запись")
}

internal class PersonalCatalog(private val platform: Platform, private val builtIns: List<Song>) {
    private val key = "library.personal.v1"
    private val recoveryKey = "$key.recovery"
    private var damagedIndex: String? = null
    private data class Index(val entries: List<PersonalSong>, val favorites: Set<String> = emptySet())
    private var index = readIndex() ?: migrate()
    val entries get() = index.entries
    val favorites get() = index.favorites
    val songs get() = builtIns + entries.map { it.asSong() }

    @OptIn(ExperimentalUuidApi::class)
    fun newEntry(displayName: String?): PersonalSong {
        val name = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val stem = name.substringBeforeLast('.', name).filterNot { it.isISOControl() }.trim().take(120)
        val base = stem.ifBlank { "Моя запись" }
        // Distinguish separate takes whose filenames are identical.
        val titles = entries.mapTo(mutableSetOf()) { it.title }
        var title = base
        var copy = 2
        while (title in titles) {
            title = "$base ($copy)"
            copy++
        }
        return PersonalSong("import-" + Uuid.random(), title)
    }

    fun add(entry: PersonalSong, chart: String) {
        val current = readIndex() ?: index
        platform.writeValue("chart.${entry.storageId}", chart)
        save(current.copy(entries = current.entries + entry))
    }

    fun remove(entry: PersonalSong) {
        val current = readIndex() ?: index
        save(current.copy(entries = current.entries.filterNot { it.id == entry.id },
            favorites = current.favorites - entry.id))
        platform.removeValue("chart.${entry.storageId}")
    }

    fun toggleFavorite(id: String) {
        val current = readIndex() ?: index
        if (builtIns.none { it.id == id } && current.entries.none { it.id == id }) return
        val next = if (id in current.favorites) current.favorites - id else current.favorites + id
        save(current.copy(favorites = next))
    }

    private fun migrate(): Index {
        val selected = platform.readValue("selected.song")
            ?: builtIns.firstOrNull { it.id == "stereo-love" }?.id ?: builtIns.firstOrNull { it.hasBundled }?.id
        val existing = builtIns.mapNotNull { song ->
            if (platform.readValue("chart.${song.id}").isNullOrBlank()) return@mapNotNull null
            PersonalSong("import-legacy-${song.id}", song.title, legacyId = song.id)
        }
        val migrated = Index(existing)
        // A damaged index is not an empty library. Keep both copies untouched on launch.
        if (platform.readValue(key) == null && platform.readValue(recoveryKey) == null) {
            existing.find { it.legacyId == selected }?.let { platform.writeValue("selected.song", it.id) }
            persist(migrated)
        }
        return migrated
    }

    private fun readIndex(): Index? {
        platform.readValue(key)?.let { raw ->
            runCatching { decodeIndex(raw) }.getOrNull()?.let { return it }
            damagedIndex = raw
        }
        return platform.readValue(recoveryKey)?.let { raw -> runCatching { decodeIndex(raw) }.getOrNull() }
    }

    private fun decodeIndex(stored: String): Index {
        val json = Json.parseToJsonElement(stored).jsonObject
        val entries = json.getValue("songs").jsonArray.map { el ->
            val item = el.jsonObject
            PersonalSong(item.getValue("id").jsonPrimitive.content, item.getValue("title").jsonPrimitive.content,
                item["legacy"]?.jsonPrimitive?.content, item["recording"]?.jsonPrimitive?.boolean == true)
        }
        return Index(entries, json.getValue("favorites").jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content })
    }

    private fun save(next: Index) {
        persist(next)
        index = next
    }

    private fun persist(value: Index) {
        val encoded = buildJsonObject {
            putJsonArray("songs") {
                for (song in value.entries) add(buildJsonObject {
                    put("id", song.id)
                    put("title", song.title)
                    song.legacyId?.let { put("legacy", it) }
                    if (song.hasRecording) put("recording", true)
                })
            }
            putJsonArray("favorites") { value.favorites.forEach { add(it) } }
        }.toString()
        damagedIndex?.let {
            platform.writeValue("$key.damaged", it)
            damagedIndex = null
        }
        platform.writeValue(key, encoded)
        // Only names and IDs are mirrored. Note charts and recordings are stored once.
        runCatching { platform.writeValue(recoveryKey, encoded) }
    }
}
