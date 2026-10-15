package com.mulesipstea.wiggins.waggle.commands

import com.mulesipstea.wiggins.actions.PhoneNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone reads aloud is capped, so the hub's wait is never at risk. */
class ReadAloudTest {
    private fun n(title: String, vararg messages: String, text: String = "") = PhoneNotification(
        ref = title, packageName = "org.example.chat", app = "Chat", title = title, text = text, time = 0, canReply = true,
        messages = messages.map { PhoneNotification.Message(title, it, 0) }, conversation = title, key = title, reply = null,
    )

    @Test fun readsWhoAndTheNewestMessages() {
        val lines = ReadAloud.spoken(listOf(n("Alex", "one", "two", "three", "four")))
        assertEquals(listOf("Chat, Alex: two. three. four"), lines)
    }

    @Test fun stopsAtAboutThreeHundredCharacters() {
        val long = "word ".repeat(40).trim()
        val lines = ReadAloud.spoken((1..5).map { n("Person $it", text = long) })
        assertTrue(lines.size < 5)
        assertTrue(lines.sumOf { it.length } <= 320)
        assertTrue(lines.last().endsWith("…") || lines.sumOf { it.length } <= 300)
    }
}
