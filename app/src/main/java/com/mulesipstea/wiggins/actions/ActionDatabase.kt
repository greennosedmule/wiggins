package com.mulesipstea.wiggins.actions

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mulesipstea.wiggins.waggle.Mode
import com.mulesipstea.wiggins.waggle.Rule
import kotlinx.coroutines.flow.Flow

/** An allowlist rule as stored (SPEC "Phone actions (Waggle)"); [mode] is its Waggle wire name. */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val scheme: String? = null,
    val packageName: String? = null,
    val category: String? = null,
    val mode: String,
) {
    fun toRule() = Rule(action, scheme, packageName, category, Mode.fromWire(mode) ?: Mode.BLOCK)

    companion object {
        fun of(rule: Rule, id: Long = 0) =
            RuleEntity(id, rule.action, rule.scheme, rule.packageName, rule.category, rule.mode.wire)
    }
}

/** One Waggle request and what became of it. Stays on the phone. */
@Entity(tableName = "action_log")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    /** `intent` or `query`. */
    val kind: String,
    /** The hub's description, or the query's name. */
    val title: String,
    /** The request as received, with long strings elided. */
    val request: String,
    /** The rule that decided it, as Waggle JSON; null if none matched or it's a query. */
    val rule: String?,
    /** `ok`, or a Waggle error code. */
    val outcome: String,
    val detail: String? = null,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY action, id")
    fun all(): Flow<List<RuleEntity>>

    @Upsert
    suspend fun upsert(rule: RuleEntity): Long

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Insert
    suspend fun insertAll(rules: List<RuleEntity>)

    @Query("DELETE FROM rules")
    suspend fun clear()
}

@Dao
interface LogDao {
    @Query("SELECT * FROM action_log ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun recent(limit: Int = LOG_LIMIT): Flow<List<LogEntity>>

    @Insert
    suspend fun insert(entry: LogEntity)

    /** Keeps the newest [keep] entries. */
    @Query("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY timeMillis DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int = LOG_LIMIT)

    @Query("DELETE FROM action_log")
    suspend fun clear()

    companion object {
        const val LOG_LIMIT = 500
    }
}

@Database(entities = [RuleEntity::class, LogEntity::class], version = 4)
abstract class ActionDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun log(): LogDao

    companion object {
        private const val A = "android.intent.action."

        /** The rules Wiggins ships with (SPEC "Phone actions (Waggle)", "Allowlist"). */
        val DEFAULT_RULES = listOf(
            Rule("${A}SET_ALARM", mode = Mode.RUN),
            Rule("${A}SET_TIMER", mode = Mode.RUN),
            Rule("${A}SHOW_ALARMS", mode = Mode.RUN),
            // Added in version 2 (MIGRATION_1_2). Stopping timers, showing them and snoozing
            // touch only what's ringing or open the Clock; dismissing an alarm can cost a
            // wake-up, so it asks.
            Rule("${A}SHOW_TIMERS", mode = Mode.RUN),
            Rule("${A}DISMISS_TIMER", mode = Mode.RUN),
            Rule("${A}SNOOZE_ALARM", mode = Mode.RUN),
            Rule("${A}DISMISS_ALARM", mode = Mode.ASK),
            Rule("${A}MAIN", category = "android.intent.category.LAUNCHER", mode = Mode.RUN),
            Rule("${A}DIAL", scheme = "tel", mode = Mode.ASK),
            Rule("${A}SENDTO", scheme = "smsto", mode = Mode.ASK),
            Rule("${A}SENDTO", scheme = "sms", mode = Mode.ASK),
            // Added in version 3 (MIGRATION_2_3): directions in the user's maps app, and the
            // commands Wiggins carries out itself, all of which work in the car.
            Rule("${A}VIEW", scheme = "geo", mode = Mode.RUN),
            Rule("waggle.torch.set", mode = Mode.RUN),
            Rule("waggle.volume.set", mode = Mode.RUN),
            Rule("waggle.ringer.set", mode = Mode.RUN),
            Rule("waggle.dnd.set", mode = Mode.RUN),
            // Added in version 4 (MIGRATION_3_4): reading notifications aloud on the phone,
            // and replying, which asks (by voice where there's no screen).
            Rule("waggle.notifications.read_aloud", mode = Mode.RUN),
            Rule("waggle.notification.reply", mode = Mode.ASK),
        )

        /** The defaults each schema version introduced, for installs that predate them. */
        private val RULES_ADDED_IN_4 = DEFAULT_RULES.filter { it.action.startsWith("waggle.notification") }
        private val RULES_ADDED_IN_3 = DEFAULT_RULES.filter { (it.scheme == "geo" || it.action.startsWith("waggle.")) && it !in RULES_ADDED_IN_4 }

        private val RULES_ADDED_IN_2 = DEFAULT_RULES.filter {
            it.action.removePrefix(A) in setOf("SHOW_TIMERS", "DISMISS_TIMER", "SNOOZE_ALARM", "DISMISS_ALARM")
        }

        /**
         * Version 2 adds no columns, only default rules: an existing install has no rule for
         * the new Clock actions, so without this they'd fall to `unmatched` (block by
         * default). Each default is added only if the user has no rule at all for its
         * action, so a rule they made themselves is never overridden or duplicated.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = RULES_ADDED_IN_2.forEach { insertDefault(db, it, ifNoRuleForAction = true) }
        }

        /**
         * Version 3 adds no columns either: the navigation rule and the command rules, each
         * only where the user has no rule for its action. A VIEW rule of the user's own
         * (for some other scheme) doesn't stop the geo one, since only its scheme matters.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = RULES_ADDED_IN_3.forEach { rule ->
                if (rule.scheme == "geo") {
                    db.execSQL(
                        "INSERT INTO rules (action, scheme, packageName, category, mode) SELECT ?, ?, NULL, NULL, ? " +
                            "WHERE NOT EXISTS (SELECT 1 FROM rules WHERE action = ? AND scheme = ?)",
                        arrayOf<Any?>(rule.action, rule.scheme, rule.mode.wire, rule.action, rule.scheme),
                    )
                } else {
                    insertDefault(db, rule, ifNoRuleForAction = true)
                }
            }
        }

        /** Version 4: the notification commands' rules, where the user has none of their own. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = RULES_ADDED_IN_4.forEach { insertDefault(db, it, ifNoRuleForAction = true) }
        }

        private fun insertDefault(db: SupportSQLiteDatabase, rule: Rule, ifNoRuleForAction: Boolean = false) {
            val values = arrayOf<Any?>(rule.action, rule.scheme, rule.packageName, rule.category, rule.mode.wire)
            if (ifNoRuleForAction) {
                db.execSQL(
                    "INSERT INTO rules (action, scheme, packageName, category, mode) SELECT ?, ?, ?, ?, ? " +
                        "WHERE NOT EXISTS (SELECT 1 FROM rules WHERE action = ?)",
                    values + rule.action,
                )
            } else {
                db.execSQL("INSERT INTO rules (action, scheme, packageName, category, mode) VALUES (?, ?, ?, ?, ?)", values)
            }
        }

        fun open(context: Context, name: String? = "actions.db"): ActionDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, ActionDatabase::class.java)
            } else {
                Room.databaseBuilder(context, ActionDatabase::class.java, name)
            }
            // onCreate runs only for a new database, which is created at the latest version
            // and so gets every default; the migrations bring older ones up to date.
            return builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DEFAULT_RULES.forEach { insertDefault(db, it) }
            }).build()
        }
    }
}
