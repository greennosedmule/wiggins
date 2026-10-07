package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.speech.Endpointer.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End of speech from per-frame voice verdicts (20 ms frames). */
class EndpointerTest {
    /** Feeds [ms] of speech or silence; returns the events with the time each happened. */
    private fun Endpointer.feed(speech: Boolean, ms: Int, clock: IntArray): List<Pair<Event, Int>> =
        (0 until ms / 20).mapNotNull { onFrame(speech).also { clock[0] += 20 }?.let { e -> e to clock[0] } }

    private fun run(vararg segments: Pair<Boolean, Int>): List<Pair<Event, Int>> {
        val e = Endpointer()
        val clock = intArrayOf(0)
        return segments.flatMap { (speech, ms) -> e.feed(speech, ms, clock) }
    }

    @Test fun speechThenSilenceEnds() {
        val events = run(false to 500, true to 1000, false to 2000)
        assertEquals(listOf(Event.STARTED, Event.PAUSED, Event.ENDED), events.map { it.first })
        assertEquals(700, events[0].second) // 200 ms after speech began
        assertEquals(1500 + 300, events[1].second)
        assertEquals(1500 + 800, events[2].second)
    }

    @Test fun reportsWhereSpeechBegan() {
        val e = Endpointer()
        val clock = intArrayOf(0)
        e.feed(false, 500, clock)
        e.feed(true, 300, clock)
        assertEquals(500, e.speechStartMs)
    }

    @Test fun noSpeechGivesUpAfterEightSeconds() {
        val events = run(false to 10_000)
        assertEquals(listOf(Event.NO_SPEECH to 8_000), events)
    }

    @Test fun briefNoiseDoesNotStartAnUtterance() {
        // Clicks of 100 ms, far apart, never add up to speech.
        val clicks = (0 until 7).flatMap { listOf(true to 100, false to 1200) }.toTypedArray()
        val events = run(*clicks)
        assertEquals(listOf(Event.NO_SPEECH), events.map { it.first })
    }

    @Test fun aPauseThenMoreSpeechResumes() {
        val events = run(true to 600, false to 400, true to 600, false to 1000)
        assertEquals(listOf(Event.STARTED, Event.PAUSED, Event.RESUMED, Event.PAUSED, Event.ENDED), events.map { it.first })
    }

    @Test fun aClickDuringAPauseDoesNotResume() {
        val events = run(true to 600, false to 400, true to 20, false to 1000)
        assertEquals(listOf(Event.STARTED, Event.PAUSED, Event.ENDED), events.map { it.first })
        // The click counts as silence: ended 800 ms after speech stopped.
        assertEquals(600 + 800, events.last().second)
    }

    @Test fun capsLongUtterances() {
        val events = run(true to 40_000)
        assertEquals(Event.CAPPED, events.last().first)
        assertEquals(30_000, events.last().second)
    }

    @Test fun nothingAfterTheEnd() {
        val e = Endpointer()
        val clock = intArrayOf(0)
        e.feed(true, 500, clock)
        e.feed(false, 900, clock)
        assertTrue(e.feed(true, 1000, clock).isEmpty())
    }

    @Test fun heardSpeechCountsShortSpeech() {
        val e = Endpointer()
        assertFalse(e.heardSpeech)
        e.onFrame(true)
        assertTrue(e.heardSpeech)
        assertFalse(e.started)
    }
}
