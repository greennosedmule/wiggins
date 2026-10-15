package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** `phone.location`: a recent fix at once, as coordinates (SPEC "Location"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PhoneLocationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)

    @Test fun aRecentFixComesBackAtOnce() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 51.5; longitude = -0.12; accuracy = 8f; time = 1_700_000_000_000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        val result = PhoneLocation.run(app, JsonObject(emptyMap()))
        assertEquals("51.5", result["lat"]!!.jsonPrimitive.content)
        assertEquals("-0.12", result["lon"]!!.jsonPrimitive.content)
        assertEquals("8.0", result["accuracy_m"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20Z", result["time"]!!.jsonPrimitive.content)
    }

    @Test fun withoutPermissionOrProvidersItSaysSo() = runBlocking {
        try {
            PhoneLocation.run(app, JsonObject(emptyMap()))
            fail("expected permission_denied")
        } catch (e: WaggleException) {
            assertEquals(ErrorCode.PERMISSION_DENIED, e.code)
        }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .forEach { shadowOf(manager).setProviderEnabled(it, false) }
        try {
            PhoneLocation.run(app, JsonObject(emptyMap()))
            fail("expected no_handler")
        } catch (e: WaggleException) {
            assertEquals(ErrorCode.NO_HANDLER, e.code)
        }
    }
}
