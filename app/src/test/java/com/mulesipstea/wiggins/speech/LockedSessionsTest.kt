package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.settings.WhenLocked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a locked phone may run a voice session over the hands-free link. */
class LockedSessionsTest {
    private val sessions = LockedSessions()
    private val car = "F0:F1:F2:F3:F4:F5"
    private val other = "00:11:22:33:44:55"
    private val trusted = setOf(car)

    @Test fun askUnlockNeverAllows() {
        sessions.onUnlocked(listOf(car))
        assertFalse(sessions.allows(WhenLocked.ASK_UNLOCK, trusted, car))
    }

    @Test fun alwaysAllowsAnyConnectedDeviceButNeedsOne() {
        assertTrue(sessions.allows(WhenLocked.ALWAYS, emptySet(), other))
        assertFalse(sessions.allows(WhenLocked.ALWAYS, emptySet(), null))
    }

    @Test fun aTrustedCarNeedsAnUnlockWhileConnected() {
        assertFalse(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
        sessions.onUnlocked(listOf(car))
        assertTrue(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
    }

    @Test fun anUnlockWithTheCarAwayDoesntCount() {
        sessions.onUnlocked(emptyList())
        assertFalse(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
    }

    @Test fun disconnectingEndsItUntilTheNextUnlock() {
        sessions.onUnlocked(listOf(car))
        sessions.onDisconnected(car)
        assertFalse(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
        sessions.onUnlocked(listOf(car))
        assertTrue(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
    }

    @Test fun onlyTrustedDevicesCount() {
        sessions.onUnlocked(listOf(car, other))
        assertFalse(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, other))
        // Untrusting the car after it was armed takes effect at once.
        assertFalse(sessions.allows(WhenLocked.TRUSTED_CAR, emptySet(), car))
    }

    @Test fun anotherDeviceDisconnectingLeavesTheCarArmed() {
        sessions.onUnlocked(listOf(car, other))
        sessions.onDisconnected(other)
        assertTrue(sessions.allows(WhenLocked.TRUSTED_CAR, trusted, car))
    }
}
