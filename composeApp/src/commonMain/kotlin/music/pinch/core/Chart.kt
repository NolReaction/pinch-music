package music.pinch.core

data class Note(
    val id: String,
    val atMs: Long,
    val midi: Int,
    val durationMs: Long,
    val velocity: Float,
)

data class Chart(
    val title: String,
    val bpm: Int,
    val durationMs: Long,
    val notes: List<Note>,
) {
    init {
        require(title.isNotBlank() && bpm > 0 && durationMs > 0) { "Некорректное описание мелодии" }
        require(notes.isNotEmpty()) { "В мелодии нет нот" }

        val ids = mutableSetOf<String>()
        var previousOnset = -1L
        for (note in notes) {
            require(note.id.isNotBlank() && ids.add(note.id)) { "Некорректный ID ноты: ${note.id}" }
            require(note.atMs >= 0 && note.atMs >= previousOnset) { "Нарушен порядок нот: ${note.id}" }
            require(note.durationMs > 0 && note.atMs <= durationMs - note.durationMs) {
                "Нота за пределами видео: ${note.id}"
            }
            require(note.midi in 0..127 && note.velocity in 0f..1f) { "Некорректный звук: ${note.id}" }
            previousOnset = note.atMs
        }
    }
}
