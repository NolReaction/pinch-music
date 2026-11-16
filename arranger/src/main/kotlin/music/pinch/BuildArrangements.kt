package music.pinch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

private fun encodeAac(encoder: File, wav: File, destination: File, bitRate: Int = 96_000): Boolean {
    Files.deleteIfExists(destination.toPath())
    val command = if (encoder.name == "afconvert") {
        listOf(encoder.path, "-f", "m4af", "-d", "aac", "-b", bitRate.toString(), wav.path, destination.path)
    } else {
        listOf(encoder.path, "-nostdin", "-hide_banner", "-loglevel", "error",
            "-i", wav.path, "-c:a", "aac", "-b:a", bitRate.toString(), "-movflags", "+faststart", destination.path)
    }
    return try {
        ProcessBuilder(command).inheritIO().start().waitFor() == 0 && destination.length() > 0
    } catch (error: IOException) {
        System.err.println(error.message)
        false
    }
}

private fun renderRecording(source: File, destination: File, scratch: File, codec: File?) {
    destination.parentFile.mkdirs()
    val decoded = scratch.resolve("performance.wav")
    val mastered = scratch.resolve("master.wav")
    try {
        if (codec != null) {
            Files.deleteIfExists(decoded.toPath())
            val command = if (codec.name == "afconvert") {
                listOf(codec.path, "-f", "WAVE", "-d", "LEI16", source.path, decoded.path)
            } else {
                listOf(codec.path, "-v", "error", "-nostdin", "-i", source.path, "-c:a", "pcm_s16le", decoded.path)
            }
            if (ProcessBuilder(command).inheritIO().start().waitFor() == 0) {
                masterStereoLove(decoded, mastered, requireNotNull(stereoLoveSong.bundled))
                if (encodeAac(codec, mastered, destination, bitRate = 160_000)) return
            }
        }
        // Tool-less builds still ship the original, score-aligned performance.
        System.err.println("Using the recorded Stereo Love mix without mastering: AAC codec unavailable.")
        source.copyTo(destination, overwrite = true)
    } finally {
        Files.deleteIfExists(decoded.toPath())
        Files.deleteIfExists(mastered.toPath())
    }
}

/** Reuse the app's PCM renderer. Each file starts at chart zero, including any written rests. */
fun main(args: Array<String>): Unit = runBlocking {
    val roots = File(args[0])
    val output = File(args[1], "arrangements")
    val scratch = File(args[2])
    var encoder = args[3].takeIf { it.isNotEmpty() }?.let(::File)
    scratch.mkdirs()
    val current = mutableSetOf<File>()
    for (song in songLibrary) {
        val score = requireNotNull(song.bundled)
        val destination = output.resolve(song.id)
        destination.mkdirs()
        for (instrument in listOf(null) + Instrument.entries) {
            val name = instrument?.name?.lowercase() ?: "band"
            val wav = scratch.resolve("track.wav")
            wav.outputStream().buffered().use { stream ->
                renderArrangement(score, instrument, { voice, pitch ->
                    roots.resolve("${voice.name.lowercase()}/$pitch.wav").readBytes()
                }) { bytes, length -> stream.write(bytes, 0, length) }
            }
            val aac = scratch.resolve("track.m4a")
            val codec = encoder
            val track = when {
                codec == null -> wav
                encodeAac(codec, wav, aac) -> aac
                else -> {
                    System.err.println("AAC unavailable from ${codec.name}. Packaging PCM WAV.")
                    Files.deleteIfExists(aac.toPath())
                    encoder = null
                    wav
                }
            }
            val file = destination.resolve("$name.${track.extension}")
            Files.move(track.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            current.add(file)
        }
    }
    renderRecording(roots.parentFile.resolve("backing/stereo-love.m4a"),
        File(args[1], "backing/stereo-love.m4a"), scratch, encoder)
    writeLaunchSound(File(args[1], "launch.wav"))
    // A removed score or a changed encoder must not leave an old track in the application.
    output.walkBottomUp().filter { it.isFile && it !in current }.forEach { Files.delete(it.toPath()) }
    Files.deleteIfExists(scratch.resolve("track.wav").toPath())
}
