package com.mulesipstea.wiggins.actions

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.settings.SettingsRepository
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone side of Waggle: rules decide, the user answers asks, and everything is logged. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PhoneActionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var db: ActionDatabase
    private lateinit var actions: PhoneActions
    private val settings = SettingsRepository(app)
    private var foreground = true

    @Before fun setUp() {
        db = ActionDatabase.open(app, name = null)
        actions = PhoneActions(app, scope, db, settings, isForeground = { foreground })
        // An alarm clock and a dialer the intents can resolve to.
        handle("android.intent.action.SET_ALARM", "com.example.clock", "Alarm")
        handle("android.intent.action.SET_TIMER", "com.example.clock", "Timer")
        handle("android.intent.action.DIAL", "com.example.dialer", "Dial", scheme = "tel")
        handle("org.example.NO_RULE", "com.example.other", "Other")
        runBlocking { settings.setUnmatched(Mode.BLOCK); Queries().forEach { settings.setQueryEnabled(it, false) } }
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun Queries() = listOf("calendar.next", "contacts.lookup", "apps.list")

    private fun handle(action: String, pkg: String, cls: String, scheme: String? = null) {
        val component = ComponentName(pkg, "$pkg.$cls")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                scheme?.let { addDataScheme(it) }
            },
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun intent(id: String, action: String, extra: String = "") =
        json("""{"id":"$id","action":"$action","description":"test"$extra}""")

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    @Test fun runRuleLaunches() = runBlocking {
        val response = actions.onIntent(intent("1", "android.intent.action.SET_TIMER",
            ""","extras":{"android.intent.extra.alarm.LENGTH":{"type":"int","value":60}}"""))
        assertEquals(true, response?.ok)
        val started = nextStarted()
        assertEquals("android.intent.action.SET_TIMER", started?.action)
        assertEquals(60, started?.getIntExtra("android.intent.extra.alarm.LENGTH", 0))
        val log = db.log().recent().first().single()
        assertEquals("ok", log.outcome)
        assertTrue(log.rule!!.contains("SET_TIMER"))
    }

    @Test fun unmatchedIsBlockedByDefault() = runBlocking {
        val response = actions.onIntent(intent("2", "org.example.NO_RULE"))
        assertEquals(ErrorCode.BLOCKED, response?.error)
        assertNull(nextStarted())
        assertEquals("blocked", db.log().recent().first().single().outcome)
    }

    @Test fun backgroundLaunchFails() = runBlocking {
        foreground = false
        assertEquals(ErrorCode.LAUNCH_FAILED, actions.onIntent(intent("3", "android.intent.action.SET_ALARM"))?.error)
    }

    @Test fun askAllowLaunches() = runBlocking {
        val pending = async { actions.onIntent(intent("4", "android.intent.action.DIAL", ""","data":"tel:+15555550100"""")) }
        val ask = withTimeout(2_000) { actions.asks.first { it.isNotEmpty() } }.single()
        assertNotNull(ask.rule) // DIAL tel: is an ask rule by default
        actions.answer(ask.key, allow = true)
        assertEquals(true, pending.await()?.ok)
        assertEquals("android.intent.action.DIAL", nextStarted()?.action)
        assertTrue(actions.asks.value.isEmpty())
    }

    @Test fun askDenyDeclines() = runBlocking {
        val pending = async { actions.onIntent(intent("5", "android.intent.action.DIAL", ""","data":"tel:+15555550100"""")) }
        val ask = withTimeout(2_000) { actions.asks.first { it.isNotEmpty() } }.single()
        actions.answer(ask.key, allow = false)
        assertEquals(ErrorCode.DECLINED, pending.await()?.error)
        assertNull(nextStarted())
    }

    @Test fun allowAlwaysAddsARule() = runBlocking {
        settings.setUnmatched(Mode.ASK)
        val pending = async { actions.onIntent(intent("6", "org.example.NO_RULE")) }
        val ask = withTimeout(2_000) { actions.asks.first { it.isNotEmpty() } }.single()
        assertNull(ask.rule)
        actions.answer(ask.key, allow = true, always = true)
        assertEquals(true, pending.await()?.ok)
        val added = db.rules().all().first().single { it.action == "org.example.NO_RULE" }
        assertEquals("run", added.mode)
        // Next time it just runs.
        assertEquals(true, actions.onIntent(intent("7", "org.example.NO_RULE"))?.ok)
    }

    @Test fun malformedRequestsAreAnsweredAndLogged() = runBlocking {
        val response = actions.onIntent(json("""{"id":"8","action":"android.intent.action.VIEW","data":"content://x/1"}"""))
        assertEquals(ErrorCode.BAD_REQUEST, response?.error)
        assertNull(actions.onIntent(json("""{"action":"android.intent.action.VIEW"}"""))) // No id: can't answer.
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, actions.onIntent(json("""{"id":"9","version":2,"action":"android.intent.action.SET_ALARM"}"""))?.error)
    }

    @Test fun queriesAreOffUntilEnabled() = runBlocking {
        val query = json("""{"id":"q1","name":"apps.list","params":{}}""")
        assertEquals(ErrorCode.BLOCKED, actions.onQuery(query)?.error)
        settings.setQueryEnabled("apps.list", true)
        assertEquals(true, actions.onQuery(query)?.ok)
        assertEquals(ErrorCode.BAD_REQUEST, actions.onQuery(json("""{"id":"q2","name":"sms.read","params":{}}"""))?.error)
    }

    @Test fun capabilitiesListRulesAndEnabledQueries() = runBlocking {
        settings.setQueryEnabled("apps.list", true)
        val caps: JsonObject = actions.capabilities().toJson()
        assertEquals("1", caps["version"].toString())
        assertEquals("\"block\"", caps["unmatched"].toString())
        assertEquals(ActionDatabase.DEFAULT_RULES.size, (caps["rules"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("[\"apps.list\"]", caps["queries"].toString())
    }
}
