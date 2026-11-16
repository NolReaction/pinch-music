package music.pinch

enum class ImportMode(val label: String) {
    Instrumental("Инструменты"),
    Original("Оригинал"),
}

// Increment together with the adaptation and renderer when cached audio changes.
internal const val IMPORT_ARRANGEMENT_REVISION = 1
internal fun instrumentalRecordingId(id: String) = "$id-instrumental-v$IMPORT_ARRANGEMENT_REVISION"
