package com.mulesipstea.wiggins.speech

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothHeadset

/**
 * The Bluetooth voice-recognition session against Robolectric's headset: when it opens,
 * and that closing ends it. Real cars and headsets, SCO routing and the car's own
 * behaviour can't be tested here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HandsFreeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val headset: BluetoothHeadset = Shadow.newInstanceOf(BluetoothHeadset::class.java)
    private val car = adapter.getRemoteDevice("00:11:22:33:44:55")

    @Before fun setUp() {
        shadowOf(adapter).setProfileProxy(BluetoothProfile.HEADSET, headset)
        shadowOf(adapter).setEnabled(true)
    }

    /** The car connects: the headset profile lists it, and the adapter reports the profile connected. */
    private fun connect() {
        shadowHeadset.addConnectedDevice(car)
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.HEADSET, BluetoothProfile.STATE_CONNECTED)
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private val shadowHeadset: ShadowBluetoothHeadset get() = Shadow.extract(headset)

    @Test fun withoutThePermissionItStaysOff() = runBlocking {
        connect()
        val handsFree = HandsFree(app)
        assertFalse(handsFree.open())
        assertFalse(headset.isAudioConnected(car))
        assertEquals(SpeechRoute.DEFAULT, handsFree.route())
    }

    @Test fun withNothingConnectedItStaysOff() = runBlocking {
        grant()
        val handsFree = HandsFree(app)
        assertFalse(handsFree.open())
        assertFalse(handsFree.isOpen)
    }

    @Test fun opensTheVoiceRecognitionSessionAndClosesIt() = runBlocking {
        grant()
        connect()
        val handsFree = HandsFree(app)
        assertTrue(handsFree.open())
        assertTrue(handsFree.isOpen)
        assertTrue(headset.isAudioConnected(car))
        assertTrue(handsFree.open()) // already open: cheap, still true
        handsFree.close()
        assertFalse(handsFree.isOpen)
        assertFalse(headset.isAudioConnected(car))
    }

    /**
     * With Bluetooth off, or no headset connection reported by the adapter, it stays off
     * without asking the headset profile, whose binding never completes with Bluetooth off.
     */
    @Test fun withBluetoothOffItDoesntWaitForTheProfile() = runBlocking {
        grant()
        connect()
        shadowOf(adapter).setEnabled(false)
        val handsFree = HandsFree(app)
        assertFalse(handsFree.open())
        assertEquals(emptyList<String>(), handsFree.connectedAddresses())
        assertFalse(headset.isAudioConnected(car))
    }

    @Test fun aListedDeviceTheAdapterDoesntReportConnectedIsIgnored() = runBlocking {
        grant()
        shadowHeadset.addConnectedDevice(car)
        val handsFree = HandsFree(app)
        assertFalse(handsFree.open())
        assertEquals(emptyList<String>(), handsFree.connectedAddresses())
    }

    @Test fun reportsTheConnectedDevice() = runBlocking {
        grant()
        connect()
        assertEquals(listOf(car.address), HandsFree(app).connectedAddresses())
    }
}
