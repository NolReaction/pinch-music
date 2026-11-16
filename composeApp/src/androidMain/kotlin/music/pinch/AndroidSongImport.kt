package music.pinch

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Copy the selected excerpt while the picker grants access to its URI. */
internal class AndroidSongImport(private val activity: ComponentActivity) {
    private var pending: CancellableContinuation<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val continuation = pending
        pending = null
        if (continuation?.isActive == true) continuation.resume(uri)
    }

    private suspend fun chooseUri(): Uri? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Uri?> { next ->
            if (pending != null) {
                next.resumeWithException(IllegalStateException("Выбор файла уже открыт"))
            } else {
                pending = next
                try {
                    picker.launch(arrayOf("audio/*", "video/mp4"))
                } catch (error: RuntimeException) {
                    pending = null
                    next.resumeWithException(error)
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri) = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    suspend fun pickAudio(): PickedAudio? {
        val uri = chooseUri() ?: return null
        return withContext(Dispatchers.IO) {
            val context = activity.applicationContext
            readSongExcerpt(context, uri).picked(displayName(context, uri))
        }
    }

    private fun referenceDir(id: String): File {
        require(validReferenceId(id))
        return File(activity.filesDir, "references/$id")
    }

    fun referenceFile(id: String, suffix: String): ArrangementTrack? {
        require(validAudioSuffix(suffix))
        return File(referenceDir(id), "track.$suffix").takeIf { it.isFile }?.let { ArrangementTrack(it.path) }
    }

    fun removeReference(id: String) { referenceDir(id).deleteRecursively() }

    suspend fun pickReference(id: String): ChosenRecording? {
        val uri = chooseUri() ?: return null
        return withContext(Dispatchers.IO) {
            val context = activity.applicationContext
            val name = displayName(context, uri) ?: "Моя фонограмма"
            val suffix = name.substringAfterLast('.', "").lowercase().takeIf(::validAudioSuffix) ?: "audio"
            val directory = referenceDir(id)
            check(directory.mkdirs())
            val dest = File(directory, "track.$suffix")
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            val inspector = MediaMetadataRetriever()
            try {
                inspector.setDataSource(dest.path)
                val milliseconds = inspector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                require(milliseconds != null && milliseconds > 0 &&
                    inspector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") {
                    "Не удалось прочитать звуковую дорожку."
                }
                ChosenRecording(name, milliseconds, suffix)
            } finally { inspector.release() }
        }
    }

}

private const val EXCERPT_SECONDS = 30

private suspend fun readSongExcerpt(context: Context, uri: Uri): ImportRecording {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("В файле нет звуковой дорожки")
        extractor.selectTrack(track)
        val source = extractor.getTrackFormat(track)
        val durationUs = if (source.containsKey(MediaFormat.KEY_DURATION)) source.getLong(MediaFormat.KEY_DURATION) else 0L
        val startUs = if (durationUs >= 70_000_000) minOf(durationUs / 3, 55_000_000) else 0L
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val sink = RecordingExcerpt()
        val type = requireNotNull(source.getString(MediaFormat.KEY_MIME))
        if (type == "audio/raw") {
            // A WAV track returned by MediaExtractor already contains PCM.
            val buffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
            while (!sink.full) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                buffer.position(0)
                buffer.limit(bytes)
                sink.append(buffer, source, extractor.sampleTime, startUs)
                if (!extractor.advance()) break
            }
        } else {
            decodeCompressed(extractor, type, source, startUs, sink)
        }
        return sink.result()
    } finally {
        extractor.release()
    }
}

private suspend fun decodeCompressed(
    extractor: MediaExtractor, type: String, source: MediaFormat, startUs: Long, sink: RecordingExcerpt
) {
    val codec = MediaCodec.createDecoderByType(type)
    try {
        codec.configure(source, null, null, 0)
        codec.start()
        var format = source
        var inputEnded = false
        var lastProgress = SystemClock.elapsedRealtime()
        val info = MediaCodec.BufferInfo()
        while (!sink.full) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() - lastProgress < 30_000) { "Декодер не ответил. Попробуй файл WAV или MP3." }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(index))
                    val bytes = extractor.readSampleData(input, 0)
                    if (bytes < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, bytes, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            val index = codec.dequeueOutputBuffer(info, 5_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
            if (index >= 0) {
                try {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.append(buffer, format, info.presentationTimeUs, startUs)
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                lastProgress = SystemClock.elapsedRealtime()
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    } finally {
        codec.release()
    }
}

/** Capture at the source rate through 48 kHz. Larger rates are averaged into bounded time bins. */
private class RecordingExcerpt {
    private var sourceRate = 0
    private var rate = 0
    private var channels = 0
    private var pcm = ByteArray(0)
    private var offset = 0
    private var phase = 0
    private var leftSum = 0f
    private var rightSum = 0f
    private var frames = 0
    val full get() = pcm.isNotEmpty() && offset == pcm.size

    fun append(bytes: ByteBuffer, format: MediaFormat, timeUs: Long, startUs: Long) {
        val n = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val hz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        require(n in 1..32 && hz in 4_000..384_000) { "Неизвестный формат звука" }
        if (sourceRate == 0) {
            sourceRate = hz
            channels = minOf(2, n)
            rate = minOf(48_000, hz)
            pcm = ByteArray(rate * EXCERPT_SECONDS * channels * 2)
        }
        require(hz == sourceRate && minOf(2, n) == channels) { "Формат записи изменился внутри фрагмента." }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val width = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Неподдерживаемый формат PCM")
        }
        val stride = width * n
        val skip = (((startUs - timeUs).coerceAtLeast(0L) * hz) / 1_000_000L)
            .coerceAtMost((bytes.remaining() / stride).toLong()).toInt()
        bytes.position(bytes.position() + skip * stride)
        while (!full && bytes.remaining() >= stride) {
            var left = 0f
            var right = 0f
            for (channel in 0 until n) {
                val sample = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((bytes.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> bytes.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val lo = bytes.get().toInt() and 255
                        val mid = bytes.get().toInt() and 255
                        (lo or (mid shl 8) or (bytes.get().toInt() shl 16)) / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> bytes.int / 2147483648f
                    else -> bytes.float.let { if (it.isFinite()) it else 0f }
                }
                if (channel % 2 == 0) left += sample else right += sample
            }
            leftSum += left / ((n + 1) / 2)
            if (channels == 2) rightSum += right / (n / 2)
            frames++
            phase += rate
            if (phase >= hz) {
                write(leftSum / frames)
                if (channels == 2) write(rightSum / frames)
                leftSum = 0f
                rightSum = 0f
                frames = 0
                phase -= hz
            }
        }
    }

    private fun write(value: Float) {
        val word = (value.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)
        pcm[offset++] = word.toByte()
        pcm[offset++] = (word shr 8).toByte()
    }

    fun result(): ImportRecording {
        require(rate > 0 && offset >= rate * channels * 2) { "В файле меньше секунды звука" }
        return ImportRecording(rate, channels, if (offset == pcm.size) pcm else pcm.copyOf(offset))
    }
}

internal class AndroidRecordings(context: Context) {
    private val directory = File(context.filesDir, "recordings")
    private fun file(id: String) = File(directory, importFilename(id))

    suspend fun save(id: String, data: ImportRecording) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку записей." }
        val dest = file(id)
        val scratch = File.createTempFile("excerpt-", ".part", directory)
        try {
            scratch.outputStream().use { out ->
                out.write(waveHeader(data.sampleRate, data.channels, data.pcm16.size))
                out.write(data.pcm16)
            }
            Os.rename(scratch.path, dest.path)
        } finally {
            scratch.delete()
        }
    }

    fun remove(id: String) { file(id).delete() }
    fun track(id: String): ArrangementTrack? = file(id).takeIf { it.isFile && it.length() > 44 }
        ?.let { ArrangementTrack(it.absolutePath) }
}
