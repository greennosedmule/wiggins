package com.mulesipstea.wiggins.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.settings.ListeningTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** The listening tone: when it plays, and that it's short, quiet enough and click-free. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ListeningCueTest {
    @Test fun settingDecidesByRoute() {
        assertFalse(ListeningTone.NEVER.shouldPlay(onBluetooth = true))
        assertTrue(ListeningTone.ALWAYS.shouldPlay(onBluetooth = false))
        assertTrue(ListeningTone.BLUETOOTH.shouldPlay(onBluetooth = true))
        assertFalse(ListeningTone.BLUETOOTH.shouldPlay(onBluetooth = false))
    }

    @Test fun toneIsShortQuietAndFaded() {
        val tone = ListeningCue.TONE
        assertEquals(16, tone.bitsPerSample)
        assertEquals(1, tone.channels)
        assertEquals(140, tone.frames * 1000 / tone.sampleRate) // two 70 ms notes
        val samples = ByteBuffer.wrap(tone.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .let { b -> ShortArray(b.remaining()).also { b.get(it) } }
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 9_000..(ListeningCue.AMPLITUDE * Short.MAX_VALUE).toInt() + 1)
        // Each note starts and ends at silence, so nothing clicks.
        val note = samples.size / 2
        listOf(0, note - 1, note, samples.size - 1).forEach { assertEquals("sample $it", 0, samples[it].toInt()) }
    }
}
