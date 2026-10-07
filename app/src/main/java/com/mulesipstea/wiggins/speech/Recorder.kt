package com.mulesipstea.wiggins.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Says whether a 20 ms frame of 16 kHz mono audio is speech. Used from one thread. */
interface VoiceDetector : AutoCloseable {
    fun isSpeech(frame: ShortArray): Boolean
}

/**
 * Records one utterance at a time for the hub's STT (SPEC "Hub speech-to-text"):
 * 16 kHz mono 16-bit from the VOICE_RECOGNITION source, with transient audio focus,
 * ending it with [Endpointer]. Audio stays in memory and is dropped afterwards.
 */
class Recorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val detector: () -> VoiceDetector,
) {
    /** What happened to the utterance; delivered on [scope]'s thread. */
    interface Listener {
        /** A pause: [wav] gives the audio so far. */
        fun onPause(wav: () -> ByteArray)
        fun onResume()

        /** The utterance is over; [wav] gives all of it. */
        fun onEnd(wav: () -> ByteArray)

        /** Nothing worth sending: no speech, or the microphone failed. */
        fun onNothing(micFailed: Boolean)
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var job: Job? = null
    @Volatile private var stopRequested = false

    private val _level = MutableStateFlow(0f)

    /** The input level, 0 to 1, while recording. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    /** Starts recording an utterance, replacing any in progress (which is discarded). */
    fun start(listener: Listener) {
        cancel()
        stopRequested = false
        _recording.value = true
        audio.requestAudioFocus(focus)
        job = scope.launch {
            try {
                record(listener)
            } finally {
                audio.abandonAudioFocusRequest(focus)
                _level.value = 0f
                _recording.value = false
            }
        }
    }

    /** Ends the utterance now, as if speech had ended (the stop button). */
    fun finish() {
        stopRequested = true
    }

    /** Discards the utterance; the listener hears nothing more. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // Checked by the caller; a revoked permission fails below.
    private suspend fun record(listener: Listener) {
        val frame = ShortArray(FRAME_SAMPLES)
        val samples = SampleBuffer(SAMPLE_RATE * (MAX_UTTERANCE_MS + NO_SPEECH_MS) / 1000)
        val endpointer = Endpointer(frameMs = FRAME_MS, noSpeechMs = NO_SPEECH_MS, maxUtteranceMs = MAX_UTTERANCE_MS)
        var startSample = 0
        fun wav(): () -> ByteArray {
            val end = samples.size
            val from = startSample
            return { samples.wav(from, end) }
        }
        withContext(Dispatchers.IO) {
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SAMPLES * 2 * 10),
                )
            } catch (e: Exception) {
                Log.w(TAG, "can't open the microphone", e)
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                withContext(scope.coroutineContext) { listener.onNothing(micFailed = true) }
                return@withContext
            }
            val vad = detector()
            try {
                record.startRecording()
                while (isActive) {
                    if (!read(record, frame)) {
                        withContext(scope.coroutineContext) { listener.onNothing(micFailed = true) }
                        return@withContext
                    }
                    samples.add(frame)
                    _level.value = level(frame)
                    if (stopRequested) {
                        val heard = endpointer.heardSpeech
                        withContext(scope.coroutineContext) { if (heard) listener.onEnd(wav()) else listener.onNothing(micFailed = false) }
                        return@withContext
                    }
                    when (endpointer.onFrame(vad.isSpeech(frame))) {
                        Endpointer.Event.STARTED ->
                            startSample = maxOf(0, (endpointer.speechStartMs - Endpointer.LEAD_IN_MS) * SAMPLE_RATE / 1000)
                        Endpointer.Event.PAUSED -> withContext(scope.coroutineContext) { listener.onPause(wav()) }
                        Endpointer.Event.RESUMED -> withContext(scope.coroutineContext) { listener.onResume() }
                        Endpointer.Event.ENDED, Endpointer.Event.CAPPED -> {
                            withContext(scope.coroutineContext) { listener.onEnd(wav()) }
                            return@withContext
                        }
                        Endpointer.Event.NO_SPEECH -> {
                            withContext(scope.coroutineContext) { listener.onNothing(micFailed = false) }
                            return@withContext
                        }
                        null -> Unit
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                vad.close()
            }
        }
    }

    /** Fills [frame]; false if the microphone failed. */
    private fun read(record: AudioRecord, frame: ShortArray): Boolean {
        var filled = 0
        while (filled < frame.size) {
            val n = record.read(frame, filled, frame.size - filled)
            if (n <= 0) {
                Log.w(TAG, "AudioRecord.read returned $n")
                return false
            }
            filled += n
        }
        return true
    }

    /** RMS level on a rough log scale, so speech fills most of the range. */
    private fun level(frame: ShortArray): Float {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size) / Short.MAX_VALUE
        if (rms <= 0.0) return 0f
        val db = 20 * kotlin.math.log10(rms)
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** Samples recorded so far; grows up to [capacity] and then drops new audio. */
    private class SampleBuffer(private val capacity: Int) {
        private var data = ShortArray(SAMPLE_RATE * 4)

        @Volatile var size = 0
            private set

        fun add(frame: ShortArray) {
            if (size + frame.size > capacity) return
            if (size + frame.size > data.size) data = data.copyOf(minOf(capacity, data.size * 2))
            frame.copyInto(data, size)
            size += frame.size
        }

        /** Copying is safe while recording continues: samples before [end] never change. */
        fun wav(from: Int, end: Int): ByteArray = Wav.encode(data.copyOfRange(from, end), SAMPLE_RATE)
    }

    companion object {
        private const val TAG = "Recorder"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val NO_SPEECH_MS = 8_000
        const val MAX_UTTERANCE_MS = 30_000
    }
}
