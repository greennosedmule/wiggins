package com.mulesipstea.wiggins.waggle.commands

import android.app.Application
import android.app.NotificationManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/** The phone's own commands (WAGGLE.md "Commands"), against Android's services. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CommandsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun fails(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: WaggleException) {
            assertEquals(code, e.code)
        }
    }

    private fun addFlash() {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowOf(app.getSystemService(CameraManager::class.java)).addCamera("0", characteristics)
    }

    @Test fun theFlashlightTurnsOnAndOff() {
        assertFalse(Torch.available(app))
        fails(ErrorCode.NO_HANDLER) { Torch.run(app, json("""{"on":true}""")) }
        addFlash()
        assertTrue(Torch.available(app))
        Torch.run(app, json("""{"on":true}"""))
        assertTrue(shadowOf(app.getSystemService(CameraManager::class.java)).getTorchMode("0"))
        assertEquals("off", Torch.variant(json("""{"on":false}""")))
        fails(ErrorCode.BAD_REQUEST) { Torch.run(app, json("""{"on":"yes"}""")) }
        fails(ErrorCode.BAD_REQUEST) { Torch.run(app, JsonObject(emptyMap())) }
    }

    @Test fun volumeByPercentOrStep() {
        shadowOf(audio).setStreamMaxVolume(15) // every stream
        val half = Volume.run(app, json("""{"level":50}"""))
        assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) // 7.5 rounds up
        assertEquals(json("""{"stream":"media","level":53}"""), half)
        Volume.run(app, json("""{"stream":"alarm","level":100}"""))
        assertEquals(15, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        fails(ErrorCode.BAD_REQUEST) { Volume.run(app, json("""{"stream":"loud","level":5}""")) }
        fails(ErrorCode.BAD_REQUEST) { Volume.run(app, json("""{"level":101}""")) }
        fails(ErrorCode.BAD_REQUEST) { Volume.run(app, json("""{"level":5,"step":"up"}""")) }
        fails(ErrorCode.BAD_REQUEST) { Volume.run(app, json("""{"step":"sideways"}""")) }
        fails(ErrorCode.BAD_REQUEST) { Volume.run(app, JsonObject(emptyMap())) }
        assertEquals("Volume (media): a step up", Volume.describe(json("""{"step":"up"}""")))
    }

    @Test fun theRingerModeIsItsVariant() {
        assertEquals("silent", Ringer.variant(json("""{"mode":"silent"}""")))
        Ringer.run(app, json("""{"mode":"vibrate"}"""))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        fails(ErrorCode.BAD_REQUEST) { Ringer.variant(json("""{"mode":"loud"}""")) }
        assertEquals("Ringer: silent", Ringer.describe(json("""{"mode":"silent"}""")))
    }

    @Test fun doNotDisturbNeedsItsAccess() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        assertFalse(DoNotDisturb.available(app))
        fails(ErrorCode.PERMISSION_DENIED) { DoNotDisturb.run(app, json("""{"on":true}""")) }
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        DoNotDisturb.run(app, json("""{"on":true}"""))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notifications.currentInterruptionFilter)
        DoNotDisturb.run(app, json("""{"on":false}"""))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
        assertEquals("Do Not Disturb: on", DoNotDisturb.describe(json("""{"on":true}""")))
    }

    @Test fun commandsAreFoundByNameAndLabelled() {
        assertEquals(listOf("waggle.torch.set", "waggle.volume.set", "waggle.ringer.set", "waggle.dnd.set"), Commands.all.map { it.name })
        assertEquals("Flashlight", Commands.label("waggle.torch.set"))
        assertEquals(null, Commands.label("android.intent.action.VIEW"))
    }
}
