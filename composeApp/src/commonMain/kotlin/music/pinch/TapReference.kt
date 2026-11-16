package music.pinch

import kotlinx.serialization.json.*
import kotlin.math.abs

/** Absolute positions in the audio file, measured independently of chart targets. */
data class TapTake(val startMs: Long, val endMs: Long, val taps: List<Long>, val version: String, val sourceName: String = "Игровая версия",
    val offsetMs: Long = 0, val chartVersionAtCapture: String? = null)
data class ReferenceState(val takes: List<TapTake>, val chartVersion: String, val durationMs: Long,
    val fileName: String? = null, val fileId: String? = null, val usingFile: Boolean = false, val choosing: Boolean = false,
    val recording: Boolean = false, val listening: Boolean = false, val count: Int = 0, val message: String? = null) {
    val version: String get() = if (usingFile) requireNotNull(fileId) else chartVersion
    val sourceName: String get() = if (usingFile) requireNotNull(fileName) else "Игровая версия"
}

internal class TapReferences(private val platform: Platform, private val key: String) {
    fun load(): List<TapTake> {
        val data = platform.readValue(key) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(data).jsonArray }.getOrNull().orEmpty().mapNotNull { row ->
            runCatching {
                val el = row.jsonObject
                val start = el.getValue("start").jsonPrimitive.long
                val end = el.getValue("end").jsonPrimitive.long
                val taps = el.getValue("taps").jsonArray.map { it.jsonPrimitive.long }
                require(start >= 0 && end >= start && taps.all { it in start..end })
                require(taps.zipWithNext().all { (a, b) -> a < b })
                TapTake(start, end, taps, el.getValue("version").jsonPrimitive.content,
                    el["source"]?.jsonPrimitive?.content ?: "Игровая версия", el["offset"]?.jsonPrimitive?.long ?: 0L,
                    el["chartVersion"]?.jsonPrimitive?.content)
            }.getOrNull()
        }
    }

    fun save(take: TapTake): List<TapTake> {
        return write(listOf(take) + load())
    }

    fun setOffset(take: TapTake, ms: Long): List<TapTake> {
        val stored = load().toMutableList()
        val index = stored.indexOf(take)
        if (index >= 0) stored[index] = take.copy(offsetMs = ms)
        return write(stored)
    }

    private fun write(all: List<TapTake>): List<TapTake> {
        platform.writeValue(key, buildJsonArray {
            all.forEach { item -> add(buildJsonObject {
                put("start", item.startMs)
                put("end", item.endMs)
                put("version", item.version)
                put("source", item.sourceName)
                put("offset", item.offsetMs)
                item.chartVersionAtCapture?.let { put("chartVersion", it) }
                putJsonArray("taps") { item.taps.forEach { add(it) } }
            }) }
        }.toString())
        return all
    }
}

internal data class MatchedTap(val tap: Long, val panel: Long) {
    val difference get() = tap - panel
}

internal data class TapComparison(val matched: List<MatchedTap>, val tapsOnly: List<Long>, val panelsOnly: List<Long>) {
    val medianMs: Long? get() {
        val values = matched.map { it.difference }.sorted()
        return if (values.isEmpty()) null else (values[(values.size - 1) / 2] + values[values.size / 2]) / 2
    }
}

internal const val TAP_TOLERANCE_MS = 150L

/** Maximum ordered one-to-one matches, then minimum summed absolute error.
 * Only panel/tap pairs within the comparison window become candidates. */
internal fun compareTaps(take: TapTake, allPanels: List<Long>, offsetMs: Long = take.offsetMs,
    toleranceMs: Long = TAP_TOLERANCE_MS): TapComparison {
    val panels = allPanels.map { it + offsetMs }.filter { it in take.startMs..take.endMs }.distinct().sorted()
    val taps = take.taps
    data class Match(val tap: Int, val panel: Int, val count: Int, val error: Long, val before: Match?)
    // Prefix maxima in a Fenwick tree. A tap's batch is committed only after all of its
    // candidates have been read, so two panel matches can never consume the same tap.
    val tree = arrayOfNulls<Match>(panels.size + 1)
    fun better(a: Match?, b: Match?) = when {
        a == null -> b
        b == null -> a
        b.count > a.count || b.count == a.count && b.error < a.error -> b
        else -> a
    }
    fun best(before: Int): Match? {
        var i = before
        var result: Match? = null
        while (i > 0) {
            result = better(result, tree[i])
            i -= i and -i
        }
        return result
    }
    var lo = 0
    val candidates = mutableListOf<Match>()
    for ((t, at) in taps.withIndex()) {
        while (lo < panels.size && panels[lo] < at - toleranceMs) lo++
        var p = lo
        while (p < panels.size && panels[p] <= at + toleranceMs) {
            val prior = best(p)
            candidates += Match(t, p, (prior?.count ?: 0) + 1, (prior?.error ?: 0) + abs(panels[p] - at), prior)
            p++
        }
        for (candidate in candidates) {
            var i = candidate.panel + 1
            while (i < tree.size) {
                tree[i] = better(tree[i], candidate)
                i += i and -i
            }
        }
        candidates.clear()
    }
    val matched = generateSequence(best(panels.size)) { it.before }.toList().asReversed()
    val usedTaps = matched.mapTo(mutableSetOf()) { it.tap }
    val usedPanels = matched.mapTo(mutableSetOf()) { it.panel }
    return TapComparison(matched.map { MatchedTap(taps[it.tap], panels[it.panel]) },
        taps.filterIndexed { i, _ -> i !in usedTaps }, panels.filterIndexed { i, _ -> i !in usedPanels })
}

internal fun tapReport(song: Song, take: TapTake, version: String, comparison: TapComparison, offsetMs: Long = take.offsetMs): String = buildString {
    appendLine("В такт · Сверка ритма · ${song.title}")
    appendLine("track=${song.id} reference=${take.version} chart=$version chart_at_capture=${take.chartVersionAtCapture ?: "—"}")
    appendLine("source=${take.sourceName} chart_offset_ms=$offsetMs")
    appendLine("start_ms=${take.startMs} end_ms=${take.endMs} tolerance_ms=$TAP_TOLERANCE_MS")
    appendLine("median_tap_minus_panel_ms=${comparison.medianMs ?: "—"}")
    appendLine("tap_ms\tpanel_aligned_ms\ttap_minus_panel_ms\tpanel_chart_ms")
    val rows = comparison.matched.map { it.tap to "${it.tap}\t${it.panel}\t${it.difference}\t${it.panel - offsetMs}" } +
        comparison.tapsOnly.map { it to "$it\t—\t—\t—" } +
        comparison.panelsOnly.map { it to "—\t$it\t—\t${it - offsetMs}" }
    rows.sortedBy { it.first }.forEach { appendLine(it.second) }
}
