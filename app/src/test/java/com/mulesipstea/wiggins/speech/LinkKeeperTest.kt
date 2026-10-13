package com.mulesipstea.wiggins.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the hands-free link closes: after the turn has been quiet for the grace period, not between a reply and its follow-up. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkKeeperTest {
    private val scope = TestScope()
    private var closes = 0
    private val keeper = LinkKeeper(scope, graceMs = 3_000) { closes++ }

    private fun after(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test fun closesOnceTheTurnHasBeenQuietForTheGrace() {
        keeper.opened()
        keeper.busy(true) // listening
        after(10_000)
        keeper.busy(false) // speech done
        after(2_999)
        assertEquals(0, closes)
        after(1)
        assertEquals(1, closes)
        after(10_000)
        assertEquals(1, closes)
    }

    @Test fun aFollowUpWithinTheGraceKeepsIt() {
        keeper.opened()
        keeper.busy(true)
        keeper.busy(false) // the question has been read
        after(500)
        keeper.opened() // listening again for the answer
        keeper.busy(true)
        after(10_000)
        assertEquals(0, closes)
        keeper.busy(false)
        after(3_000)
        assertEquals(1, closes)
    }

    @Test fun openedWhileBusyDoesntScheduleAClose() {
        keeper.busy(true) // e.g. still waiting for the hub
        keeper.opened()
        after(10_000)
        assertEquals(0, closes)
    }

    @Test fun anOpenLinkThatNeverGetsBusyStillCloses() {
        keeper.opened() // listening then failed to start
        after(3_000)
        assertEquals(1, closes)
    }

    @Test fun closeNowClosesOnceAndNothingWhenNotOpen() {
        keeper.closeNow()
        assertEquals(0, closes)
        keeper.opened()
        keeper.closeNow()
        keeper.closeNow()
        after(10_000)
        assertEquals(1, closes)
        keeper.busy(false) // not open: nothing to close
        after(10_000)
        assertEquals(1, closes)
    }
}
