@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package music.pinch

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.memcpy
import platform.posix.rename
import platform.posix.unlink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AppleAudioImportListener {
    fun decoded(pcm16: NSData, rate: Int, channels: Int, displayName: String)
    fun cancelled()
    fun failed(message: String)
}

interface AppleReferenceListener {
    fun copied(displayName: String, durationMs: Long, suffix: String)
    fun cancelled()
    fun failed(message: String)
}

private data class AppleExcerpt(val pcm: NSData, val rate: Int, val channels: Int, val name: String)

internal suspend fun pickLocalSong(open: (AppleAudioImportListener) -> Unit): PickedAudio? {
    val excerpt = suspendCancellableCoroutine<AppleExcerpt?> { continuation ->
        open(object : AppleAudioImportListener {
            override fun decoded(pcm16: NSData, rate: Int, channels: Int, displayName: String) {
                if (continuation.isActive) continuation.resume(AppleExcerpt(pcm16, rate, channels, displayName))
            }
            override fun cancelled() {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun failed(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
        })
    } ?: return null
    return withContext(Dispatchers.Default) {
        val bytes = excerpt.pcm.length
        require(bytes in 2uL..5_760_000uL) { "Пустой или повреждённый фрагмент записи." }
        val pcm = ByteArray(bytes.toInt())
        pcm.usePinned { memcpy(it.addressOf(0), excerpt.pcm.bytes, bytes) }
        ImportRecording(excerpt.rate, excerpt.channels, pcm).picked(excerpt.name)
    }
}

internal object AppleRecordings {
    private val directory: String get() {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        return "$base/Recordings"
    }

    private fun referenceDir(id: String): String {
        require(validReferenceId(id))
        return "$directory/References/$id"
    }

    fun referenceFile(id: String, suffix: String): ArrangementTrack? {
        require(validAudioSuffix(suffix))
        val path = "${referenceDir(id)}/track.$suffix"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) ArrangementTrack(path) else null
    }

    fun removeReference(id: String) {
        NSFileManager.defaultManager.removeItemAtPath(referenceDir(id), null)
    }

    suspend fun pickReference(id: String, factory: AppleMediaFactoryProtocol): ChosenRecording? =
        suspendCancellableCoroutine { continuation ->
            factory.pickReference(referenceDir(id), object : AppleReferenceListener {
                override fun copied(displayName: String, durationMs: Long, suffix: String) {
                    if (continuation.isActive) continuation.resume(ChosenRecording(displayName, durationMs, suffix))
                    else removeReference(id)
                }
                override fun cancelled() { if (continuation.isActive) continuation.resume(null) }
                override fun failed(message: String) {
                    removeReference(id)
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            })
        }

    suspend fun save(id: String, data: ImportRecording) = withContext(Dispatchers.Default) {
        val folder = directory
        check(NSFileManager.defaultManager.createDirectoryAtPath(folder, true, null, null)) {
            "Не удалось создать папку записей."
        }
        val final = "$folder/${importFilename(id)}"
        val scratch = "$final.part"
        val file = fopen(scratch, "wb") ?: error("Не удалось открыть файл записи.")
        try {
            try {
                fun write(bytes: ByteArray) {
                    check(bytes.usePinned { fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), file) } == bytes.size.toULong()) {
                        "Не удалось записать аудиофрагмент."
                    }
                }
                write(waveHeader(data.sampleRate, data.channels, data.pcm16.size))
                write(data.pcm16)
            } finally {
                check(fclose(file) == 0) { "Не удалось закрыть файл записи." }
            }
            check(rename(scratch, final) == 0) { "Не удалось сохранить аудиофрагмент." }
        } finally {
            unlink(scratch)
        }
    }

    fun remove(id: String) { unlink("$directory/${importFilename(id)}") }
    fun track(id: String): ArrangementTrack? {
        val path = "$directory/${importFilename(id)}"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) ArrangementTrack(path) else null
    }
}
