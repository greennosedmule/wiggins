package com.mulesipstea.wiggins.actions

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import com.mulesipstea.wiggins.waggle.queries.foldForMatch
import java.security.MessageDigest

/**
 * Wiggins' notification listener (SPEC "Notifications"): Android binds it once the user
 * grants Notification access, and it only keeps itself reachable; [Notifications] reads
 * the active notifications through it when the hub asks.
 */
class NotificationReader : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    companion object {
        @Volatile internal var connected: NotificationReader? = null
    }
}

/**
 * One notification as the hub sees it: no key, only an opaque [ref]. [messages] holds a
 * messaging app's conversation, oldest first.
 */
data class PhoneNotification(
    val ref: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
    val messages: List<Message>,
    /** The conversation's name, for matching replies by who (a group's name, or the person). */
    val conversation: String,
    internal val key: String,
    internal val reply: Notification.Action?,
) {
    data class Message(val sender: String, val text: String, val time: Long)
}

/**
 * Reads and replies to notifications from the apps the user picked (SPEC "Notifications").
 * Ongoing and foreground-service notifications, group summaries and Wiggins' own are
 * skipped; Android 15 already hides one-time codes from listeners like this one.
 */
class Notifications(
    private val context: Context,
    /** The active notifications, newest or not; the listener's in production. */
    private val active: () -> List<StatusBarNotification>? = { NotificationReader.connected?.activeNotifications?.toList() },
) {
    /** Whether the user has granted Notification access to Wiggins. */
    fun hasAccess(): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, NotificationReader::class.java))

    /**
     * The notifications of [apps], newest first, filtered by [app] (label or package) and
     * [from] (title, conversation or a sender), case and accents ignored, and [since].
     * Throws [ErrorCode.PERMISSION_DENIED] without access.
     */
    fun list(apps: Set<String>, app: String? = null, from: String? = null, since: Long? = null): List<PhoneNotification> {
        val all = active() ?: throw WaggleException(ErrorCode.PERMISSION_DENIED, "Wiggins doesn't have Notification access")
        val appWant = app?.let(::foldForMatch)
        val fromWant = from?.let(::foldForMatch)
        return all.asSequence()
            .filter { it.packageName in apps && it.packageName != context.packageName && wanted(it) }
            .map(::read)
            .filter { n -> appWant == null || appWant in foldForMatch(n.app) || appWant in n.packageName.lowercase() }
            .filter { n -> fromWant == null || matchesFrom(n, fromWant) }
            .filter { n -> since == null || n.time >= since }
            .sortedByDescending { it.time }
            .toList()
    }

    /** The notification [ref] names, if it's still showing. */
    fun byRef(apps: Set<String>, ref: String): PhoneNotification? = list(apps).firstOrNull { it.ref == ref }

    /**
     * The one conversation [to] names that can be replied to. Throws [ErrorCode.NOT_FOUND]
     * for none and [ErrorCode.AMBIGUOUS] for several: the phone never guesses.
     */
    fun byWho(apps: Set<String>, to: String): PhoneNotification {
        val want = foldForMatch(to)
        val matches = list(apps).filter { it.canReply && matchesFrom(it, want) }
            .distinctBy { it.packageName to foldForMatch(it.conversation) }
        return when (matches.size) {
            0 -> throw WaggleException(ErrorCode.NOT_FOUND, "No conversation to reply to matches '$to'")
            1 -> matches.single()
            else -> throw WaggleException(ErrorCode.AMBIGUOUS, "'$to' matches ${matches.size} conversations")
        }
    }

    /** Sends [text] through [notification]'s reply action, as typing it into the notification would. */
    fun reply(notification: PhoneNotification, text: String) {
        val action = notification.reply ?: throw WaggleException(ErrorCode.NO_HANDLER, "It can't be replied to")
        val inputs = action.remoteInputs ?: throw WaggleException(ErrorCode.NO_HANDLER, "It can't be replied to")
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        try {
            action.actionIntent.send(context, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            throw WaggleException(ErrorCode.NOT_FOUND, "The notification's reply is gone")
        }
    }

    private fun wanted(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return false
        return !sbn.isOngoing
    }

    private fun matchesFrom(n: PhoneNotification, want: String) =
        want in foldForMatch(n.title) || want in foldForMatch(n.conversation) || n.messages.any { want in foldForMatch(it.sender) }

    internal fun read(sbn: StatusBarNotification): PhoneNotification {
        val n = sbn.notification
        val extras = n.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messages = style?.messages.orEmpty().map { m ->
            PhoneNotification.Message(m.person?.name?.toString() ?: style?.user?.name?.toString().orEmpty(), m.text?.toString().orEmpty(), m.timestamp)
        }.takeLast(MAX_MESSAGES)
        // A messaging app's own title often joins the conversation and the sender ("Family: Mom").
        val title = style?.conversationTitle?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val reply = n.actions?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        return PhoneNotification(
            ref = refOf(sbn.key),
            packageName = sbn.packageName,
            app = appLabel(sbn.packageName),
            title = title,
            text = if (messages.isNotEmpty() && text == messages.last().text) "" else text,
            time = sbn.postTime,
            canReply = reply != null,
            messages = messages,
            conversation = style?.conversationTitle?.toString() ?: title,
            key = sbn.key,
            reply = reply,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)

    companion object {
        /** A conversation's newest messages kept per notification (WAGGLE.md: at most 5). */
        const val MAX_MESSAGES = 5

        /** An opaque reference to a notification's key: the hub can only name what the phone listed. */
        fun refOf(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
