package com.mulesipstea.wiggins.waggle.queries

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppsListTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun launcher(label: String, pkg: String, activity: String = "$pkg.Main") = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = activity
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        nonLocalizedLabel = label
    }

    private fun run(json: String): JsonObject = runBlocking {
        AppsList.run(app, Json.parseToJsonElement(json).jsonObject)
    }

    private fun labels(result: JsonObject) = result.getValue("apps").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content }

    @Before fun setUp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            intent,
            listOf(
                launcher("Camera", "app.grapheneos.camera"),
                launcher("calculator", "com.example.calc"),
                launcher("Wiggins", app.packageName),
                launcher("Clock", "com.example.clock"),
                launcher("Clock", "com.example.clock", "com.example.clock.Main2"), // a second launcher activity
                launcher("Éditeur", "com.example.editor"),
                launcher("Maps", "com.example.maps"),
            ),
        )
    }

    @Test fun listsLauncherAppsByLabelWithoutWiggins() {
        assertEquals(listOf("calculator", "Camera", "Clock", "Éditeur", "Maps"), labels(run("{}")))
    }

    @Test fun shape() {
        val first = run("""{"name": "camera"}""").getValue("apps").jsonArray.single()
        assertEquals(Json.parseToJsonElement("""{"label": "Camera", "package": "app.grapheneos.camera"}"""), first)
    }

    @Test fun filtersByLabelIgnoringCaseAndAccents() {
        assertEquals(listOf("calculator", "Camera"), labels(run("""{"name": "CA"}""")))
        assertEquals(listOf("Éditeur"), labels(run("""{"name": "edit"}""")))
        assertEquals(listOf("calculator", "Camera", "Clock", "Éditeur", "Maps"), labels(run("""{"name": null}""")))
        assertEquals(emptyList<String>(), labels(run("""{"name": "wiggins"}""")))
    }

    @Test fun limit() {
        assertEquals(listOf("calculator", "Camera"), labels(run("""{"limit": 2}""")))
        val many = (1..30).map { AppsList.App("App %02d".format(it), "com.example.app$it") }
        assertEquals(20, AppsList.select(many, null, 20).size)
    }

    @Test fun badParams() {
        for (bad in listOf("""{"name": 3}""", """{"name": true}""", """{"limit": 0}""", """{"limit": "20"}""", """{"limit": 2.5}""")) {
            val e = assertThrows(bad, WaggleException::class.java) { run(bad) }
            assertEquals(bad, ErrorCode.BAD_REQUEST, e.code)
        }
    }
}
