package music.pinch

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.pinch.core.Chart

internal object AndroidAccompaniment {
    private var cache: AccompanimentCache? = null

    @Synchronized private fun cache(context: Context): AccompanimentCache {
        cache?.let { return it }
        val app = context.applicationContext
        val directory = app.cacheDir
        return AccompanimentCache({ chart, previewInstrument, slot ->
            val result = File(directory, "pinch-band-$slot.wav")
            val scratch = File(directory, "pinch-band-$slot.part")
            try {
                scratch.outputStream().buffered().use { out ->
                    renderArrangement(chart, previewInstrument, { instrument, root ->
                        app.assets.open("instruments/${instrument.name.lowercase()}/$root.wav").use {
                            it.readBytes()
                        }
                    }) { bytes, size -> out.write(bytes, 0, size) }
                }
                Os.rename(scratch.path, result.path)
                result.path
            } finally {
                scratch.delete()
            }
        }, { File(it).isFile }).also { cache = it }
    }

    suspend fun track(context: Context, chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack {
        val asset = withContext(Dispatchers.IO) {
            packagedArrangement(bundledId, previewInstrument) { name ->
                try {
                    context.assets.openFd(name).use { it.length > 0 }
                } catch (_: FileNotFoundException) {
                    false
                }
            }
        }
        return if (asset != null) ArrangementTrack(asset, bundledAsset = true)
            else ArrangementTrack(cache(context).file(chart, previewInstrument))
    }
}
