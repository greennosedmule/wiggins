package com.mulesipstea.wiggins

import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.Who
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
            val saved = runCatching { Json.parseToJsonElement(file.readText()).jsonArray.size }.getOrNull()
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

    @Test fun clearIsSaved() {
        val store = ConversationStore(file, scope)
        store.add(Who.USER, "hello")
        waitForSave(1)
        store.clear()
        waitForSave(0)
    }
}
