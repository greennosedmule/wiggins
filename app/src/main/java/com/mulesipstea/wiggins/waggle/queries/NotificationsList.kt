package com.mulesipstea.wiggins.waggle.queries

import android.content.Context
import com.mulesipstea.wiggins.actions.PhoneNotification
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * `notifications.list`: the notifications of the apps the user picked, newest first
 * (SPEC "Notifications"). `app` and `from` filter by name, `since` by time, `limit` caps
 * them (1–20, 10 by default). Each has an opaque `ref` to reply to while it's showing.
 */
object NotificationsList : Query {
    override val name = "notifications.list"

    /** Notification access is granted in Android's settings, not asked for at runtime. */
    override val permission: String? = null

    override suspend fun run(context: Context, params: JsonObject): JsonObject =
        throw WaggleException(ErrorCode.PERMISSION_DENIED, "Needs the phone's notification settings")

    override suspend fun run(context: Context, params: JsonObject, env: WaggleEnv): JsonObject {
        val app = params.stringParam("app")
        val from = params.stringParam("from")
        val since = params.timeParam("since")
        val limit = params.intParam("limit", default = 10, min = 1, max = 20)
        val found = env.notifications.list(env.notificationApps(), app, from, since).take(limit)
        return buildJsonObject {
            put("notifications", buildJsonArray { found.forEach { add(it.toJson()) } })
        }
    }

    fun PhoneNotification.toJson() = buildJsonObject {
        put("ref", ref)
        put("app", app)
        put("package", packageName)
        put("title", title)
        put("text", text)
        put("time", wireInstant(time))
        put("can_reply", canReply)
        if (messages.isNotEmpty()) {
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("sender", m.sender)
                        put("text", m.text)
                        put("time", wireInstant(m.time))
                    })
                }
            })
        }
    }
}

/** The wire time param [key] (ISO 8601) as epoch millis, or null if absent. */
internal fun JsonObject.timeParam(key: String): Long? {
    val value = stringParam(key) ?: return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { throw badRequest("param '$key' must be an ISO 8601 time, got $value") }
}
