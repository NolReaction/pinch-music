package music.pinch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

private fun renderGenerated(
    id: String, output: File, scratch: File, codec: File?, render: (File) -> Unit,
): File {
    val wav = scratch.resolve("$id.wav")
    val aac = scratch.resolve("$id.m4a")
    try {
        render(wav)
        val track = if (codec != null && encodeAac(codec, wav, aac, bitRate = 160_000)) aac else wav
        return output.resolve(track.name).also {
            Files.move(track.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(wav.toPath())
        Files.deleteIfExists(aac.toPath())
    }
}

/** Each song is rendered from the same score used for its playable contacts. */
fun main(args: Array<String>) {
    val source = File(args[0])
    val assets = File(args[1])
    val scratch = File(args[2])
    val codec = args[3].takeIf { it.isNotEmpty() }?.let(::File)
    val output = assets.resolve("backing")
    output.mkdirs()
    scratch.mkdirs()

    val stereoLove = output.resolve("stereo-love.m4a")
    renderRecording(source, stereoLove, scratch, codec)

    val floor = renderGenerated("on-the-floor", output, scratch, codec) { wav ->
        writeOnTheFloorMaster(wav, requireNotNull(onTheFloorSong.bundled))
    }
    val gypsy = renderGenerated("gypsy-woman", output, scratch, codec) { wav ->
        writeDanceMaster(wav, requireNotNull(gypsyWomanSong.bundled), DanceStyle.GypsyWoman)
    }
    val levels = renderGenerated("levels", output, scratch, codec) { wav ->
        writeDanceMaster(wav, requireNotNull(levelsSong.bundled), DanceStyle.Levels)
    }
    val stay = renderGenerated("stay", output, scratch, codec) { wav ->
        writeStayMaster(wav, requireNotNull(staySong.bundled))
    }
    val turnDown = renderGenerated("turn-down-for-what", output, scratch, codec) { wav ->
        writeTurnDownMaster(wav, requireNotNull(turnDownForWhatSong.bundled))
    }
    writeLaunchSound(assets.resolve("launch.wav"))
    val current = setOf(stereoLove, floor, gypsy, levels, stay, turnDown)
    output.listFiles().orEmpty().filter { it.isFile && it !in current }.forEach { Files.delete(it.toPath()) }
    // Upgrading a checkout with old generated previews must also retire its artifacts.
    val previews = assets.resolve("arrangements")
    if (previews.exists()) check(previews.deleteRecursively()) { "Cannot remove old previews at $previews" }
}
