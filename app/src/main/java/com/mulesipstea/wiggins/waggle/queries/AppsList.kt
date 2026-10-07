package com.mulesipstea.wiggins.waggle.queries

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator

/**
 * `apps.list`: apps with a launcher activity, other than Wiggins, sorted by label, up to
 * `limit` (default 20). With `name`, only those whose label contains it, ignoring case
 * and accents. Visibility of launcher apps comes from the manifest's `<queries>` entry
 * for MAIN/LAUNCHER, so no permission is needed.
 */
object AppsList : Query {
    override val name = "apps.list"
    override val permission: String? = null

    internal data class App(val label: String, val packageName: String)

    override suspend fun run(context: Context, params: JsonObject): JsonObject {
        val filter = params.stringParam("name")
        val limit = params.intParam("limit", default = 20, min = 1)
        val apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    App(info.loadLabel(pm).toString().trim(), pkg)
                }
        }
        return appsData(select(apps, filter, limit))
    }

    /** [apps] whose label contains [filter] (all if it's null or blank), without repeats, by label. */
    internal fun select(apps: List<App>, filter: String?, limit: Int): List<App> {
        val needle = filter?.let(::foldForMatch).orEmpty()
        val collator = Collator.getInstance()
        return apps
            .filter { it.label.isNotEmpty() && foldForMatch(it.label).contains(needle) }
            .distinct()
            .sortedWith(compareBy(collator) { app: App -> app.label }.thenBy { it.packageName })
            .take(limit)
    }

    internal fun appsData(apps: List<App>): JsonObject = buildJsonObject {
        put("apps", buildJsonArray {
            apps.forEach { app ->
                add(buildJsonObject {
                    put("label", app.label)
                    put("package", app.packageName)
                })
            }
        })
    }
}
