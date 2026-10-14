package com.mulesipstea.wiggins.turns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagesTest {
    @Test fun stagesReadAndUnknownOnesStayRaw() {
        assertEquals("Persona", Stages.stageName("ovos-persona-pipeline-plugin"))
        assertEquals("Intent (Padatious)", Stages.stageName("ovos-padatious-pipeline-plugin"))
        assertEquals("Waggle", Stages.stageName("ovos-waggle-pipeline-plugin"))
        assertEquals("Media (OCP)", Stages.stageName("ovos-ocp-pipeline-plugin"))
        assertEquals("Fallback", Stages.stageName("ovos-fallback-pipeline-plugin"))
        assertEquals("my-own-stage", Stages.stageName("my-own-stage"))
    }

    @Test fun skillsRead() {
        assertEquals("date time", Stages.skillName("ovos-skill-date-time.openvoiceos"))
        assertEquals("weather", Stages.skillName("skill-weather.example"))
        assertEquals("persona.openvoiceos", Stages.skillName("persona.openvoiceos"))
    }

    @Test fun thePersonaIsKnownByItsStageOrSkill() {
        assertTrue(Stages.isPersona(SampleTurns.persona.match))
        assertTrue(Stages.isPersona(TurnDetails.Match(skillId = "persona.openvoiceos")))
        assertFalse(Stages.isPersona(SampleTurns.skill.match))
        assertFalse(Stages.isPersona(null))
    }
}
