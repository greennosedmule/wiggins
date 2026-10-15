package com.mulesipstea.wiggins.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class YesNoTest {
    @Test fun clearAnswersAreRead() {
        listOf("Yes", "yeah send it", "Sure.", "go ahead", "OK").forEach { assertEquals(it, true, YesNo.parse(it)) }
        listOf("No", "nope", "don't send it", "cancel", "never mind").forEach { assertEquals(it, false, YesNo.parse(it)) }
    }

    @Test fun anythingUnclearIsNoAnswer() {
        listOf("", "hmm", "maybe", "what did you say").forEach { assertEquals(it, null, YesNo.parse(it)) }
        assertEquals(false, YesNo.parse("yes no")) // any no wins
    }
}
