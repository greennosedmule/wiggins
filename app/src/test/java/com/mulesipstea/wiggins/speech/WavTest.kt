package com.mulesipstea.wiggins.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test fun encodesACanonicalHeader() {
        val wav = Wav.encode(shortArrayOf(1, -2, 300), 16_000)
        assertEquals(44 + 6, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(6, b.getInt(40))
        assertEquals(-2, b.getShort(46).toInt())
    }

    @Test fun encodesAPrefix() {
        assertEquals(44 + 4, Wav.encode(shortArrayOf(1, 2, 3), 16_000, count = 2).size)
    }

    @Test fun roundTrips() {
        val samples = ShortArray(500) { (it * 37).toShort() }
        val pcm = Wav.parse(Wav.encode(samples, 22_050))
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(16, pcm.bitsPerSample)
        assertFalse(pcm.float)
        assertEquals(500, pcm.frames)
        val back = ShortArray(500).also { ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        assertArrayEquals(samples, back)
    }

    @Test fun skipsOtherChunksAndTheirPadding() {
        val wav = wav(format = 1, channels = 2, rate = 24_000, bits = 16, data = ByteArray(8) { it.toByte() }, extra = "LIST" to ByteArray(3))
        val pcm = Wav.parse(wav)
        assertEquals(2, pcm.channels)
        assertEquals(24_000, pcm.sampleRate)
        assertArrayEquals(ByteArray(8) { it.toByte() }, pcm.data)
    }

    @Test fun readsFloatAndExtensible() {
        val float = Wav.parse(wav(format = 3, channels = 1, rate = 24_000, bits = 32, data = ByteArray(8)))
        assertTrue(float.float)
        assertEquals(2, float.frames)
        val extensible = Wav.parse(wav(format = 0xFFFE, sub = 1, channels = 1, rate = 48_000, bits = 24, data = ByteArray(9)))
        assertFalse(extensible.float)
        assertEquals(24, extensible.bitsPerSample)
        assertEquals(3, extensible.frames)
    }

    @Test fun toleratesAnUnknownDataLength() {
        val wav = wav(format = 1, channels = 1, rate = 16_000, bits = 16, data = ByteArray(7), declaredData = -1)
        // Streamed: take what's there, whole frames only.
        assertEquals(6, Wav.parse(wav).data.size)
    }

    @Test(expected = WavException::class) fun rejectsNonWav() {
        Wav.parse("not a wav file at all".toByteArray())
    }

    @Test(expected = WavException::class) fun rejectsCompressedFormats() {
        Wav.parse(wav(format = 85, channels = 1, rate = 16_000, bits = 0, data = ByteArray(4)))
    }

    private fun wav(
        format: Int,
        channels: Int,
        rate: Int,
        bits: Int,
        data: ByteArray,
        sub: Int? = null,
        extra: Pair<String, ByteArray>? = null,
        declaredData: Int = data.size,
    ): ByteArray {
        val fmtSize = if (sub != null) 40 else 16
        val extraSize = extra?.let { 8 + it.second.size + it.second.size % 2 } ?: 0
        val b = ByteBuffer.allocate(12 + 8 + fmtSize + extraSize + 8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(fmtSize).putShort(format.toShort()).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (sub != null) b.putShort(22).putShort(bits.toShort()).putInt(0).putShort(sub.toShort()).put(ByteArray(14))
        extra?.let { (id, body) ->
            b.put(id.toByteArray()).putInt(body.size).put(body)
            if (body.size % 2 == 1) b.put(0)
        }
        b.put("data".toByteArray()).putInt(declaredData).put(data)
        return b.array()
    }
}
