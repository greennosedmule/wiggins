package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * `phone.location`: where the phone is, as coordinates (SPEC "Location"). A fix at most
 * `max_age_s` old (120 by default) comes back at once; otherwise the phone asks for a new
 * one and answers within [WAIT_MS], or `timeout`. There's no address: the phone has no
 * geocoder without Google's services, and the hub describes the place itself.
 */
object PhoneLocation : Query {
    override val name = "phone.location"
    override val permission = Manifest.permission.ACCESS_FINE_LOCATION

    /** How long the phone waits for a fix; the hub waits a little longer. */
    const val WAIT_MS = 30_000L

    override suspend fun run(context: Context, params: JsonObject): JsonObject {
        val maxAgeS = params.intParam("max_age_s", default = 120, min = 0, max = 86_400)
        requirePermission(context, permission)
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw WaggleException(ErrorCode.NO_HANDLER, "Location is off on the phone")
        val recent = try {
            providers.mapNotNull(manager::getLastKnownLocation).maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: SecurityException) {
            throw WaggleException(ErrorCode.PERMISSION_DENIED, "Android refused the location: ${e.message}")
        }
        val fix = recent?.takeIf { ageMs(it) <= maxAgeS * 1000L } ?: current(manager, providers.first())
            ?: throw WaggleException(ErrorCode.TIMEOUT, "No location fix in ${WAIT_MS / 1000}s")
        return buildJsonObject {
            put("lat", fix.latitude)
            put("lon", fix.longitude)
            if (fix.hasAccuracy()) put("accuracy_m", fix.accuracy.toDouble())
            put("time", wireInstant(fix.time))
        }
    }

    private fun ageMs(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private suspend fun current(manager: LocationManager, provider: String): Location? = withTimeoutOrNull(WAIT_MS) {
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                manager.getCurrentLocation(provider, cancel, Runnable::run) { location -> if (cont.isActive) cont.resume(location) }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
