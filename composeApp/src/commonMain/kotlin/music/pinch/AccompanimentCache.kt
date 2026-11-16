package music.pinch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.pinch.core.Chart

internal fun packagedArrangement(bundledId: String?, instrument: Instrument?, exists: (String) -> Boolean): String? {
    if (bundledId == null) return null
    val base = "arrangements/$bundledId/${instrument?.name?.lowercase() ?: "band"}"
    return listOf("$base.m4a", "$base.wav").firstOrNull(exists)
}

/** The gameplay band and the sampled preview have separate files and independent lifetimes. */
internal class AccompanimentCache(
    private val renderFile: suspend (Chart, Instrument?, Int) -> String,
    private val exists: (String) -> Boolean,
) {
    private class Entry(val baseSlot: Int) {
        val mutex = Mutex()
        var chart: Chart? = null
        var instrument: Instrument? = null
        var path = ""
        var slot = 0
    }

    private val band = Entry(0)
    private val preview = Entry(2)

    suspend fun file(chart: Chart, previewInstrument: Instrument? = null): String = withContext(Dispatchers.Default) {
        val entry = if (previewInstrument == null) band else preview
        entry.mutex.withLock {
            if (entry.chart === chart && entry.instrument == previewInstrument && exists(entry.path)) {
                return@withLock entry.path
            }
            val next = 1 - entry.slot
            val ready = renderFile(chart, previewInstrument, entry.baseSlot + next)
            currentCoroutineContext().ensureActive()
            entry.slot = next
            entry.path = ready
            entry.chart = chart
            entry.instrument = previewInstrument
            ready
        }
    }
}
