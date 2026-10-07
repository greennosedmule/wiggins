package com.mulesipstea.wiggins.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubSpeakerTest {
    private val requested = mutableListOf<String>() // texts, in request order
    private val ids = mutableMapOf<String, String>() // text to id
    private val heard = mutableListOf<String>() // what was played, in order
    private val answered = mutableListOf<Boolean>()
    private var idle = 0
    private var connected = true
    private var nextId = 0

    private fun TestScope.speaker() = HubSpeaker(
        this,
        request = { id, text ->
            if (connected) {
                requested += text
                ids[text] = id
            }
            connected
        },
        play = { audio ->
            delay(1_000)
            heard += "hub:$audio"
        },
        fallback = { text -> heard += "device:$text" },
        onHubAnswered = { answered += it },
        newId = { "id${nextId++}" },
    ).also { it.onIdle = { idle++ } }

    @Test fun playsInOrderWhateverOrderTheAudioArrives() = runTest {
        val s = speaker()
        s.speak("One.")
        s.speak("Two.")
        s.onAudio(ids.getValue("Two."), "2")
        s.onAudio(ids.getValue("One."), "1")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("hub:1", "hub:2"), heard)
        assertEquals(1, idle)
        assertFalse(s.isSpeaking)
    }

    @Test fun requestsOnlyTheCurrentAndNextSentence() = runTest {
        val s = speaker()
        s.speak("One.")
        s.speak("Two.")
        s.speak("Three.")
        assertEquals(listOf("One.", "Two."), requested)
        s.onAudio(ids.getValue("One."), "1")
        runCurrent()
        // "One." is playing: "Two." is next and already requested; "Three." waits.
        assertEquals(listOf("One.", "Two."), requested)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("One.", "Two.", "Three."), requested)
    }

    @Test fun aSentenceWithNoAudioInTimeGoesToTheDevice() = runTest {
        val s = speaker()
        s.speak("One.")
        s.speak("Two.")
        s.onAudio(ids.getValue("Two."), "2")
        advanceTimeBy(10_001)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("device:One.", "hub:2"), heard)
        assertTrue(false in answered)
    }

    @Test fun notConnectedFallsBackAtOnce() = runTest {
        connected = false
        val s = speaker()
        s.speak("One.")
        runCurrent()
        assertEquals(listOf("device:One."), heard)
        assertEquals(1, idle)
    }

    @Test fun splitsAReplyIntoSentences() = runTest {
        val s = speaker()
        s.speak("Honeybees live in colonies. A colony has one queen, who lays 1.5 thousand eggs a day! Workers do the rest.")
        assertEquals(listOf("Honeybees live in colonies.", "A colony has one queen, who lays 1.5 thousand eggs a day!"), requested)
        assertEquals(listOf("Hi."), HubSpeaker.sentences("  Hi.  ", java.util.Locale.US))
        assertEquals(emptyList<String>(), HubSpeaker.sentences("   ", java.util.Locale.US))
    }

    @Test fun aLongSentenceGetsLonger() {
        assertEquals(10_000L, HubSpeaker.timeoutFor("Short."))
        assertEquals(10_000L + 40 * 400, HubSpeaker.timeoutFor("x".repeat(500)))
    }

    @Test fun stopForgetsTheQueueWithoutIdle() = runTest {
        val s = speaker()
        s.speak("One.")
        s.speak("Two.")
        s.onAudio(ids.getValue("One."), "1")
        runCurrent()
        s.stop()
        s.onAudio(ids.getValue("Two."), "2")
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(emptyList<String>(), heard)
        assertEquals(0, idle)
        assertFalse(s.isSpeaking)
    }
}
