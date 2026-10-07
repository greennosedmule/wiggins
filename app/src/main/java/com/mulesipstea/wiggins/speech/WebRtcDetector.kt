package com.mulesipstea.wiggins.speech

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * WebRTC's voice detector (vendored android-vad, module :vad), giving the raw
 * per-frame verdict: [Endpointer] does the smoothing.
 */
class WebRtcDetector : VoiceDetector {
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.VERY_AGGRESSIVE)
    private var closed = false

    override fun isSpeech(frame: ShortArray): Boolean {
        // The native code reads a whole frame without checking the length.
        require(frame.size == Recorder.FRAME_SAMPLES) { "frame of ${frame.size} samples" }
        return vad.isSpeech(frame)
    }

    // The library throws on a second close.
    override fun close() {
        if (closed) return
        closed = true
        vad.close()
    }
}
