package com.mulesipstea.wiggins.waggle.queries

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `phone.status`: the battery, the network, the ringer and Do Not Disturb. No permission
 * needed; the Wi-Fi network's name is left out, since Android ties it to location.
 */
object PhoneStatus : Query {
    override val name = "phone.status"
    override val permission: String? = null

    override suspend fun run(context: Context, params: JsonObject): JsonObject {
        // The sticky battery broadcast: registering no receiver just reads its last value.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val audio = context.getSystemService(AudioManager::class.java)
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return buildJsonObject {
            putJsonObject("battery") {
                if (level >= 0 && scale > 0) put("percent", level * 100 / scale)
                put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                put("source", source(plugged))
            }
            putJsonObject("network") {
                put("type", networkType(caps))
                if (caps != null) put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            put("ringer", ringer(audio.ringerMode))
            put("dnd", dnd != NotificationManager.INTERRUPTION_FILTER_ALL && dnd != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        }
    }

    internal fun source(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "none"
        else -> "other"
    }

    private fun networkType(caps: NetworkCapabilities?) = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
    }

    private fun ringer(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }
}
