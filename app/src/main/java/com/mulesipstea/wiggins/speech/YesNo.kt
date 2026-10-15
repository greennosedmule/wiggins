package com.mulesipstea.wiggins.speech

import java.util.Locale

/**
 * A spoken answer to "Send it?" (SPEC "Voice confirmation"): yes, no, or null when it's
 * neither. Any no in it wins ("don't send it"), and unclear counts as no answer: never yes.
 */
object YesNo {
    private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "send", "correct", "affirmative", "please")
    private val YES_PHRASES = listOf("do it", "go ahead", "send it")
    private val NO = setOf("no", "nope", "nah", "cancel", "stop", "don't", "dont", "wait")
    private val NO_PHRASES = listOf("do not", "never mind", "nevermind", "not now")

    fun parse(heard: String): Boolean? {
        val text = heard.lowercase(Locale.ROOT).replace(Regex("[^a-z' ]"), " ").replace(Regex(" +"), " ").trim()
        val words = text.split(' ').toSet()
        val yes = words.any { it in YES } || YES_PHRASES.any { it in text }
        val no = words.any { it in NO } || NO_PHRASES.any { it in text }
        return when {
            no -> false
            yes -> true
            else -> null
        }
    }
}
