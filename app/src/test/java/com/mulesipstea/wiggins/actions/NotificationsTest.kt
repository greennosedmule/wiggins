package com.mulesipstea.wiggins.actions

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Reading and replying to notifications (SPEC "Notifications"), with made-up apps and people. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NotificationsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val chat = "org.example.chat"
    private val mail = "org.example.mail"
    private val active = mutableListOf<StatusBarNotification>()
    private val notifications = Notifications(app) { active }
    private var nextId = 1

    private fun replyAction(): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(app, nextId, Intent("org.example.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(0, "Reply", pending)
            .addRemoteInput(androidx.core.app.RemoteInput.Builder("reply_text").setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun post(pkg: String, notification: Notification, time: Long, flags: Int = 0) {
        notification.flags = notification.flags or flags
        val id = nextId++
        @Suppress("DEPRECATION")
        active += StatusBarNotification(pkg, pkg, id, null, 0, 0, 0, notification, Process.myUserHandle(), time)
    }

    private fun conversation(pkg: String, title: String, vararg messages: Pair<String, String>, time: Long, reply: Boolean = true) {
        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title)
        messages.forEachIndexed { i, (who, text) -> style.addMessage(text, time - 1000L * (messages.size - i), Person.Builder().setName(who).build()) }
        val builder = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(messages.last().second).setStyle(style)
        if (reply) builder.addAction(replyAction())
        post(pkg, builder.build(), time)
    }

    private fun plain(pkg: String, title: String, text: String, time: Long, flags: Int = 0) =
        post(pkg, NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).build(), time, flags)

    @Test fun listsThePickedAppsNewestFirstAndSkipsTheRest() {
        conversation(chat, "Alex", "Alex" to "Running late", "Alex" to "On my way", time = 2_000_000)
        plain(mail, "Invoice", "Your invoice is ready", time = 3_000_000)
        plain(chat, "Syncing", "…", time = 4_000_000, flags = Notification.FLAG_ONGOING_EVENT)
        plain(chat, "3 new messages", "", time = 5_000_000, flags = Notification.FLAG_GROUP_SUMMARY)
        val found = notifications.list(setOf(chat))
        assertEquals(listOf("Alex"), found.map { it.title })
        val alex = found.single()
        assertEquals(listOf("Running late", "On my way"), alex.messages.map { it.text })
        assertEquals(listOf("Alex", "Alex"), alex.messages.map { it.sender })
        assertEquals("", alex.text) // the newest message is in messages already
        assertTrue(alex.canReply)
        assertEquals(24, alex.ref.length)
        assertEquals(listOf("Invoice", "Alex"), notifications.list(setOf(chat, mail)).map { it.title })
    }

    @Test fun filtersByAppSenderAndTime() {
        conversation(chat, "Family", "Mom" to "Dinner at 6?", time = 2_000_000)
        conversation(chat, "Alex", "Alex" to "Hi", time = 3_000_000)
        assertEquals(listOf("Family"), notifications.list(setOf(chat), from = "mom").map { it.title })
        assertEquals(listOf("Alex"), notifications.list(setOf(chat), since = 2_500_000).map { it.title })
        assertEquals(2, notifications.list(setOf(chat), app = "example.chat").size)
        assertEquals(0, notifications.list(setOf(chat), app = "mail").size)
    }

    @Test fun repliesFindExactlyOneConversation() {
        conversation(chat, "Alex", "Alex" to "Hi", time = 2_000_000)
        conversation(chat, "Alexandra", "Alexandra" to "Hey", time = 3_000_000)
        conversation(chat, "News", "News" to "Read this", time = 4_000_000, reply = false)
        assertEquals("Alexandra", notifications.byWho(setOf(chat), "alexandra").title)
        fails(ErrorCode.AMBIGUOUS) { notifications.byWho(setOf(chat), "alex") }
        fails(ErrorCode.NOT_FOUND) { notifications.byWho(setOf(chat), "news") } // can't be replied to
        fails(ErrorCode.NOT_FOUND) { notifications.byWho(setOf(chat), "Sam") }
    }

    @Test fun aReplyGoesThroughTheNotificationsOwnAction() {
        conversation(chat, "Alex", "Alex" to "Hi", time = 2_000_000)
        val alex = notifications.byWho(setOf(chat), "Alex")
        notifications.reply(alex, "On my way")
        val sent = shadowOf(app).broadcastIntents.last()
        assertEquals("org.example.REPLY", sent.action)
        assertEquals("On my way", RemoteInput.getResultsFromIntent(sent)?.getCharSequence("reply_text").toString())
        // The ref finds it again while it's showing.
        assertEquals(alex.key, notifications.byRef(setOf(chat), alex.ref)?.key)
        active.clear()
        assertEquals(null, notifications.byRef(setOf(chat), alex.ref))
    }

    @Test fun withoutAccessTheListIsRefused() {
        fails(ErrorCode.PERMISSION_DENIED) { Notifications(app) { null }.list(setOf(chat)) }
        assertFalse(Notifications(app).hasAccess())
    }

    private fun fails(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: WaggleException) {
            assertEquals(code, e.code)
        }
    }
}
