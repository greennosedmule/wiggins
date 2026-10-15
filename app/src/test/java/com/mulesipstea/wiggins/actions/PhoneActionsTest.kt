package com.mulesipstea.wiggins.actions

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.settings.PhoneContext
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
    private var where = PhoneContext.UNLOCKED
    private val active = mutableListOf<android.service.notification.StatusBarNotification>()
    private var voiceAnswer: Boolean? = null
    private val spoken = mutableListOf<String>()

    @Before fun setUp() {
        db = ActionDatabase.open(app, name = null)
        actions = PhoneActions(app, scope, db, settings, isForeground = { foreground }, phoneContext = { where },
            notifications = Notifications(app) { active }, speak = { spoken += it }, confirmByVoice = { voiceAnswer })
        // An alarm clock and a dialer the intents can resolve to.
        handle("android.intent.action.SET_ALARM", "com.example.clock", "Alarm")
        handle("android.intent.action.SET_TIMER", "com.example.clock", "Timer")
        handle("android.intent.action.DISMISS_TIMER", "com.example.clock", "DismissTimer")
        handle("android.intent.action.DISMISS_ALARM", "com.example.clock", "DismissAlarm")
        handle("android.intent.action.DIAL", "com.example.dialer", "Dial", scheme = "tel")
        handle("org.example.NO_RULE", "com.example.other", "Other")
        runBlocking {
            settings.setUnmatched(Mode.BLOCK)
            Queries().forEach { settings.setQueryEnabled(it, false) }
            PhoneContext.entries.forEach { settings.setActionsIn(it, it != PhoneContext.LOCKED) }
            settings.setNotificationApps(emptySet())
            PhoneContext.entries.forEach {
                settings.setReadAloudOnly(it, false)
                settings.setVoiceAskIn(it, it == PhoneContext.TRUSTED_DEVICE)
                settings.setLocationIn(it, it != PhoneContext.LOCKED)
            }
        }
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun Queries() = listOf("calendar.next", "contacts.lookup", "apps.list", "phone.status", "notifications.list", "phone.location")

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

    @Test fun clockDefaultsRunTimersAndAskBeforeDismissingAnAlarm() = runBlocking {
        assertEquals(true, actions.onIntent(intent("10", "android.intent.action.DISMISS_TIMER"))?.ok)
        assertEquals("android.intent.action.DISMISS_TIMER", nextStarted()?.action)

        val extras = ""","extras":{"android.intent.extra.alarm.SEARCH_MODE":{"type":"string","value":"android.next"}}"""
        val pending = async { actions.onIntent(intent("11", "android.intent.action.DISMISS_ALARM", extras)) }
        val ask = withTimeout(2_000) { actions.asks.first { it.isNotEmpty() } }.single()
        assertEquals("ask", ask.rule?.mode?.wire)
        actions.answer(ask.key, allow = true)
        assertEquals(true, pending.await()?.ok)
        val started = nextStarted()
        assertEquals("android.intent.action.DISMISS_ALARM", started?.action)
        assertEquals("android.next", started?.getStringExtra("android.intent.extra.alarm.SEARCH_MODE"))
    }

    @Test fun malformedRequestsAreAnsweredAndLogged() = runBlocking {
        val response = actions.onIntent(json("""{"id":"8","action":"android.intent.action.VIEW","data":"content://x/1"}"""))
        assertEquals(ErrorCode.BAD_REQUEST, response?.error)
        assertNull(actions.onIntent(json("""{"action":"android.intent.action.VIEW"}"""))) // No id: can't answer.
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, actions.onIntent(json("""{"id":"9","version":2,"action":"android.intent.action.SET_ALARM"}"""))?.error)
    }

    /** Through a trusted device (SPEC "Permissions"): allow rules run, ask rules are refused without an ask card. */
    @Test fun aTrustedDeviceRunsAllowRulesButNeverAsks() = runBlocking {
        where = PhoneContext.TRUSTED_DEVICE
        assertEquals(true, actions.onIntent(intent("t1", "android.intent.action.SET_TIMER"))?.ok)
        assertEquals("android.intent.action.SET_TIMER", nextStarted()?.action)
        val dial = actions.onIntent(intent("t2", "android.intent.action.DIAL", ""","data":"tel:+15555550100""""))
        assertEquals(ErrorCode.BLOCKED, dial?.error)
        assertTrue(actions.asks.value.isEmpty())
        assertNull(nextStarted())
    }

    /** Locked otherwise, with phone actions off there by default: nothing runs, not even a query. */
    @Test fun aLockedPhoneRunsNothingByDefault() = runBlocking {
        where = PhoneContext.LOCKED
        settings.setQueryEnabled("apps.list", true)
        assertEquals(ErrorCode.BLOCKED, actions.onIntent(intent("l1", "android.intent.action.SET_TIMER"))?.error)
        assertEquals(ErrorCode.BLOCKED, actions.onQuery(json("""{"id":"l2","name":"apps.list","params":{}}"""))?.error)
        assertNull(nextStarted())
        assertEquals(listOf("blocked", "blocked"), db.log().recent().first().map { it.outcome })
        where = PhoneContext.UNLOCKED
        assertEquals(true, actions.onQuery(json("""{"id":"l3","name":"apps.list","params":{}}"""))?.ok)
    }

    /** The user decides where phone actions run at all. */
    @Test fun phoneActionsFollowTheUsersContexts() = runBlocking {
        settings.setActionsIn(PhoneContext.UNLOCKED, false)
        assertEquals(ErrorCode.BLOCKED, actions.onIntent(intent("c1", "android.intent.action.SET_TIMER"))?.error)
        settings.setActionsIn(PhoneContext.UNLOCKED, true)
        settings.setActionsIn(PhoneContext.LOCKED, true)
        where = PhoneContext.LOCKED
        assertEquals(true, actions.onIntent(intent("c2", "android.intent.action.SET_TIMER"))?.ok)
    }

    /** Commands (WAGGLE.md "Commands"): judged by the rules like intents, with their variant as the category. */
    @Test fun aCommandRunsByItsRule() = runBlocking {
        val response = actions.onCommand(json("""{"id":"k1","name":"waggle.ringer.set","params":{"mode":"vibrate"}}"""))
        assertEquals(true, response?.ok)
        assertEquals(android.media.AudioManager.RINGER_MODE_VIBRATE, app.getSystemService(android.media.AudioManager::class.java).ringerMode)
        assertEquals("ok", db.log().recent().first().single().outcome)
    }

    @Test fun aVariantRuleCanAskForOneMode() = runBlocking {
        db.rules().upsert(RuleEntity.of(com.mulesipstea.wiggins.waggle.Rule("waggle.ringer.set", category = "silent", mode = Mode.ASK)))
        val pending = async { actions.onCommand(json("""{"id":"k2","name":"waggle.ringer.set","params":{"mode":"silent"}}""")) }
        val ask = withTimeout(2_000) { actions.asks.first { it.isNotEmpty() } }.single()
        assertEquals("Ringer: silent", ask.request.description)
        actions.answer(ask.key, allow = false)
        assertEquals(ErrorCode.DECLINED, pending.await()?.error)
        // Other modes still run.
        assertEquals(true, actions.onCommand(json("""{"id":"k3","name":"waggle.ringer.set","params":{"mode":"normal"}}"""))?.ok)
    }

    @Test fun commandsFollowContextsAndNeverAskLocked() = runBlocking {
        db.rules().upsert(RuleEntity.of(com.mulesipstea.wiggins.waggle.Rule("waggle.ringer.set", category = "silent", mode = Mode.ASK)))
        where = PhoneContext.TRUSTED_DEVICE
        assertEquals(ErrorCode.BLOCKED, actions.onCommand(json("""{"id":"k4","name":"waggle.ringer.set","params":{"mode":"silent"}}"""))?.error)
        assertTrue(actions.asks.value.isEmpty())
        where = PhoneContext.LOCKED // phone actions off there by default
        assertEquals(ErrorCode.BLOCKED, actions.onCommand(json("""{"id":"k5","name":"waggle.ringer.set","params":{"mode":"normal"}}"""))?.error)
    }

    @Test fun badCommandsAreBadRequests() = runBlocking {
        assertEquals(ErrorCode.BAD_REQUEST, actions.onCommand(json("""{"id":"k6","name":"waggle.toaster.set","params":{}}"""))?.error)
        assertEquals(ErrorCode.BAD_REQUEST, actions.onCommand(json("""{"id":"k7","name":"waggle.ringer.set","params":{"mode":"loud"}}"""))?.error)
        // An intent may not borrow a command's name, and so its rule.
        assertEquals(ErrorCode.BAD_REQUEST, actions.onIntent(intent("k8", "waggle.torch.set"))?.error)
        assertNull(actions.onCommand(json("""{"name":"waggle.torch.set"}""")))
    }

    @Test fun capabilitiesListTheCommandsThePhoneCanDo() = runBlocking {
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        val commands = actions.capabilities().toJson()["commands"].toString()
        // No flash on Robolectric's phone, and no Do Not Disturb access yet.
        assertEquals("[\"waggle.volume.set\",\"waggle.ringer.set\"]", commands)
    }

    private fun chat(title: String, from: String, text: String) {
        val pending = android.app.PendingIntent.getBroadcast(app, active.size, Intent("org.example.REPLY").setPackage(app.packageName), android.app.PendingIntent.FLAG_MUTABLE)
        val reply = androidx.core.app.NotificationCompat.Action.Builder(0, "Reply", pending)
            .addRemoteInput(androidx.core.app.RemoteInput.Builder("reply_text").build()).build()
        val style = androidx.core.app.NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("Me").build())
            .setConversationTitle(title).addMessage(text, 1_000, androidx.core.app.Person.Builder().setName(from).build())
        val n = androidx.core.app.NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setStyle(style).addAction(reply).build()
        @Suppress("DEPRECATION")
        active += android.service.notification.StatusBarNotification("org.example.chat", "org.example.chat", active.size + 1, null, 0, 0, 0, n, android.os.Process.myUserHandle(), 1_000)
    }

    /** Notifications (SPEC "Notifications"): only picked apps; a reply asks by default, and the rules see its app. */
    @Test fun listingAndReplyingFollowTheRules() = runBlocking {
        chat("Alex", "Alex", "Running late")
        settings.setQueryEnabled("notifications.list", true)
        val none = actions.onQuery(json("""{"id":"n1","name":"notifications.list","params":{}}"""))
        assertEquals("[]", none?.data?.get("notifications").toString()) // no apps picked yet
        settings.setNotificationApps(setOf("org.example.chat"))
        val listed = actions.onQuery(json("""{"id":"n2","name":"notifications.list","params":{"from":"alex"}}"""))
        assertTrue(listed?.data?.get("notifications").toString().contains("Running late"))
        // Allow replies for this app only, and the reply goes straight through.
        db.rules().upsert(RuleEntity.of(com.mulesipstea.wiggins.waggle.Rule("waggle.notification.reply", packageName = "org.example.chat", mode = Mode.RUN)))
        assertEquals(true, actions.onCommand(json("""{"id":"n3","name":"waggle.notification.reply","params":{"to":"Alex","text":"On my way"}}"""))?.ok)
        assertEquals("On my way", android.app.RemoteInput.getResultsFromIntent(shadowOf(app).broadcastIntents.last())?.getCharSequence("reply_text").toString())
        assertEquals(ErrorCode.NOT_FOUND, actions.onCommand(json("""{"id":"n4","name":"waggle.notification.reply","params":{"to":"Sam","text":"Hi"}}"""))?.error)
        assertEquals(ErrorCode.BAD_REQUEST, actions.onCommand(json("""{"id":"n5","name":"waggle.notification.reply","params":{"text":"Hi"}}"""))?.error)
    }

    /** Through a trusted device, a reply that asks is read back and sent only on a spoken yes. */
    @Test fun aReplyIsConfirmedByVoiceWhereThereIsNoScreen() = runBlocking {
        chat("Alex", "Alex", "Running late")
        settings.setNotificationApps(setOf("org.example.chat"))
        db.rules().upsert(RuleEntity.of(com.mulesipstea.wiggins.waggle.Rule("waggle.notification.reply", mode = Mode.ASK)))
        where = PhoneContext.TRUSTED_DEVICE
        val sentBefore = shadowOf(app).broadcastIntents.size
        voiceAnswer = false
        assertEquals(ErrorCode.DECLINED, actions.onCommand(json("""{"id":"v1","name":"waggle.notification.reply","params":{"to":"Alex","text":"On my way"}}"""))?.error)
        voiceAnswer = null
        assertEquals(ErrorCode.TIMEOUT, actions.onCommand(json("""{"id":"v2","name":"waggle.notification.reply","params":{"to":"Alex","text":"On my way"}}"""))?.error)
        assertEquals(sentBefore, shadowOf(app).broadcastIntents.size)
        voiceAnswer = true
        assertEquals(true, actions.onCommand(json("""{"id":"v3","name":"waggle.notification.reply","params":{"to":"Alex","text":"On my way"}}"""))?.ok)
        assertTrue(actions.asks.value.isEmpty()) // never a card
        // With voice asks off there, it's refused.
        settings.setVoiceAskIn(PhoneContext.TRUSTED_DEVICE, false)
        assertEquals(ErrorCode.BLOCKED, actions.onCommand(json("""{"id":"v4","name":"waggle.notification.reply","params":{"to":"Alex","text":"x"}}"""))?.error)
    }

    @Test fun readAloudSpeaksOnThePhone() = runBlocking {
        chat("Alex", "Alex", "Running late")
        settings.setNotificationApps(setOf("org.example.chat"))
        val response = actions.onCommand(json("""{"id":"r1","name":"waggle.notifications.read_aloud","params":{}}"""))
        assertEquals("{\"count\":1}", response?.data.toString())
        assertEquals(1, spoken.size)
        assertTrue(spoken.single().contains("Running late"))
    }

    @Test fun readAloudOnlyContextsKeepTheListFromTheHub() = runBlocking {
        settings.setQueryEnabled("notifications.list", true)
        settings.setReadAloudOnly(PhoneContext.UNLOCKED, true)
        assertEquals(ErrorCode.BLOCKED, actions.onQuery(json("""{"id":"o1","name":"notifications.list","params":{}}"""))?.error)
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
