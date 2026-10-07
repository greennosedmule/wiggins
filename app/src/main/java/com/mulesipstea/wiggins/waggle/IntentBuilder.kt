package com.mulesipstea.wiggins.waggle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float

/** Turns a validated `waggle.intent` into the Android [Intent] Wiggins launches (SPEC "Launching"). */
object IntentBuilder {
    /**
     * Actions that place a call without the user tapping call. SPEC: `ACTION_CALL` is never
     * supported, so Wiggins never holds `CALL_PHONE`; the privileged variants go with it.
     */
    private val CALL_ACTIONS = setOf(
        Intent.ACTION_CALL,
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
    )

    /**
     * Builds the Intent for [request]: action, data and type (normalized, so a `TEL:` URI
     * reaches the dialer's `tel` filter), categories, package and typed extras. It never
     * carries hub-supplied flags or URI grants; its only flag is [Intent.FLAG_ACTIVITY_NEW_TASK].
     *
     * Throws [WaggleException]:
     * - [ErrorCode.BLOCKED] for `ACTION_CALL`: the request is well formed under WAGGLE.md, which
     *   doesn't forbid the action, but the phone refuses it as policy, like a built-in block rule.
     * - [ErrorCode.BAD_REQUEST] if the intent names Wiggins' package or any activity of
     *   Wiggins' would match it.
     * - [ErrorCode.NO_HANDLER] if no activity resolves it.
     *
     * Resolution sees only packages visible to Wiggins (Android 11+ package visibility), so the
     * manifest's `<queries>` must cover the intents Wiggins is expected to launch. Call this
     * before asking the user, so a request that can't launch fails without a confirmation card.
     */
    fun build(context: Context, request: IntentRequest): Intent {
        if (request.action in CALL_ACTIONS) {
            throw WaggleException(ErrorCode.BLOCKED, "${request.action} is never supported; use DIAL")
        }
        val intent = Intent(request.action)
        val uri = request.data?.let { safeUri(it, "'data'") }
        when {
            uri != null && request.mimeType != null -> intent.setDataAndTypeAndNormalize(uri, request.mimeType)
            uri != null -> intent.setDataAndNormalize(uri)
            request.mimeType != null -> intent.setTypeAndNormalize(request.mimeType)
        }
        request.categories.forEach { intent.addCategory(it) }
        request.packageName?.let { intent.setPackage(it) }
        request.extras.forEach { (name, extra) -> putExtra(intent, name, extra) }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        val self = context.packageName
        val pm = context.packageManager
        // Any match counts here, not just DEFAULT ones: Wiggins must never be a candidate.
        if (request.packageName == self || pm.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == self }) {
            throw WaggleException(ErrorCode.BAD_REQUEST, "intent would resolve to Wiggins itself")
        }
        // startActivity only resolves implicit intents to filters with CATEGORY_DEFAULT.
        if (pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            launcherActivity(pm, intent)?.let { intent.component = it }
                ?: throw WaggleException(ErrorCode.NO_HANDLER, "no activity handles ${request.action}")
        }
        return intent
    }

    /**
     * MAIN + LAUNCHER for one package is how a hub opens an app, but launcher filters rarely
     * declare CATEGORY_DEFAULT, so that intent doesn't resolve implicitly. Like
     * PackageManager.getLaunchIntentForPackage, target the package's launcher activity directly.
     */
    private fun launcherActivity(pm: PackageManager, intent: Intent): ComponentName? {
        if (intent.action != Intent.ACTION_MAIN || intent.`package` == null ||
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) != true
        ) return null
        val info = pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /**
     * Parses a URI, checking the forbidden schemes again as Android reads them, in case
     * [request] didn't come from [IntentRequest.parse].
     */
    private fun safeUri(value: String, what: String): Uri {
        val uri = Uri.parse(value).normalizeScheme()
        val scheme = uriScheme(value)
        if (scheme == null || scheme in FORBIDDEN_SCHEMES || uri.scheme?.lowercase() in FORBIDDEN_SCHEMES) {
            throw badRequest("$what has a missing or forbidden scheme")
        }
        return uri
    }

    private fun putExtra(intent: Intent, name: String, extra: Extra) {
        val value = extra.value
        when (extra.type) {
            "int" -> intent.putExtra(name, (value as JsonPrimitive).content.toInt())
            "long" -> intent.putExtra(name, (value as JsonPrimitive).content.toLong())
            "float" -> intent.putExtra(name, (value as JsonPrimitive).float)
            "double" -> intent.putExtra(name, (value as JsonPrimitive).double)
            "bool" -> intent.putExtra(name, (value as JsonPrimitive).boolean)
            "string" -> intent.putExtra(name, (value as JsonPrimitive).content)
            "string[]" -> intent.putExtra(name, (value as JsonArray).map { (it as JsonPrimitive).content }.toTypedArray())
            "uri" -> intent.putExtra(name, safeUri((value as JsonPrimitive).content, "extra '$name'"))
            else -> throw badRequest("extra '$name' has unknown type ${extra.type}")
        }
    }
}
