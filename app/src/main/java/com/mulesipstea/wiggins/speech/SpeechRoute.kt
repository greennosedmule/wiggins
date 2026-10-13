package com.mulesipstea.wiggins.speech

import android.media.AudioAttributes
import android.media.AudioDeviceInfo

/**
 * Where Wiggins' speech goes and its listening comes from. Normally that's assistant
 * usage, which Android routes like media. While a Bluetooth hands-free link is up (a car,
 * a headset; see [HandsFree]) it's that link both ways: a car in voice-recognition mode
 * mutes media and expects the assistant on the hands-free channel.
 */
data class SpeechRoute(
    /** Attributes for speech and the listening tone. */
    val playback: AudioAttributes,
    /** The output to prefer, or null for Android's choice. */
    val output: AudioDeviceInfo? = null,
    /** The microphone to prefer, or null for Android's choice. */
    val input: AudioDeviceInfo? = null,
) {
    /** Whether this is the Bluetooth hands-free link. */
    val handsFree get() = output?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    companion object {
        val DEFAULT = SpeechRoute(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )

        /** Voice-communication usage follows the communication device, which [HandsFree] sets to the link. */
        fun handsFree(output: AudioDeviceInfo, input: AudioDeviceInfo?) = SpeechRoute(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            output,
            input,
        )
    }
}
