package com.mulesipstea.wiggins.turns

/** Readable names for OVOS's pipeline stages and skills, with the raw ids kept beside them. */
object Stages {
    /** `ovos-padatious-pipeline-plugin` → "Intent (Padatious)"; an unknown stage stays as it is. */
    fun stageName(pipelineId: String): String {
        val id = pipelineId.lowercase()
        return when {
            "persona" in id -> "Persona"
            "padatious" in id -> "Intent (Padatious)"
            "adapt" in id -> "Intent (Adapt)"
            "m2v" in id -> "Intent (Model2Vec)"
            "waggle" in id -> "Waggle"
            "ocp" in id -> "Media (OCP)"
            "common" in id && ("qa" in id || "query" in id) -> "Common query"
            "fallback" in id -> "Fallback"
            "converse" in id -> "Converse"
            "stop" in id -> "Stop"
            else -> pipelineId
        }
    }

    /** `ovos-skill-date-time.openvoiceos` → "date time"; anything else stays as it is. */
    fun skillName(skillId: String): String {
        val base = skillId.substringBefore('.')
        val name = base.removePrefix("ovos-skill-").removePrefix("skill-")
        return if (name == base && !base.startsWith("ovos-")) skillId else name.replace('-', ' ')
    }

    /** Whether the persona answered: its stage, or its skill id. */
    fun isPersona(match: TurnDetails.Match?): Boolean =
        match?.pipelineId?.contains("persona") == true || match?.skillId?.startsWith("persona.") == true
}
