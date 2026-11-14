package music.pinch

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The picker owns its transient URI grant; only the extracted note chart is retained. */
internal class AndroidSongImport(private val activity: ComponentActivity) {
    private var pending: CancellableContinuation<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val continuation = pending
        pending = null
        if (continuation?.isActive == true) continuation.resume(uri)
    }

    suspend fun pickAudio(): FloatArray? {
        val uri = withContext(Dispatchers.Main.immediate) {
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
        } ?: return null
        return withContext(Dispatchers.IO) { readSongExcerpt(activity.applicationContext, uri) }
    }
}

private const val OUTPUT_RATE = 11_025
private const val EXCERPT_SECONDS = 30
private const val MAX_FRAMES = OUTPUT_RATE * EXCERPT_SECONDS

private suspend fun readSongExcerpt(context: Context, uri: Uri): FloatArray {
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
        val sink = MonoExcerpt()
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
    extractor: MediaExtractor, type: String, source: MediaFormat, startUs: Long, sink: MonoExcerpt
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

/** Downmix and average input frames into target-rate time bins without retaining the full recording. */
private class MonoExcerpt {
    private val pcm = FloatArray(MAX_FRAMES)
    private var count = 0
    private var phase = 0
    private var frameSum = 0f
    private var frames = 0
    val full get() = count == pcm.size

    fun append(bytes: ByteBuffer, format: MediaFormat, timeUs: Long, startUs: Long) {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        require(channels in 1..32 && rate in 4_000..384_000) { "Неизвестный формат звука" }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val width = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Неподдерживаемый формат PCM")
        }
        val stride = width * channels
        val availableFrames = bytes.remaining() / stride
        val skip = (((startUs - timeUs).coerceAtLeast(0L) * rate) / 1_000_000L)
            .coerceAtMost(availableFrames.toLong()).toInt()
        bytes.position(bytes.position() + skip * stride)
        while (!full && bytes.remaining() >= stride) {
            var value = 0f
            repeat(channels) {
                value += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((bytes.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> bytes.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val lo = bytes.get().toInt() and 255
                        val mid = bytes.get().toInt() and 255
                        val hi = bytes.get().toInt()
                        (lo or (mid shl 8) or (hi shl 16)) / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> bytes.int / 2147483648f
                    else -> bytes.float
                }
            }
            frameSum += value / channels
            frames++
            phase += OUTPUT_RATE
            if (phase >= rate) {
                val mean = (frameSum / frames).coerceIn(-1f, 1f)
                while (phase >= rate && !full) {
                    pcm[count++] = mean
                    phase -= rate
                }
                frameSum = 0f
                frames = 0
            }
        }
    }

    fun result(): FloatArray {
        require(count >= OUTPUT_RATE) { "В файле меньше секунды звука" }
        return pcm.copyOf(count)
    }
}
