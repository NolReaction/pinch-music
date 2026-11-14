package music.pinch.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffFixturesTest {
    private val fixture = javaClass.getResourceAsStream("/fixtures/judgement-cases.json")!!
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun rows(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.time(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun everyBoundaryMatchesTheHandoff() {
        for (case in rows("judgeOnly")) {
            assertEquals(case.string("expected"), gradeFor(case.time("errorMs"))?.name ?: "NoCandidate")
        }
        for (case in rows("expiry")) {
            val note = Note("a", case.time("noteAtMs"), 72, 250, 1f)
            val game = RhythmGame(Chart("Demo", 120, 4_000, listOf(note)))
            game.advance(case.time("judgementNowMs"))
            assertEquals(case.string("expected"), if (game.stats.missed == 0) "Pending" else "Missed")
        }
    }

    @Test
    fun sessionSamplesAndFinalScoresMatchTheHandoff() {
        for (session in rows("sessions")) {
            val notes = session.getValue("notes").jsonArray.map {
                val n = it.jsonObject
                Note(n.string("id"), n.time("atMs"), n.number("midi"), 250, 1f)
            }
            val game = RhythmGame(Chart("Demo", 120, 4_000, notes))
            val audio = session.getValue("tapTimesMs").jsonArray.mapNotNull {
                (game.tap(it.jsonPrimitive.long) as? TapResult.Hit)?.note?.midi
            }
            val name = session.string("name")
            assertEquals(session.getValue("expectedAudioMidi").jsonArray.map { it.jsonPrimitive.int }, audio, name)
            val result = game.finish()
            val expected = session.getValue("expectedFinal").jsonObject
            assertEquals(expected.number("perfect"), result.perfect, name)
            assertEquals(expected.number("good"), result.good, name)
            assertEquals(expected.number("okay"), result.okay, name)
            assertEquals(expected.number("missed"), result.missed, name)
            assertEquals(expected.number("stray"), result.stray, name)
            assertEquals(expected.number("score"), result.score, name)
        }
    }

    @Test
    fun scoreMatchesTheHandoffIncludingSpam() {
        for (case in rows("scores")) {
            val stats = GameStats(
                noteCount = case.number("notes"), perfect = case.number("perfect"),
                good = case.number("good"), okay = case.number("okay"), stray = case.number("stray"),
            )
            assertEquals(case.number("expectedScore"), stats.score)
        }
    }
}
