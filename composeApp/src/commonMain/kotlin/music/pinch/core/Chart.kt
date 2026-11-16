package music.pinch.core

data class Note(
    val id: String,
    val atMs: Long,
    val midi: Int,
    val durationMs: Long,
    val velocity: Float,
    val playable: Boolean = true,
)

enum class BackingVoice { Bass, Keys, Pad, Kick, Snare, Hat }

data class BackingNote(
    val atMs: Long,
    val midi: Int,
    val durationMs: Long,
    val velocity: Float,
    val voice: BackingVoice,
)

data class Chart(
    val title: String,
    val bpm: Int,
    val durationMs: Long,
    val notes: List<Note>,
    val accompaniment: List<BackingNote> = emptyList(),
    val authoredLandings: Boolean = false,
) {
    init {
        require(title.isNotBlank() && bpm > 0 && durationMs > 0) { "Некорректное описание мелодии" }
        require(notes.isNotEmpty()) { "В мелодии нет нот" }

        val ids = mutableSetOf<String>()
        var previousOnset = -1L
        var lastLanding = -1L
        for (note in notes) {
            require(note.id.isNotBlank() && ids.add(note.id)) { "Некорректный ID ноты: ${note.id}" }
            require(note.atMs >= 0 && note.atMs >= previousOnset) { "Нарушен порядок нот: ${note.id}" }
            require(note.durationMs > 0 && note.atMs <= durationMs - note.durationMs) {
                "Нота за пределами видео: ${note.id}"
            }
            require(note.midi in 0..127 && note.velocity in 0f..1f) { "Некорректный звук: ${note.id}" }
            if (authoredLandings && note.playable) {
                require(note.atMs > lastLanding) { "Два игровых удара в один момент" }
                lastLanding = note.atMs
            }
            previousOnset = note.atMs
        }
        require(!authoredLandings || lastLanding >= 0L) { "В партии нет отмеченных ударов" }
        previousOnset = -1L
        for (note in accompaniment) {
            require(note.atMs >= 0 && note.atMs >= previousOnset &&
                note.durationMs > 0 && note.atMs <= durationMs - note.durationMs &&
                note.midi in 0..127 && note.velocity in 0f..1f) { "Некорректная нота сопровождения" }
            previousOnset = note.atMs
        }
    }
}
