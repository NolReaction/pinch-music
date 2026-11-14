@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AppleAudioImportListener {
    fun decoded(samples: NSData)
    fun cancelled()
    fun failed(message: String)
}

internal suspend fun pickLocalSong(open: (AppleAudioImportListener) -> Unit): FloatArray? =
    suspendCancellableCoroutine { continuation ->
        open(object : AppleAudioImportListener {
            override fun decoded(samples: NSData) {
                if (!continuation.isActive) return
                val bytes = samples.length
                if (bytes == 0uL || bytes > 30uL * 11_025uL * 4uL || bytes % 4uL != 0uL) {
                    failed("Пустой или повреждённый фрагмент аудио")
                    return
                }
                val pcm = FloatArray((bytes / 4uL).toInt())
                pcm.usePinned { memcpy(it.addressOf(0), samples.bytes, bytes) }
                continuation.resume(pcm)
            }

            override fun cancelled() {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun failed(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
        })
    }
