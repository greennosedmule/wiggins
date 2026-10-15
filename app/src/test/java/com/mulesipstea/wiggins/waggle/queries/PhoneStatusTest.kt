package com.mulesipstea.wiggins.waggle.queries

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `phone.status`: battery, network, ringer and Do Not Disturb. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PhoneStatusTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun readsTheBatteryRingerAndDnd() = runBlocking {
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 42).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB),
        )
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val status = PhoneStatus.run(app, JsonObject(emptyMap()))
        val battery = status["battery"]!!.jsonObject
        assertEquals("42", battery["percent"]!!.jsonPrimitive.content)
        assertEquals("true", battery["charging"]!!.jsonPrimitive.content)
        assertEquals("usb", battery["source"]!!.jsonPrimitive.content)
        assertEquals("vibrate", status["ringer"]!!.jsonPrimitive.content)
        assertEquals("false", status["dnd"]!!.jsonPrimitive.content)
        // The network is whatever Robolectric reports; its shape is fixed.
        assertEquals(true, status["network"]!!.jsonObject["type"]!!.jsonPrimitive.content in setOf("wifi", "cellular", "other", "none"))
    }

    @Test fun powerSourcesRead() {
        assertEquals(listOf("ac", "usb", "wireless", "none"),
            listOf(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_PLUGGED_WIRELESS, 0).map(PhoneStatus::source))
    }
}
