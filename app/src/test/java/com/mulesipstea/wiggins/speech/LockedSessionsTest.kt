package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.settings.OtherHeadsets
import com.mulesipstea.wiggins.settings.PhoneContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a locked invocation may run as (SPEC "Permissions"); anything unsure asks for the unlock. */
class LockedSessionsTest {
    private val sessions = LockedSessions()
    private val car = "F0:F1:F2:F3:F4:F5"
    private val buds = "A0:A1:A2:A3:A4:A5"
    private val other = "00:11:22:33:44:55"
    private val trusted = setOf(car, buds)

    private fun decide(
        connected: List<String>,
        voiceButton: Boolean = true,
        perDrive: Boolean = false,
        others: OtherHeadsets = OtherHeadsets.ASK_UNLOCK,
    ) = sessions.contextFor(voiceButton, connected, trusted, perDrive, others)

    @Test fun aTrustedDevicesButtonRunsAsTrusted() {
        assertEquals(PhoneContext.TRUSTED_DEVICE, decide(listOf(car)))
        assertEquals(PhoneContext.TRUSTED_DEVICE, decide(listOf(buds)))
    }

    @Test fun theGestureNeverRunsLocked() {
        assertNull(decide(listOf(car), voiceButton = false))
        assertNull(decide(listOf(other), voiceButton = false, others = OtherHeadsets.TALK_ONLY))
    }

    @Test fun nothingConnectedAsksForTheUnlock() {
        assertNull(decide(emptyList(), others = OtherHeadsets.TALK_ONLY))
    }

    @Test fun twoTrustedDevicesAreStillTrusted() {
        assertEquals(PhoneContext.TRUSTED_DEVICE, decide(listOf(car, buds)))
    }

    @Test fun anyUntrustedDeviceMakesItAnyones() {
        assertNull(decide(listOf(car, other)))
        assertEquals(PhoneContext.LOCKED, decide(listOf(car, other), others = OtherHeadsets.TALK_ONLY))
        assertNull(decide(listOf(other)))
        assertEquals(PhoneContext.LOCKED, decide(listOf(other), others = OtherHeadsets.TALK_ONLY))
    }

    @Test fun unlockOncePerDriveNeedsAnUnlockWhileConnected() {
        assertNull(decide(listOf(car), perDrive = true))
        sessions.onUnlocked(listOf(car))
        assertEquals(PhoneContext.TRUSTED_DEVICE, decide(listOf(car), perDrive = true))
        // Every connected device must have been there for the unlock.
        assertNull(decide(listOf(car, buds), perDrive = true))
        sessions.onDisconnected(car)
        assertNull(decide(listOf(car), perDrive = true))
    }

    @Test fun anUnlockWithTheCarAwayDoesntCount() {
        sessions.onUnlocked(emptyList())
        assertNull(decide(listOf(car), perDrive = true))
    }

    @Test fun anUntrustedDeviceJoiningEndsTheTrust() {
        sessions.start(PhoneContext.TRUSTED_DEVICE, trusted)
        sessions.onConnectedLocked(buds)
        assertEquals(PhoneContext.TRUSTED_DEVICE, sessions.current)
        sessions.onConnectedLocked(other)
        assertEquals(PhoneContext.LOCKED, sessions.current)
        sessions.end()
        assertNull(sessions.current)
    }
}
