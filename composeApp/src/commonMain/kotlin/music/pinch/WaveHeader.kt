package music.pinch

internal fun waveHeader(outputRate: Int, channels: Int, dataBytes: Int): ByteArray {
    val header = ByteArray(44)
    fun word(offset: Int, value: Int, bytes: Int) {
        repeat(bytes) { header[offset + it] = (value ushr (it * 8)).toByte() }
    }
    fun tag(offset: Int, value: String) { value.encodeToByteArray().copyInto(header, offset) }
    tag(0, "RIFF"); word(4, 36 + dataBytes, 4); tag(8, "WAVE"); tag(12, "fmt ")
    word(16, 16, 4); word(20, 1, 2); word(22, channels, 2); word(24, outputRate, 4)
    word(28, outputRate * channels * 2, 4); word(32, channels * 2, 2); word(34, 16, 2)
    tag(36, "data"); word(40, dataBytes, 4)
    return header
}
