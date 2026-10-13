package com.mulesipstea.wiggins.speech

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.mulesipstea.wiggins.settings.ListeningTone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The listening tone (SPEC "Voice pipeline"): a short rising two-note chime played just
 * before Wiggins starts listening, so the user knows when to speak without looking. It
 * plays on the route Wiggins' speech uses, which is also where the user is listening.
 */
class ListeningCue(private val audio: AudioManager) {
    /** Plays the tone if [setting] calls for it on the current route; returns once it's heard. */
    suspend fun playIfWanted(setting: ListeningTone) {
        if (!setting.shouldPlay(onBluetooth())) return
        try {
            PcmPlayer.play(TONE)
        } catch (e: Exception) {
            // A missing tone mustn't stop the listening it announces.
            Log.w(TAG, "listening tone failed", e)
        }
    }

    /** Whether speech would play on a Bluetooth device now: a car, earbuds or a headset. */
    private fun onBluetooth(): Boolean =
        audio.getAudioDevicesForAttributes(SPEECH).any { it.type in BLUETOOTH_TYPES }

    companion object {
        private const val TAG = "ListeningCue"
        private const val SAMPLE_RATE = 24_000

        private val SPEECH = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        val TONE: Pcm by lazy { Pcm(SAMPLE_RATE, 1, 16, float = false, data = chime(SAMPLE_RATE)) }

        /**
         * Two 70 ms notes, E5 then A5, at a third of full scale, each with 8 ms fades so
         * they don't click. 16-bit little-endian mono.
         */
        internal fun chime(sampleRate: Int): ByteArray {
            val noteFrames = sampleRate * 70 / 1000
            val fadeFrames = sampleRate * 8 / 1000
            val notes = doubleArrayOf(659.25, 880.0)
            val out = ByteBuffer.allocate(noteFrames * notes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (frequency in notes) {
                for (i in 0 until noteFrames) {
                    val envelope = min(1.0, min(i, noteFrames - 1 - i).toDouble() / fadeFrames)
                    val sample = sin(2 * PI * frequency * i / sampleRate) * envelope * AMPLITUDE
                    out.putShort((sample * Short.MAX_VALUE).toInt().toShort())
                }
            }
            return out.array()
        }

        internal const val AMPLITUDE = 0.33
    }
}
