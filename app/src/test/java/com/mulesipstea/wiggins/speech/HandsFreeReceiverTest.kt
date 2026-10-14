package com.mulesipstea.wiggins.speech

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.WigginsApp
import com.mulesipstea.wiggins.settings.OtherHeadsets
import com.mulesipstea.wiggins.settings.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Hands-free connections arm and disarm trusted devices, and can end a session's trust (SPEC "Permissions"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HandsFreeReceiverTest {
    private val app: WigginsApp = ApplicationProvider.getApplicationContext()
    private val car = "F0:F1:F2:F3:F4:F5"
    private val trusted = setOf(car)
    private val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))

    private fun device(address: String): BluetoothDevice {
        val adapter: BluetoothAdapter = app.getSystemService(BluetoothManager::class.java).adapter
        return adapter.getRemoteDevice(address)
    }

    private fun broadcast(state: Int, address: String = car) = HandsFreeReceiver().onReceive(
        app,
        Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device(address))
            .putExtra(BluetoothProfile.EXTRA_STATE, state),
    )

    /** With unlock once per drive: whether the car's button may run locked as a trusted device. */
    private fun allowed() =
        app.lockedSessions.contextFor(true, listOf(car), trusted, unlockPerDrive = true, OtherHeadsets.ASK_UNLOCK) == PhoneContext.TRUSTED_DEVICE

    @Test fun connectingWhileUnlockedArms() {
        keyguard.setKeyguardLocked(false)
        broadcast(BluetoothProfile.STATE_CONNECTED)
        assertTrue(allowed())
        broadcast(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(allowed())
    }

    @Test fun connectingWhileLockedDoesnt() {
        keyguard.setKeyguardLocked(true)
        broadcast(BluetoothProfile.STATE_CONNECTED)
        assertFalse(allowed())
    }

    @Test fun disconnectingDisarmsAnUnlockFromBefore() {
        app.lockedSessions.onUnlocked(listOf(car))
        keyguard.setKeyguardLocked(true)
        broadcast(BluetoothProfile.STATE_DISCONNECTED)
        broadcast(BluetoothProfile.STATE_CONNECTED)
        assertFalse(allowed())
    }

    @Test fun anUntrustedDeviceConnectingEndsATrustedSessionsTrust() {
        keyguard.setKeyguardLocked(true)
        app.lockedSessions.start(PhoneContext.TRUSTED_DEVICE, trusted)
        broadcast(BluetoothProfile.STATE_CONNECTED, car) // the car again: still trusted
        assertEquals(PhoneContext.TRUSTED_DEVICE, app.lockedSessions.current)
        broadcast(BluetoothProfile.STATE_CONNECTED, "00:11:22:33:44:55")
        assertEquals(PhoneContext.LOCKED, app.lockedSessions.current)
        app.lockedSessions.end()
    }
}
