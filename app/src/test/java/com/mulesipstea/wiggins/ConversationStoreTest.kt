package com.mulesipstea.wiggins

import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.Who
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file get() = File(tmp.root, "conversation.json")

    @After fun tearDown() = scope.cancel()

    /** Waits until the file holds [expectedEntries] entries (saves are debounced). */
    private fun waitForSave(expectedEntries: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val saved = runCatching { Json.parseToJsonElement(file.readText()).jsonObject.getValue("entries").jsonArray.size }.getOrNull()
            if (saved == expectedEntries) return
            Thread.sleep(50)
        }
        throw AssertionError("conversation wasn't saved with $expectedEntries entries")
    }

    @Test fun survivesRestartAndPendingBecomesNotSent() {
        val store = ConversationStore(file, scope)
        store.add(Who.USER, "what time is it")
        store.add(Who.HUB, "It's seven.")
        store.add(Who.USER, "set an alarm", Delivery.PENDING)
        waitForSave(3)

        val reloaded = ConversationStore(file, scope).entries.value
        assertEquals(listOf("what time is it", "It's seven.", "set an alarm"), reloaded.map { it.text })
        assertEquals(Delivery.NOT_SENT, reloaded.last().delivery)
    }

    @Test fun streamingReplyJoinsSentencesAndLoadsFinished() {
        val store = ConversationStore(file, scope)
        val id = store.add(Who.HUB, "Honeybees live in colonies.", streaming = true)
        store.append(id, "A colony has one queen.")
        assertEquals("Honeybees live in colonies. A colony has one queen.", store.get(id)?.text)
        assertTrue(store.get(id)!!.streaming)
        waitForSave(1)
        // If the app dies mid-reply, no more of it will come.
        assertEquals(false, ConversationStore(file, scope).entries.value.single().streaming)
        store.finish(id)
        assertEquals(false, store.get(id)!!.streaming)
    }

    @Test fun idsKeepIncreasingAfterReload() {
        val first = ConversationStore(file, scope)
        val a = first.add(Who.USER, "one")
        waitForSave(1)
        val b = ConversationStore(file, scope).add(Who.USER, "two")
        assertTrue(b > a)
    }

    @Test fun keepsOnlyTheMostRecent() {
        val store = ConversationStore(file, scope, limit = 3)
        repeat(5) { store.add(Who.USER, "q$it") }
        assertEquals(listOf("q2", "q3", "q4"), store.entries.value.map { it.text })
    }

    @Test fun corruptFileStartsEmpty() {
        file.writeText("{not json")
        assertEquals(emptyList<Any>(), ConversationStore(file, scope).entries.value)
    }

    @Test fun newSessionClearsAndIsSaved() {
        val store = ConversationStore(file, scope)
        store.add(Who.USER, "hello")
        waitForSave(1)
        val before = store.sessionId
        store.newSession()
        waitForSave(0)
        assertTrue(store.sessionId != before)
        assertEquals(store.sessionId, ConversationStore(file, scope).sessionId)
    }

    @Test fun sessionAndActivitySurviveRestart() {
        var now = 1_000_000L
        val store = ConversationStore(file, scope, clock = { now })
        store.add(Who.USER, "hi")
        now += 5_000
        store.touch()
        waitForSave(1)
        val reloaded = ConversationStore(file, scope, clock = { now })
        assertEquals(store.sessionId, reloaded.sessionId)
        assertEquals(now, reloaded.lastActivity)
    }

    @Test fun idleIsMeasuredFromTheLastActivity() {
        var now = 0L
        val store = ConversationStore(null, scope, clock = { now })
        now = 29 * 60_000L
        assertEquals(false, store.idleFor(30 * 60_000L))
        store.touch()
        now += 31 * 60_000L
        assertTrue(store.idleFor(30 * 60_000L))
    }

    @Test fun firstFormatStillLoads() {
        file.writeText("""[{"id":3,"who":"USER","text":"old","delivery":"PENDING"}]""")
        val store = ConversationStore(file, scope)
        assertEquals("old", store.entries.value.single().text)
        assertEquals(Delivery.NOT_SENT, store.entries.value.single().delivery)
        assertTrue(store.sessionId.isNotBlank())
    }
}
