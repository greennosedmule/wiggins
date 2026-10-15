package com.mulesipstea.wiggins.waggle.commands

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import com.mulesipstea.wiggins.waggle.queries.badRequest
import com.mulesipstea.wiggins.waggle.queries.intParam
import com.mulesipstea.wiggins.waggle.queries.stringParam
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Something the phone does itself rather than through an app (WAGGLE.md "Commands"). The
 * rules judge it as an intent whose action is its [name] and whose category is its
 * [variant], so a rule like "ringer, silent: Ask me" can single one out.
 */
interface Command {
    /** The wire name, starting with `waggle.`. */
    val name: String

    /** A short name for people: on rules, ask cards and the action log. */
    val label: String

    /** The variant the rules see as the category, from valid [params]; null if it has none. */
    fun variant(params: JsonObject): String? = null

    /** Whether the phone can do it now: hardware it needs, and Android's access. */
    fun available(context: Context): Boolean = true

    /**
     * Does it and returns the response `data`, or null for none. Throws [WaggleException]:
     * [ErrorCode.BAD_REQUEST] for bad params (checked before anything happens),
     * [ErrorCode.PERMISSION_DENIED] if Android's access is missing, [ErrorCode.NO_HANDLER]
     * if the phone can't.
     */
    fun run(context: Context, params: JsonObject): JsonObject?

    /** What it will do, for the ask card: "Do Not Disturb: on". */
    fun describe(params: JsonObject): String = label + (variant(params)?.let { ": $it" } ?: "")
}

/** The commands Wiggins implements. */
object Commands {
    val all: List<Command> = listOf(Torch, Volume, Ringer, DoNotDisturb)

    fun named(name: String): Command? = all.firstOrNull { it.name == name }

    /** A rule's label for a command action, or null if [action] isn't one. */
    fun label(action: String): String? = named(action)?.label
}

/** The boolean param [key], required. */
internal fun JsonObject.booleanParam(key: String): Boolean {
    val element = this[key]
    if (element == null || element is JsonNull) throw badRequest("missing param '$key'")
    return (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw badRequest("param '$key' must be true or false, got $element")
}

/** `waggle.torch.set {on}`: the flashlight, on the camera that has one. No permission needed. */
object Torch : Command {
    override val name = "waggle.torch.set"
    override val label = "Flashlight"

    override fun variant(params: JsonObject) = if (params.booleanParam("on")) "on" else "off"

    override fun available(context: Context) = torchCamera(context) != null

    override fun run(context: Context, params: JsonObject): JsonObject? {
        val on = params.booleanParam("on")
        val camera = torchCamera(context) ?: throw WaggleException(ErrorCode.NO_HANDLER, "No camera with a flash")
        try {
            context.getSystemService(CameraManager::class.java).setTorchMode(camera, on)
        } catch (e: Exception) {
            // In use by the camera app, or the camera service is busy.
            throw WaggleException(ErrorCode.LAUNCH_FAILED, "Couldn't set the flashlight: ${e.message}")
        }
        return null
    }

    private fun torchCamera(context: Context): String? = runCatching {
        val cameras = context.getSystemService(CameraManager::class.java)
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
}

/**
 * `waggle.volume.set {stream, level | step}`: a stream's volume, as a percentage of its
 * range or a step up or down. `stream` is media (the default), ring, alarm or call.
 * Answers the stream's new level.
 */
object Volume : Command {
    override val name = "waggle.volume.set"
    override val label = "Volume"

    private val STREAMS = mapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "call" to AudioManager.STREAM_VOICE_CALL,
    )

    override fun describe(params: JsonObject): String {
        val stream = params.stringParam("stream") ?: "media"
        val to = params.stringParam("step")?.let { "a step $it" } ?: "${params["level"]}%"
        return "$label ($stream): $to"
    }

    override fun run(context: Context, params: JsonObject): JsonObject {
        val streamName = params.stringParam("stream") ?: "media"
        val stream = STREAMS[streamName] ?: throw badRequest("param 'stream' must be one of ${STREAMS.keys}, got $streamName")
        val step = params.stringParam("step")
        val hasLevel = params["level"] != null && params["level"] !is JsonNull
        if (step != null && hasLevel) throw badRequest("give 'level' or 'step', not both")
        if (step != null && step !in setOf("up", "down")) throw badRequest("param 'step' must be up or down, got $step")
        if (step == null && !hasLevel) throw badRequest("give 'level' (0–100) or 'step' (up or down)")
        val level = if (hasLevel) params.intParam("level", default = 0, min = 0, max = 100) else null
        val audio = context.getSystemService(AudioManager::class.java)
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        try {
            if (level != null) {
                audio.setStreamVolume(stream, min + ((max - min) * level / 100.0).roundToInt(), 0)
            } else {
                audio.adjustStreamVolume(stream, if (step == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            }
        } catch (e: SecurityException) {
            // Changing the ringer's volume to or from zero can change Do Not Disturb.
            throw WaggleException(ErrorCode.PERMISSION_DENIED, "Android refused: ${e.message}")
        }
        val now = audio.getStreamVolume(stream)
        return buildJsonObject {
            put("stream", streamName)
            put("level", if (max > min) ((now - min) * 100.0 / (max - min)).roundToInt() else 0)
        }
    }
}

/** `waggle.ringer.set {mode}`: normal, vibrate or silent. Silent needs Do Not Disturb access on Android. */
object Ringer : Command {
    override val name = "waggle.ringer.set"
    override val label = "Ringer"

    private val MODES = mapOf(
        "normal" to AudioManager.RINGER_MODE_NORMAL,
        "vibrate" to AudioManager.RINGER_MODE_VIBRATE,
        "silent" to AudioManager.RINGER_MODE_SILENT,
    )

    override fun variant(params: JsonObject): String {
        val mode = params.stringParam("mode", required = true)!!
        if (mode !in MODES) throw badRequest("param 'mode' must be one of ${MODES.keys}, got $mode")
        return mode
    }

    override fun run(context: Context, params: JsonObject): JsonObject? {
        val mode = MODES.getValue(variant(params))
        try {
            context.getSystemService(AudioManager::class.java).ringerMode = mode
        } catch (e: SecurityException) {
            throw WaggleException(ErrorCode.PERMISSION_DENIED, "Silencing needs Do Not Disturb access for Wiggins")
        }
        return null
    }
}

/** `waggle.dnd.set {on}`: Do Not Disturb, as priority-only, or off. Needs Do Not Disturb access. */
object DoNotDisturb : Command {
    override val name = "waggle.dnd.set"
    override val label = "Do Not Disturb"

    override fun variant(params: JsonObject) = if (params.booleanParam("on")) "on" else "off"

    override fun available(context: Context) = hasAccess(context)

    override fun run(context: Context, params: JsonObject): JsonObject? {
        val on = params.booleanParam("on")
        if (!hasAccess(context)) throw WaggleException(ErrorCode.PERMISSION_DENIED, "Wiggins doesn't have Do Not Disturb access")
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        context.getSystemService(NotificationManager::class.java).setInterruptionFilter(filter)
        return null
    }

    fun hasAccess(context: Context) = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
}
