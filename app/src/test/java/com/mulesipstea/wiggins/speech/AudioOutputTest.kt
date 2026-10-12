package com.mulesipstea.wiggins.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioOutputTest {
    private val sent = mutableListOf<String>()
    private val output = AudioOutput { sent += it }

    @Test fun aReplyIsBracketedOnce() {
        output.onSpeak(playing = true)
        output.onSpeak(playing = true) // the next sentence, while the first plays
        output.onIdle()
        assertEquals(listOf(AudioOutput.START, AudioOutput.END), sent)
    }

    @Test fun speechAfterTheQueueDrainedStartsAgain() {
        // A skill speaking with wait=True sends its next sentence only after the end.
        output.onSpeak(playing = true)
        output.onIdle()
        output.onSpeak(playing = true)
        output.onIdle()
        assertEquals(listOf(AudioOutput.START, AudioOutput.END, AudioOutput.START, AudioOutput.END), sent)
    }

    @Test fun stoppingEndsOnceAndLaterIdlesAreQuiet() {
        output.onSpeak(playing = true)
        output.onIdle() // the user stopped it
        output.onIdle() // the device engine's own idle, after the stop
        output.onIdle() // a new question stops voices that are already quiet
        assertEquals(listOf(AudioOutput.START, AudioOutput.END), sent)
        assertFalse(output.isOpen)
    }

    @Test fun idleWithoutSpeechSendsNothing() {
        output.onIdle()
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun silentRepliesEndEachSpeakWithoutAStart() {
        // Muted or no engine: nothing plays, but a skill may be waiting on each speak.
        output.onSpeak(playing = false)
        output.onSpeak(playing = false)
        output.onIdle()
        assertEquals(listOf(AudioOutput.END, AudioOutput.END), sent)
    }

    @Test fun aSpeakThatPlaysNothingClosesAnOpenStretch() {
        output.onSpeak(playing = true)
        output.onSpeak(playing = false)
        output.onIdle()
        assertEquals(listOf(AudioOutput.START, AudioOutput.END), sent)
    }
}
