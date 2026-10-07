package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.speech.Transcription.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionTest {
    private val sent = mutableListOf<Pair<String, String>>() // id to audio label
    private val results = mutableListOf<Result>()
    private var ids = 0

    private fun TestScope.transcription(early: Boolean) = Transcription(
        this,
        early = early,
        send = { id, wav -> sent += id to String(wav) },
        onResult = { results += it },
        newId = { "r${++ids}" },
    )

    private fun audio(label: String): () -> ByteArray = { label.toByteArray() }

    @Test fun aPauseThatBecomesTheEndUsesTheEarlyAnswer() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("so far"))
        assertEquals(listOf("r1" to "so far"), sent)
        t.onAnswer("r1", " what time is it ")
        assertEquals(emptyList<Result>(), results) // Not over yet.
        t.onEnd(audio("all"))
        assertEquals(listOf(Result.Heard("what time is it")), results)
        assertEquals(1, sent.size) // Nothing more sent.
    }

    @Test fun theEarlyAnswerCanArriveAfterTheEnd() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("so far"))
        t.onEnd(audio("all"))
        assertEquals(1, sent.size)
        t.onAnswer("r1", "hello")
        assertEquals(listOf(Result.Heard("hello")), results)
    }

    @Test fun speechAfterAPauseMakesItsAnswerStale() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("set an"))
        t.onResume()
        t.onAnswer("r1", "set an") // Stale: ignored.
        t.onPause(audio("set an alarm"))
        t.onEnd(audio("set an alarm."))
        t.onAnswer("r2", "set an alarm")
        assertEquals(listOf("r1" to "set an", "r2" to "set an alarm"), sent)
        assertEquals(listOf(Result.Heard("set an alarm")), results)
    }

    @Test fun aLateAnswerToAReplacedRequestIsIgnored() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("one"))
        t.onResume()
        t.onPause(audio("one two")) // Replaces r1 at once.
        t.onEnd(audio("one two"))
        t.onAnswer("r1", "one")
        assertEquals(emptyList<Result>(), results)
        t.onAnswer("r2", "one two")
        assertEquals(listOf(Result.Heard("one two")), results)
    }

    @Test fun resumingAfterTheLastPauseSendsTheWholeUtterance() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("one"))
        t.onResume()
        t.onEnd(audio("one two"))
        assertEquals("r2" to "one two", sent.last())
        t.onAnswer("r2", "one two")
        assertEquals(listOf(Result.Heard("one two")), results)
    }

    @Test fun withTheSwitchOffOnlyTheEndIsSent() = runTest {
        val t = transcription(early = false)
        t.onPause(audio("one"))
        t.onResume()
        t.onPause(audio("one two"))
        t.onEnd(audio("one two."))
        assertEquals(listOf("r1" to "one two."), sent)
        t.onAnswer("r1", "one two")
        assertEquals(listOf(Result.Heard("one two")), results)
    }

    @Test fun anEmptyOrFailedTranscriptionIsNothingHeard() = runTest {
        transcription(early = false).apply { onEnd(audio("a")); onAnswer("r1", "  ") }
        transcription(early = false).apply { onEnd(audio("b")); onAnswer("r2", null) }
        assertEquals(listOf(Result.NothingHeard, Result.NothingHeard), results)
    }

    @Test fun noAnswerTimesOut() = runTest {
        val t = transcription(early = false)
        t.onEnd(audio("a"))
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(emptyList<Result>(), results)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Result.NoAnswer), results)
        t.onAnswer("r1", "too late")
        assertEquals(listOf(Result.NoAnswer), results)
    }

    @Test fun aFailedSendFinishesAtOnce() = runTest {
        val t = transcription(early = false)
        t.onEnd(audio("a"))
        t.onSendFailed("r1")
        assertEquals(listOf(Result.NotSent), results)
    }

    @Test fun aFailedEarlySendIsRetriedAtTheEnd() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("so far"))
        t.onSendFailed("r1")
        t.onEnd(audio("all"))
        assertEquals("r2" to "all", sent.last())
        assertEquals(emptyList<Result>(), results)
    }

    @Test fun cancelIgnoresEverything() = runTest {
        val t = transcription(early = true)
        t.onPause(audio("so far"))
        t.cancel()
        t.onAnswer("r1", "hello")
        t.onEnd(audio("all"))
        advanceTimeBy(20_000)
        assertEquals(emptyList<Result>(), results)
    }
}
