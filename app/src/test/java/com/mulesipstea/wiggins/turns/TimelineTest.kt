package com.mulesipstea.wiggins.turns

import com.mulesipstea.wiggins.turns.Timeline.Component
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The waterfall's rows and the maths that places them (SPEC "Reply details"). */
class TimelineTest {
    @Test fun ticksUseTheSmallestRoundStepThatFits() {
        assertEquals(listOf(0L, 1_000, 2_000, 3_000, 4_000), Timeline.ticks(4_800, 5))
        assertEquals(listOf(0L, 2_000, 4_000, 6_000, 8_000, 10_000), Timeline.ticks(10_900, 5))
        assertEquals(listOf(0L, 100, 200, 300, 400), Timeline.ticks(450, 4))
        assertEquals(listOf(0L, 500), Timeline.ticks(900, 1))
        // Narrow enough for none still has zero.
        assertEquals(0L, Timeline.ticks(900, 0).first())
    }

    @Test fun labelsAndDurationsRead() {
        assertEquals(listOf("0", "500 ms", "1 s", "1.5 s"), listOf(0L, 500, 1_000, 1_500).map(Timeline::tickLabel))
        assertEquals(listOf("340 ms", "1.2 s", "59.9 s", "1:05"), listOf(340L, 1_234, 59_940, 65_000).map(Timeline::duration))
    }

    @Test fun barsArePlacedToScaleButNeverVanish() {
        val row = Timeline.Row("x", Component.HUB, listOf(Timeline.Bar(0, 500), Timeline.Bar(1_000, 1_001, light = true), Timeline.Bar(1_990, 2_500)))
        val placed = Timeline.place(row, 2_000, 200f, 2f)
        assertEquals(Timeline.Placed(0f, 50f, false), placed[0])
        assertEquals(Timeline.Placed(100f, 2f, true), placed[1])
        // Past the axis's end: clipped to it, but still there.
        assertEquals(200f, placed[2].x + placed[2].width, 0.01f)
        assertTrue(placed[2].width >= 2f)
    }

    @Test fun theDurationGoesAfterTheBarThenBeforeThenInside() {
        assertEquals(Timeline.LabelAt(64f, false), Timeline.label(10f, 60f, 30f, 200f, 4f))
        assertEquals(Timeline.LabelAt(116f, false), Timeline.label(150f, 190f, 30f, 200f, 4f))
        assertEquals(Timeline.LabelAt(170f, true), Timeline.label(10f, 190f, 30f, 200f, 4f))
    }

    @Test fun aPersonaTurnInStartOrderWithTheEndLast() {
        val rows = Timeline.rows(SampleTurns.persona)
        assertEquals(
            listOf("Listening", "Transcription", "Matching", "Ada 1", "Speech 1", "GetLiveContext", "set_alarm", "SET_ALARM", "Ada 2", "Speech 2", "Done"),
            rows.map { it.label },
        )
        val first = rows.first { it.label == "Ada 1" }
        // Waiting for the first text is light; the rest is solid, on the hub's clock.
        assertEquals(listOf(Timeline.Bar(2_750, 3_530, light = true), Timeline.Bar(3_530, 4_220)), first.bars)
        assertTrue(first.approximate)
        assertEquals(Component.PERSONA, first.component)
        assertFalse(rows.first { it.label == "Speech 1" }.approximate)
        assertEquals(10_900L, Timeline.totalMs(rows))
    }

    @Test fun missingHubDataSaysSoAndThePhonesBarsStillShow() {
        val rows = Timeline.rows(SampleTurns.personaNoReport)
        assertTrue(rows.none { it.component == Component.PERSONA || it.component == Component.TOOL || it.approximate })
        assertTrue(rows.any { it.label == "Listening" && it.bars.isNotEmpty() })
        val noMatch = Timeline.rows(SampleTurns.skill.copy(match = null, matchedMs = null))
        assertEquals(Timeline.NOT_REPORTED, noMatch.first { it.label == "Matching" }.missing)
        val noEnd = Timeline.rows(SampleTurns.skill.copy(handledMs = null))
        assertEquals(Timeline.NOT_REPORTED, noEnd.last().missing)
    }

    @Test fun anAskCardsWaitIsLight() {
        val ask = TurnDetails.PhoneAction("w", "intent", "android.intent.action.DIAL", 100, 3_200, askStartMs = 200, askEndMs = 3_000, outcome = "ok")
        val row = Timeline.rows(SampleTurns.skill.copy(actions = listOf(ask))).first { it.label == "DIAL" }
        assertEquals(Component.PHONE, row.component)
        assertEquals(listOf(Timeline.Bar(100, 200), Timeline.Bar(200, 3_000, light = true), Timeline.Bar(3_000, 3_200)), row.bars)
        assertTrue(row.detail.contains("waited 2.8 s for your answer"))
    }

    @Test fun aSentenceNotReadAloudIsAMark() {
        val quiet = SampleTurns.skill.copy(sentences = SampleTurns.skill.sentences.map { it.copy(spoken = false, audioMs = null, playStartMs = null, playEndMs = null) })
        val row = Timeline.rows(quiet).first { it.label == "Speech 1" }
        assertEquals(480L, row.marker)
        assertTrue(row.bars.isEmpty())
    }

    @Test fun nothingAssumesWhichModelAnswers() {
        assertEquals("Persona", Timeline.personaName(SampleTurns.skill))
        assertEquals("Ada", Timeline.personaName(SampleTurns.personaNoReport)) // from the match's slot
        assertEquals("Ada", Timeline.personaName(SampleTurns.persona))
    }

    @Test fun theCopyHasEveryRow() {
        val text = Timeline.describe(SampleTurns.persona)
        assertTrue(text.contains("Answered by: persona.openvoiceos"))
        assertTrue(text.contains("Ada · example-model-1 · effort low"))
        assertTrue(text.contains("Ada 1: 2.75 s +1.5 s (hub clock)"))
        assertTrue(text.lines().last().startsWith("Done: at 7.90 s"))
    }
}
