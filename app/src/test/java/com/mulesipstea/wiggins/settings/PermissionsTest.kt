package com.mulesipstea.wiggins.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The locked-phone settings from before 0.7.0 carry over without trusting anything new. */
class PermissionsTest {
    private val ticked = setOf("F0:F1:F2:F3:F4:F5")

    @Test fun aTrustedCarStaysTrustedWithItsUnlockPerDrive() {
        assertEquals(Triple(ticked, true, OtherHeadsets.ASK_UNLOCK), LegacyWhenLocked.migrate("TRUSTED_CAR", ticked))
    }

    @Test fun alwaysBecomesTalkOnlyAndTrustsNothing() {
        assertEquals(Triple(emptySet<String>(), false, OtherHeadsets.TALK_ONLY), LegacyWhenLocked.migrate("ALWAYS", ticked))
    }

    @Test fun askToUnlockDropsDevicesThatWerentInUse() {
        assertEquals(Triple(emptySet<String>(), false, OtherHeadsets.ASK_UNLOCK), LegacyWhenLocked.migrate("ASK_UNLOCK", ticked))
        assertEquals(Triple(emptySet<String>(), false, OtherHeadsets.ASK_UNLOCK), LegacyWhenLocked.migrate(null, ticked))
    }

    @Test fun onlyTheLockedPhoneSeesHistorySinceTheLock() {
        assertEquals(listOf("full", "full", "since_lock"), PhoneContext.entries.map { it.history })
    }
}
