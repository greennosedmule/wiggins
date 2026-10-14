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
import com.mulesipstea.wiggins.settings.WhenLocked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Hands-free connections arm and disarm locked sessions (SPEC "Locked, in the car"). */
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

    private fun broadcast(state: Int) = HandsFreeReceiver().onReceive(
        app,
        Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device(car))
            .putExtra(BluetoothProfile.EXTRA_STATE, state),
    )

    private fun allowed() = app.lockedSessions.allows(WhenLocked.TRUSTED_CAR, trusted, car)

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
}
