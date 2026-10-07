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

@Database(entities = [RuleEntity::class, LogEntity::class], version = 1)
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
            Rule("${A}MAIN", category = "android.intent.category.LAUNCHER", mode = Mode.RUN),
            Rule("${A}DIAL", scheme = "tel", mode = Mode.ASK),
            Rule("${A}SENDTO", scheme = "smsto", mode = Mode.ASK),
            Rule("${A}SENDTO", scheme = "sms", mode = Mode.ASK),
        )

        fun open(context: Context, name: String? = "actions.db"): ActionDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, ActionDatabase::class.java)
            } else {
                Room.databaseBuilder(context, ActionDatabase::class.java, name)
            }
            return builder.addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DEFAULT_RULES.forEach { rule ->
                        db.execSQL(
                            "INSERT INTO rules (action, scheme, packageName, category, mode) VALUES (?, ?, ?, ?, ?)",
                            arrayOf(rule.action, rule.scheme, rule.packageName, rule.category, rule.mode.wire),
                        )
                    }
                }
            }).build()
        }
    }
}
