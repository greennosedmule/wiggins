package com.mulesipstea.wiggins.actions

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Existing installs get the defaults that later versions add, without losing or
 * duplicating the user's own rules. The version 1 database is built from Room's exported
 * schema (app/schemas), exactly as a 0.4.0-rc2 install has it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ActionDatabaseMigrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private var db: ActionDatabase? = null

    @Before fun clean() {
        app.deleteDatabase(name)
    }

    @After fun close() {
        db?.close()
        app.deleteDatabase(name)
    }

    /** Creates a version 1 database holding [rules] (action to mode), as Room 1 left it. */
    private fun createVersion1(vararg rules: Pair<String, String>) {
        val schema = File("schemas/com.mulesipstea.wiggins.actions.ActionDatabase/1.json")
            .let { if (it.exists()) it else File("app/${it.path}") }
            .readText().let { Json.parseToJsonElement(it).jsonObject["database"]!!.jsonObject }
        val sql = SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        sql.use {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                it.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
            }
            schema["setupQueries"]!!.jsonArray.forEach { query -> it.execSQL(query.jsonPrimitive.content) }
            rules.forEach { (action, mode) ->
                it.execSQL("INSERT INTO rules (action, mode) VALUES (?, ?)", arrayOf(action, mode))
            }
            it.version = 1
        }
    }

    private fun rules(): List<RuleEntity> = runBlocking {
        db = ActionDatabase.open(app, name)
        db!!.rules().all().first()
    }

    @Test fun addsTheClockDefaultsToAnExistingInstall() {
        createVersion1("android.intent.action.SET_ALARM" to "run")
        val byAction = rules().associate { it.action.removePrefix("android.intent.action.") to it.mode }
        assertEquals(
            mapOf(
                "SET_ALARM" to "run",
                "SHOW_TIMERS" to "run",
                "DISMISS_TIMER" to "run",
                "SNOOZE_ALARM" to "run",
                "DISMISS_ALARM" to "ask",
            ),
            byAction,
        )
    }

    @Test fun keepsTheUsersOwnRuleForANewAction() {
        // The user had already blocked dismissing alarms; the default doesn't override it or add a second rule.
        createVersion1("android.intent.action.DISMISS_ALARM" to "block")
        val dismiss = rules().filter { it.action == "android.intent.action.DISMISS_ALARM" }
        assertEquals(listOf("block"), dismiss.map { it.mode })
    }

    @Test fun aNewInstallGetsEveryDefault() {
        val rules = rules().map { it.toRule() }
        assertEquals(ActionDatabase.DEFAULT_RULES.sortedBy { it.toString() }, rules.sortedBy { it.toString() })
    }
}
