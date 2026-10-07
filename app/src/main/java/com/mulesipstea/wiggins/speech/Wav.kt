package com.mulesipstea.wiggins.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linear PCM audio: interleaved little-endian samples, as in a WAV file's data chunk. */
class Pcm(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    /** True for IEEE float samples (32-bit), false for integers. */
    val float: Boolean,
    val data: ByteArray,
) {
    val frameBytes get() = channels * bitsPerSample / 8
    val frames get() = data.size / frameBytes
}

class WavException(message: String) : Exception(message)

/** WAV files: Wiggins writes them for the hub's STT and reads the hub's TTS replies. */
object Wav {
    private const val HEADER_BYTES = 44
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /** A canonical 44-byte-header WAV file of 16-bit mono [samples] at [sampleRate]. */
    fun encode(samples: ShortArray, sampleRate: Int, count: Int = samples.size): ByteArray {
        val dataBytes = count * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(FORMAT_PCM.toShort()).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until count) buffer.putShort(samples[i])
        return buffer.array()
    }

    /**
     * Reads a WAV file's format and samples. Accepts integer PCM of 8 to 32 bits and
     * 32-bit float, plain or in WAVE_FORMAT_EXTENSIBLE, skips chunks it doesn't need,
     * and tolerates the unknown data length streaming encoders write.
     */
    fun parse(bytes: ByteArray): Pcm {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(buffer, 0) != "RIFF" || tag(buffer, 8) != "WAVE") throw WavException("Not a WAV file")
        var position = 12
        var format: IntArray? = null // formatTag, channels, sampleRate, bitsPerSample
        while (position + 8 <= bytes.size) {
            val id = tag(buffer, position)
            val declared = buffer.getInt(position + 4).toLong() and 0xFFFFFFFFL
            val start = position + 8
            val available = (bytes.size - start).toLong()
            when (id) {
                "fmt " -> {
                    if (declared < 16 || available < 16) throw WavException("Truncated format chunk")
                    var tag = buffer.getShort(start).toInt() and 0xFFFF
                    if (tag == FORMAT_EXTENSIBLE && declared >= 26) tag = buffer.getShort(start + 24).toInt() and 0xFFFF
                    format = intArrayOf(
                        tag,
                        buffer.getShort(start + 2).toInt() and 0xFFFF,
                        buffer.getInt(start + 4),
                        buffer.getShort(start + 14).toInt() and 0xFFFF,
                    )
                }
                "data" -> {
                    val (tag, channels, rate, bits) = format ?: throw WavException("Data before format")
                    val isFloat = when {
                        tag == FORMAT_PCM && bits in setOf(8, 16, 24, 32) -> false
                        tag == FORMAT_FLOAT && bits == 32 -> true
                        else -> throw WavException("Unsupported WAV format $tag with $bits-bit samples")
                    }
                    if (channels !in 1..2 || rate <= 0) throw WavException("Unsupported WAV: $channels channels at $rate Hz")
                    // Streaming encoders write 0 or 0xFFFFFFFF when they don't know the length.
                    val length = if (declared == 0L || declared > available) available else declared
                    val frameBytes = channels * bits / 8
                    val usable = (length - length % frameBytes).toInt()
                    return Pcm(rate, channels, bits, isFloat, bytes.copyOfRange(start, start + usable))
                }
            }
            // Chunks are padded to an even length.
            position = (start + declared + (declared and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        throw WavException("No audio data")
    }

    private fun tag(buffer: ByteBuffer, at: Int) = String(ByteArray(4) { buffer.get(at + it) }, Charsets.US_ASCII)
}
