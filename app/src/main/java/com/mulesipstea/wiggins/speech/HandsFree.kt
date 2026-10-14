package com.mulesipstea.wiggins.speech

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Bluetooth hands-free link (SPEC "In the car"): while Wiggins listens, thinks and
 * speaks, a connected car or headset carries both the microphone and the speech, the way
 * it does for a phone's built-in assistant.
 *
 * It uses the Bluetooth voice-recognition session: [BluetoothHeadset.startVoiceRecognition]
 * tells the car an assistant is active and opens the hands-free audio (SCO). It also
 * completes a session the car started itself. A car's voice button sends Android the
 * hands-free "start voice recognition" command; Android opens Wiggins with
 * ACTION_VOICE_COMMAND and gives up after a few seconds unless an app takes the session
 * up like this. While the link is up it's the communication device, so voice-communication
 * audio follows it, and [route] says which devices to prefer.
 *
 * Needs the runtime permission BLUETOOTH_CONNECT; without it, or with no hands-free device
 * connected, [open] returns false and speech stays on its usual route.
 */
class HandsFree(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var headset: BluetoothHeadset? = null
    private var device: BluetoothDevice? = null

    /** Whether the link is up. Main thread only. */
    var isOpen = false
        private set

    private var binding: CompletableDeferred<BluetoothHeadset?>? = null

    fun permitted() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * The headset profile, bound on first use (after the permission may have been granted)
     * and kept; null if Bluetooth is off or the profile doesn't answer within a second.
     */
    private suspend fun headset(): BluetoothHeadset? {
        headset?.let { return it }
        val pending = binding ?: CompletableDeferred<BluetoothHeadset?>().also { deferred ->
            binding = deferred
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val asked = adapter?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        headset = proxy as BluetoothHeadset
                        deferred.complete(headset)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        headset = null
                        isOpen = false
                        binding = null
                    }
                },
                BluetoothProfile.HEADSET,
            ) ?: false
            if (!asked) {
                binding = null
                deferred.complete(null)
            }
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }
    }

    /** Addresses of the connected hands-free devices; empty without the permission or Bluetooth. */
    @SuppressLint("MissingPermission")
    suspend fun connectedAddresses(): List<String> {
        if (!permitted()) return emptyList()
        return headset()?.connectedDevices?.map { it.address }.orEmpty()
    }

    /** The device [open] would use, or null if none is connected. */
    suspend fun connectedAddress(): String? = connectedAddresses().firstOrNull()

    /**
     * Opens the link if a hands-free device is connected; returns whether it's up. Waits up
     * to [OPEN_TIMEOUT_MS] for the audio to connect. Calling it while it's open is cheap.
     */
    @SuppressLint("MissingPermission")
    suspend fun open(): Boolean {
        if (!permitted()) return false
        val headset = headset() ?: return false
        val target = headset.connectedDevices.firstOrNull() ?: return false.also { isOpen = false }
        if (isOpen && device == target && headset.isAudioConnected(target)) return true
        device = target
        if (!headset.isAudioConnected(target) && !headset.startVoiceRecognition(target)) {
            Log.w(TAG, "the headset refused voice recognition")
            return false
        }
        val connected = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            while (!headset.isAudioConnected(target)) delay(POLL_MS)
        } != null
        if (!connected) {
            Log.w(TAG, "hands-free audio didn't connect in ${OPEN_TIMEOUT_MS}ms")
            close()
            return false
        }
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?.let(audio::setCommunicationDevice)
        isOpen = true
        return true
    }

    /** Ends the voice-recognition session and gives the car its media back. */
    @SuppressLint("MissingPermission")
    fun close() {
        val target = device
        isOpen = false
        device = null
        audio.clearCommunicationDevice()
        if (target != null && permitted()) runCatching { headset?.stopVoiceRecognition(target) }
    }

    /** The devices and attributes to use now: the link's while it's open, the usual ones otherwise. */
    fun route(): SpeechRoute {
        if (!isOpen) return SpeechRoute.DEFAULT
        val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return SpeechRoute.DEFAULT
        val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return SpeechRoute.handsFree(output, input)
    }

    private companion object {
        const val TAG = "HandsFree"
        const val OPEN_TIMEOUT_MS = 4_000L
        const val BIND_TIMEOUT_MS = 1_000L
        const val POLL_MS = 100L
    }
}
