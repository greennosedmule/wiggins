package com.mulesipstea.wiggins.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.withTransaction
import com.mulesipstea.wiggins.settings.ActionSettings
import com.mulesipstea.wiggins.settings.SettingsRepository
import com.mulesipstea.wiggins.waggle.Capabilities
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.IntentBuilder
import com.mulesipstea.wiggins.waggle.IntentRequest
import com.mulesipstea.wiggins.waggle.Mode
import com.mulesipstea.wiggins.waggle.QueryRequest
import com.mulesipstea.wiggins.waggle.Rule
import com.mulesipstea.wiggins.waggle.RuleMatcher
import com.mulesipstea.wiggins.waggle.Waggle
import com.mulesipstea.wiggins.waggle.WaggleException
import com.mulesipstea.wiggins.waggle.WaggleResponse
import com.mulesipstea.wiggins.waggle.queries.Queries
import com.mulesipstea.wiggins.waggle.requestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the hub's Waggle requests within the user's rules (SPEC "Phone actions (Waggle)"):
 * decides each intent by the allowlist, asks the user when a rule says so, launches it,
 * runs enabled queries, and logs every request. Rules and settings are edited only on
 * the phone.
 *
 * Main-thread confined, like [com.mulesipstea.wiggins.Assistant].
 */
class PhoneActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: ActionDatabase,
    private val settings: SettingsRepository,
    /** Whether a Wiggins screen is visible; Android blocks activity launches from the background. */
    private val isForeground: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A confirmation the user hasn't answered yet. */
    data class Ask(
        val key: Long,
        val request: IntentRequest,
        /** The rule that said ask, or null for an unmatched intent with `unmatched: ask`. */
        val rule: Rule?,
        /** The app that would open, as the system resolves it, or null if it's a chooser. */
        val target: String?,
        val deadlineMillis: Long,
    )

    private enum class Answer { ALLOW, ALLOW_ALWAYS, DENY }

    val rules: StateFlow<List<RuleEntity>> = db.rules().all().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val log: StateFlow<List<LogEntity>> = db.log().recent().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val actionSettings: StateFlow<ActionSettings> = settings.actions.stateIn(scope, SharingStarted.Eagerly, ActionSettings())

    private val _asks = MutableStateFlow<List<Ask>>(emptyList())

    /** Confirmations waiting for the user, oldest first; the first is the one on screen. */
    val asks: StateFlow<List<Ask>> = _asks.asStateFlow()
    private val answers = mutableMapOf<Long, CompletableDeferred<Answer>>()
    private val askKeys = AtomicLong()

    /** Emits whenever the rules or settings change, so the hub's copy of the capabilities can follow. */
    val capabilityChanges: Flow<Unit> = combine(db.rules().all(), settings.actions) { _, _ -> }.drop(1)

    /** The phone's capabilities as they stand (WAGGLE.md `waggle.capabilities`). */
    suspend fun capabilities(): Capabilities {
        val s = settings.actions.first()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return Capabilities(
            clientName = CLIENT_NAME,
            clientVersion = version.orEmpty(),
            timezone = TimeZone.getDefault().id,
            lang = Locale.getDefault().toLanguageTag(),
            askTimeoutS = ASK_TIMEOUT_S,
            unmatched = s.unmatched,
            rules = db.rules().all().first().map { it.toRule() },
            queries = Queries.all.map { it.name }.filter { it in s.queries },
        )
    }

    /** Handles a `waggle.intent`; returns the response to send, or null if it can't be answered (no id). */
    suspend fun onIntent(data: JsonObject): WaggleResponse? {
        val id = requestId(data) ?: return null.also { Log.w(TAG, "waggle.intent without an id") }
        var rule: Rule? = null
        val request = try {
            checkVersion(data)
            IntentRequest.parse(data)
        } catch (e: WaggleException) {
            return finish("intent", data, rawTitle(data), null, WaggleResponse.error(id, e.code, e.message))
        }
        val response = try {
            val s = settings.actions.first()
            val decision = RuleMatcher.decide(db.rules().all().first().map { it.toRule() }, s.unmatched, request)
            rule = decision.rule
            if (decision.mode == Mode.BLOCK) throw WaggleException(ErrorCode.BLOCKED, if (rule == null) "No rule matches" else "A rule blocks it")
            // Built before asking, so a request that can't run never bothers the user.
            val intent = IntentBuilder.build(context, request)
            if (decision.mode == Mode.ASK) {
                when (ask(request, rule, intent)) {
                    null -> throw WaggleException(ErrorCode.TIMEOUT, "No answer in ${ASK_TIMEOUT_S}s")
                    Answer.DENY -> throw WaggleException(ErrorCode.DECLINED, "The user said no")
                    Answer.ALLOW -> Unit
                    Answer.ALLOW_ALWAYS -> db.rules().upsert(RuleEntity.of(ruleFor(request)))
                }
            }
            launch(intent)
            WaggleResponse.ok(id)
        } catch (e: WaggleException) {
            WaggleResponse.error(id, e.code, e.message)
        }
        return finish("intent", data, request.description ?: request.action, rule, response)
    }

    /** Handles a `waggle.query`; returns the response to send, or null if it can't be answered (no id). */
    suspend fun onQuery(data: JsonObject): WaggleResponse? {
        val id = requestId(data) ?: return null.also { Log.w(TAG, "waggle.query without an id") }
        var name: String? = rawTitle(data)
        val response = try {
            checkVersion(data)
            val request = QueryRequest.parse(data)
            name = request.name
            val query = Queries.named(request.name) ?: throw WaggleException(ErrorCode.BAD_REQUEST, "Unknown query ${request.name}")
            if (query.name !in settings.actions.first().queries) throw WaggleException(ErrorCode.BLOCKED, "${query.name} isn't enabled")
            WaggleResponse.ok(id, query.run(context, request.params))
        } catch (e: WaggleException) {
            WaggleResponse.error(id, e.code, e.message)
        }
        return finish("query", data, name, null, response)
    }

    /** The user's answer to the confirmation [key]. */
    fun answer(key: Long, allow: Boolean, always: Boolean = false) {
        answers[key]?.complete(
            when {
                !allow -> Answer.DENY
                always -> Answer.ALLOW_ALWAYS
                else -> Answer.ALLOW
            },
        )
    }

    fun saveRule(rule: Rule, id: Long = 0) = scope.launch { db.rules().upsert(RuleEntity.of(rule, id)) }
    fun deleteRule(rule: RuleEntity) = scope.launch { db.rules().delete(rule) }

    /** Puts back the rules Wiggins ships with, replacing the user's. */
    fun resetRules() = scope.launch {
        // One transaction, so the hub is never told about an empty rule list.
        db.withTransaction {
            db.rules().clear()
            db.rules().insertAll(ActionDatabase.DEFAULT_RULES.map { RuleEntity.of(it) })
        }
    }

    fun setUnmatched(mode: Mode) = scope.launch { settings.setUnmatched(mode) }
    fun setQueryEnabled(name: String, enabled: Boolean) = scope.launch { settings.setQueryEnabled(name, enabled) }
    fun clearLog() = scope.launch { db.log().clear() }

    private suspend fun ask(request: IntentRequest, rule: Rule?, intent: Intent): Answer? {
        val key = askKeys.incrementAndGet()
        val answer = CompletableDeferred<Answer>()
        answers[key] = answer
        _asks.update { it + Ask(key, request, rule, target(intent), clock() + ASK_TIMEOUT_S * 1000L) }
        return try {
            withTimeoutOrNull(ASK_TIMEOUT_S * 1000L) { answer.await() }
        } finally {
            answers.remove(key)
            _asks.update { list -> list.filterNot { it.key == key } }
        }
    }

    /** What the system would open for [intent], in words: "Clock (com.android.deskclock)". */
    private fun target(intent: Intent): String? {
        val pm = context.packageManager
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = info.activityInfo.packageName
        if (pkg == "android") return null // The chooser: several apps could answer.
        return "${info.loadLabel(pm)} ($pkg)"
    }

    private fun launch(intent: Intent) {
        // Android drops a background launch without an error, so don't pretend it worked.
        if (!isForeground()) throw WaggleException(ErrorCode.LAUNCH_FAILED, "Wiggins isn't on screen")
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw WaggleException(ErrorCode.NO_HANDLER, e.message ?: "No app handles it")
        } catch (e: Exception) {
            throw WaggleException(ErrorCode.LAUNCH_FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    /** A rejected request's description or action, if it has them as text. */
    private fun rawTitle(data: JsonObject): String? = listOf("description", "action", "name")
        .firstNotNullOfOrNull { (data[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }

    private fun checkVersion(data: JsonObject) {
        val version = (data["version"] as? JsonPrimitive)?.intOrNull ?: return
        if (version != Waggle.VERSION) throw WaggleException(ErrorCode.UNSUPPORTED_VERSION, "Version $version")
    }

    private suspend fun finish(kind: String, data: JsonObject, title: String?, rule: Rule?, response: WaggleResponse): WaggleResponse {
        val request = data.toString().let { if (it.length > LOGGED_REQUEST_LIMIT) it.take(LOGGED_REQUEST_LIMIT) + "…" else it }
        db.log().insert(
            LogEntity(
                timeMillis = clock(),
                kind = kind,
                title = title ?: "(malformed $kind)",
                request = request,
                rule = rule?.toJson()?.toString(),
                outcome = response.error?.wire ?: "ok",
                detail = response.message,
            ),
        )
        db.log().trim()
        return response
    }

    companion object {
        private const val TAG = "PhoneActions"
        const val CLIENT_NAME = "Wiggins"
        const val ASK_TIMEOUT_S = 15
        private const val LOGGED_REQUEST_LIMIT = 4_000

        /** The rule "Allow always" creates for an intent: as specific as the intent itself. */
        fun ruleFor(request: IntentRequest) = Rule(
            action = request.action,
            scheme = request.scheme,
            packageName = request.packageName,
            category = request.categories.firstOrNull(),
            mode = Mode.RUN,
        )
    }
}
