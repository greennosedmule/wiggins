package com.mulesipstea.wiggins.waggle.commands

import android.content.Context
import com.mulesipstea.wiggins.actions.PhoneNotification
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import com.mulesipstea.wiggins.waggle.queries.WaggleEnv
import com.mulesipstea.wiggins.waggle.queries.badRequest
import com.mulesipstea.wiggins.waggle.queries.intParam
import com.mulesipstea.wiggins.waggle.queries.stringParam
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `waggle.notifications.read_aloud {app?, from?, limit?}`: the phone reads the matching
 * notifications with its own voice and answers how many once it has finished (SPEC
 * "Notifications"). Their words go to Waggle or the persona only if the user also lets the
 * hub list them; with hub text-to-speech, the hub's speech server voices them.
 */
object ReadAloud : Command {
    override val name = "waggle.notifications.read_aloud"
    override val label = "Read notifications aloud"

    override fun available(context: Context) = com.mulesipstea.wiggins.actions.Notifications(context).hasAccess()

    override fun run(context: Context, params: JsonObject): JsonObject? =
        throw WaggleException(ErrorCode.PERMISSION_DENIED, "Needs the phone's notification settings")

    override suspend fun run(context: Context, params: JsonObject, env: WaggleEnv): JsonObject {
        val app = params.stringParam("app")
        val from = params.stringParam("from")
        val limit = params.intParam("limit", default = 5, min = 1, max = 20)
        val found = env.notifications.list(env.notificationApps(), app, from).take(limit)
        if (found.isNotEmpty()) env.speak(spoken(found))
        return buildJsonObject { put("count", found.size) }
    }

    /**
     * What's read for [found]: per notification who it's from and its newest messages, at
     * most [MAX_MESSAGES] each and about [MAX_CHARS] characters in all, so the hub's wait
     * (120 s) is never at risk.
     */
    internal fun spoken(found: List<PhoneNotification>): List<String> {
        val lines = mutableListOf<String>()
        var budget = MAX_CHARS
        for (n in found) {
            val words = if (n.messages.isNotEmpty()) {
                n.messages.takeLast(MAX_MESSAGES).joinToString(". ") { it.text }
            } else {
                listOf(n.text).filter { it.isNotBlank() }.joinToString()
            }
            val who = n.conversation.ifBlank { n.title }.ifBlank { n.app }
            val line = "${n.app}, $who: $words".trim()
            if (budget <= 0) break
            lines += if (line.length > budget) line.take(budget).substringBeforeLast(' ') + "…" else line
            budget -= line.length
        }
        return lines
    }

    private const val MAX_MESSAGES = 3
    private const val MAX_CHARS = 300
}

/**
 * `waggle.notification.reply {text, ref | to}`: replies through the notification's own
 * reply action, as typing into it would (SPEC "Notifications"). `ref` names a listed
 * notification; `to` a conversation by name, which must match exactly one. The rules see
 * the notification's app as the package, so "Signal: Allow" can stand beside "Ask me".
 */
object Reply : Command {
    override val name = "waggle.notification.reply"
    override val label = "Reply"

    override fun available(context: Context) = com.mulesipstea.wiggins.actions.Notifications(context).hasAccess()

    override fun run(context: Context, params: JsonObject): JsonObject? =
        throw WaggleException(ErrorCode.PERMISSION_DENIED, "Needs the phone's notification settings")

    override suspend fun target(context: Context, params: JsonObject, env: WaggleEnv) = find(params, env).packageName

    override suspend fun describe(params: JsonObject, env: WaggleEnv): String {
        val n = find(params, env)
        return "Reply to ${n.conversation.ifBlank { n.title }} on ${n.app}: “${params.stringParam("text", required = true)}”"
    }

    override suspend fun run(context: Context, params: JsonObject, env: WaggleEnv): JsonObject? {
        val text = params.stringParam("text", required = true)!!
        env.notifications.reply(find(params, env), text)
        return null
    }

    /** The notification [params] name; checks the params first. */
    private suspend fun find(params: JsonObject, env: WaggleEnv): PhoneNotification {
        params.stringParam("text", required = true)
        val ref = params.stringParam("ref")
        val to = params.stringParam("to")
        if ((ref == null) == (to == null)) throw badRequest("give exactly one of 'ref' and 'to'")
        val apps = env.notificationApps()
        val n = if (ref != null) {
            env.notifications.byRef(apps, ref) ?: throw WaggleException(ErrorCode.NOT_FOUND, "That notification is gone")
        } else {
            env.notifications.byWho(apps, to!!)
        }
        if (!n.canReply) throw WaggleException(ErrorCode.NO_HANDLER, "It can't be replied to")
        return n
    }
}
