@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.pinch.core.Chart
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSTemporaryDirectory
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.unlink

private val appleAccompaniment = AccompanimentCache({ chart, previewInstrument, slot ->
    val base = NSTemporaryDirectory().trimEnd('/') + "/pinch-band-$slot"
    val finalPath = "$base.wav"
    val scratch = "$base.part"
    val file = fopen(scratch, "wb") ?: error("Не удалось открыть временную фонограмму.")
    try {
        try {
            renderArrangement(chart, previewInstrument, ::readInstrumentSample) { bytes, size ->
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1uL, size.toULong(), file) }
                check(written == size.toULong()) { "Не удалось записать фонограмму." }
            }
        } finally {
            check(fclose(file) == 0) { "Не удалось закрыть фонограмму." }
        }
        check(rename(scratch, finalPath) == 0) { "Не удалось сохранить фонограмму." }
        finalPath
    } finally {
        unlink(scratch)
    }
}, { access(it, F_OK) == 0 })

private fun readInstrumentSample(instrument: Instrument, root: Int): ByteArray {
    val resources = NSBundle.mainBundle.resourcePath ?: error("Отсутствуют ресурсы игры.")
    val path = "$resources/media/instruments/${instrument.name.lowercase()}/$root.wav"
    val file = fopen(path, "rb") ?: error("Не удалось открыть сэмпл инструмента.")
    try {
        check(fseek(file, 0, SEEK_END) == 0)
        val length = ftell(file)
        check(length in 44..1_048_576 && fseek(file, 0, SEEK_SET) == 0)
        return ByteArray(length.toInt()).also { bytes ->
            val read = bytes.usePinned { fread(it.addressOf(0), 1uL, length.toULong(), file) }
            check(read == length.toULong()) { "Не удалось прочитать сэмпл инструмента." }
        }
    } finally {
        fclose(file)
    }
}

internal suspend fun appleArrangementTrack(chart: Chart, bundledId: String?, previewInstrument: Instrument?): ArrangementTrack {
    val asset = withContext(Dispatchers.Default) {
        val directory = NSBundle.mainBundle.resourcePath
        if (directory == null) null else packagedArrangement(bundledId, previewInstrument) {
            access("$directory/$it", F_OK) == 0
        }?.let { "$directory/$it" }
    }
    return ArrangementTrack(asset ?: appleAccompaniment.file(chart, previewInstrument))
}
