package com.mulesipstea.wiggins.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Plays the hub's speech: a WAV file's PCM through an [AudioTrack] with assistant
 * usage, at whatever rate and format the hub's engine produced.
 */
object PcmPlayer {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Decodes and plays a base64 WAV file; returns when it has been heard, or throws if it can't be played. */
    suspend fun play(wavBase64: String) {
        val pcm = withContext(Dispatchers.Default) { Wav.parse(Base64.getMimeDecoder().decode(wavBase64)) }
        if (pcm.frames == 0) return
        play(pcm)
    }

    suspend fun play(pcm: Pcm) = withContext(Dispatchers.IO) {
        val encoding = when {
            pcm.float -> AudioFormat.ENCODING_PCM_FLOAT
            pcm.bitsPerSample == 8 -> AudioFormat.ENCODING_PCM_8BIT
            pcm.bitsPerSample == 16 -> AudioFormat.ENCODING_PCM_16BIT
            pcm.bitsPerSample == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> AudioFormat.ENCODING_PCM_32BIT
        }
        val channelMask = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(pcm.sampleRate).setChannelMask(channelMask).build()
        val minBuffer = AudioTrack.getMinBufferSize(pcm.sampleRate, channelMask, encoding)
        require(minBuffer > 0) { "AudioTrack can't play ${pcm.sampleRate} Hz, ${pcm.bitsPerSample}-bit, ${pcm.channels} channels" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.sampleRate * pcm.frameBytes / 5))
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < pcm.data.size) {
                ensureActive()
                val chunk = minOf(CHUNK_BYTES, pcm.data.size - offset)
                val written = track.write(ByteBuffer.wrap(pcm.data, offset, chunk), chunk, AudioTrack.WRITE_BLOCKING)
                require(written >= 0) { "AudioTrack.write failed: $written" }
                offset += written
            }
            // Wait until the last frame has been played, not just queued.
            val deadline = System.nanoTime() + (pcm.frames * 1_000_000_000L / pcm.sampleRate) + 2_000_000_000L
            while (track.playbackHeadPosition < pcm.frames && System.nanoTime() < deadline) delay(20)
        } finally {
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    private const val CHUNK_BYTES = 8 * 1024
}
