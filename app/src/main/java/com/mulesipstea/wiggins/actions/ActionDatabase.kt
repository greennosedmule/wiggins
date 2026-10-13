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

@Database(entities = [RuleEntity::class, LogEntity::class], version = 2)
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
        )

        /** The defaults each schema version introduced, for installs that predate them. */
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
            return builder.addMigrations(MIGRATION_1_2).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DEFAULT_RULES.forEach { insertDefault(db, it) }
            }).build()
        }
    }
}
