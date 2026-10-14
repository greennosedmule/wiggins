package com.mulesipstea.wiggins.speech

import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mulesipstea.wiggins.WigginsApp

/**
 * Follows hands-free connections for [LockedSessions] (SPEC "Locked, in the car"): a
 * device that disconnects is disarmed, and one that connects while the phone is unlocked
 * is armed, since the user has the phone unlocked during this connection. Declared in the
 * manifest so the broadcast arrives when it's sent; see the manifest's comment.
 */
class HandsFreeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val sessions = (context.applicationContext as WigginsApp).lockedSessions
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)) {
            BluetoothProfile.STATE_DISCONNECTED -> sessions.onDisconnected(device.address)
            BluetoothProfile.STATE_CONNECTED ->
                if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    sessions.onUnlocked(listOf(device.address))
                }
        }
    }
}
