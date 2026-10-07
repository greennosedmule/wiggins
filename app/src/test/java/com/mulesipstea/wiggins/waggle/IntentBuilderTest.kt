package com.mulesipstea.wiggins.waggle

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class IntentBuilderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val clock = ComponentName("com.example.clock", "com.example.clock.SetAlarm")
    private val dialer = ComponentName("com.example.dialer", "com.example.dialer.Dial")
    private val browser = ComponentName("com.example.browser", "com.example.browser.View")
    private val camera = ComponentName("com.example.camera", "com.example.camera.Main")

    @Before fun installApps() {
        handle(clock, IntentFilter("android.intent.action.SET_ALARM").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        handle(dialer, IntentFilter(Intent.ACTION_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") })
        handle(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https"); addDataType("text/html")
        })
        // A typical launcher filter: no CATEGORY_DEFAULT.
        handle(camera, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun handle(component: ComponentName, filter: IntentFilter) {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, filter)
    }

    private fun request(json: String) = IntentRequest.parse(Json.parseToJsonElement(json).jsonObject)

    private fun build(json: String) = IntentBuilder.build(context, request(json))

    private fun assertFails(code: ErrorCode, json: String) {
        val e = assertThrows(WaggleException::class.java) { build(json) }
        assertEquals(e.message, code, e.code)
    }

    @Test fun buildsTypedExtras() {
        val intent = build("""{"id": "r", "action": "android.intent.action.SET_ALARM", "extras": {
            "i": {"type": "int", "value": 6},
            "l": {"type": "long", "value": 9223372036854775807},
            "f": {"type": "float", "value": 1.5},
            "fi": {"type": "float", "value": 2},
            "d": {"type": "double", "value": 0.25},
            "b": {"type": "bool", "value": true},
            "s": {"type": "string", "value": "Wake up"},
            "sa": {"type": "string[]", "value": ["a", "b"]},
            "u": {"type": "uri", "value": "HTTPS://example.com/x"}
        }}""")
        val extras = intent.extras!!
        assertEquals(6, extras.get("i") as Int)
        assertEquals(Long.MAX_VALUE, extras.get("l") as Long)
        assertEquals(1.5f, extras.get("f") as Float)
        assertEquals(2f, extras.get("fi") as Float)
        assertEquals(0.25, extras.get("d") as Double, 0.0)
        assertEquals(true, extras.get("b") as Boolean)
        assertEquals("Wake up", extras.get("s") as String)
        @Suppress("UNCHECKED_CAST")
        assertArrayEquals(arrayOf("a", "b"), extras.get("sa") as Array<String>)
        assertEquals(Uri.parse("https://example.com/x"), extras.get("u") as Uri)
        assertEquals(9, extras.size())
    }

    @Test fun onlyNewTaskFlag() {
        val intent = build("""{"id": "r", "action": "android.intent.action.SET_ALARM",
            "flags": 1, "extras": {"android.intent.extra.alarm.HOUR": {"type": "int", "value": 6}}}""")
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.clipData)
        assertEquals("android.intent.action.SET_ALARM", intent.action)
    }

    @Test fun normalizesDataSchemeSoFiltersMatch() {
        val intent = build("""{"id": "r", "action": "android.intent.action.DIAL", "data": "TEL:+15551234567"}""")
        assertEquals(Uri.parse("tel:+15551234567"), intent.data)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.component)
    }

    @Test fun setsDataTypeCategoriesAndPackage() {
        val intent = build("""{"id": "r", "action": "android.intent.action.VIEW", "data": "https://example.com/",
            "mime_type": "text/html", "categories": ["android.intent.category.BROWSABLE"],
            "package": "com.example.browser"}""")
        assertEquals(Uri.parse("https://example.com/"), intent.data)
        assertEquals("text/html", intent.type)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertEquals("com.example.browser", intent.`package`)
    }

    @Test fun opensAnAppThroughItsLauncherActivity() {
        val intent = build("""{"id": "r", "action": "android.intent.action.MAIN",
            "categories": ["android.intent.category.LAUNCHER"], "package": "com.example.camera"}""")
        assertEquals(camera, intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test fun noHandler() {
        assertFails(ErrorCode.NO_HANDLER, """{"id": "r", "action": "com.example.NOTHING"}""")
        assertFails(ErrorCode.NO_HANDLER, """{"id": "r", "action": "android.intent.action.DIAL", "data": "sip:a@b"}""")
        assertFails(ErrorCode.NO_HANDLER, """{"id": "r", "action": "android.intent.action.SET_ALARM", "package": "com.example.other"}""")
        assertFails(ErrorCode.NO_HANDLER, """{"id": "r", "action": "android.intent.action.MAIN",
            "categories": ["android.intent.category.LAUNCHER"], "package": "com.example.missing"}""")
        // A non-DEFAULT filter isn't reachable implicitly unless it's an app launch by package.
        assertFails(ErrorCode.NO_HANDLER, """{"id": "r", "action": "android.intent.action.MAIN", "package": "com.example.camera"}""")
    }

    @Test fun refusesToResolveToWiggins() {
        // AssistActivity's filter (the app manifest) handles ASSIST.
        assertFails(ErrorCode.BAD_REQUEST, """{"id": "r", "action": "android.intent.action.ASSIST"}""")
        assertFails(ErrorCode.BAD_REQUEST, """{"id": "r", "action": "android.intent.action.MAIN",
            "categories": ["android.intent.category.LAUNCHER"], "package": "${context.packageName}"}""")
        assertFails(ErrorCode.BAD_REQUEST, """{"id": "r", "action": "com.example.NOTHING", "package": "${context.packageName}"}""")
        // An ambiguous launch with Wiggins among the candidates.
        handle(clock, IntentFilter(Intent.ACTION_ASSIST).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        assertFails(ErrorCode.BAD_REQUEST, """{"id": "r", "action": "android.intent.action.ASSIST"}""")
    }

    @Test fun actionCallIsBlocked() {
        handle(dialer, IntentFilter(Intent.ACTION_CALL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") })
        assertFails(ErrorCode.BLOCKED, """{"id": "r", "action": "android.intent.action.CALL", "data": "tel:+15551234567"}""")
        assertFails(ErrorCode.BLOCKED, """{"id": "r", "action": "android.intent.action.CALL_PRIVILEGED", "data": "tel:1"}""")
    }

    @Test fun rechecksForbiddenSchemesOnHandmadeRequests() {
        val handmade = IntentRequest("r", Intent.ACTION_VIEW, null, "Content://x/y", null, emptyList(), null, emptyMap())
        val e = assertThrows(WaggleException::class.java) { IntentBuilder.build(context, handmade) }
        assertEquals(ErrorCode.BAD_REQUEST, e.code)
    }
}
