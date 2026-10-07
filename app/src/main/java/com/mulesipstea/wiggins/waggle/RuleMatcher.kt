package com.mulesipstea.wiggins.waggle

/** WAGGLE.md "Rule matching", shared in behaviour with the hub's pre-check (waggle/rules.py). */
object RuleMatcher {
    /** What the phone does with an intent; [rule] is null when no rule matched and `unmatched` applied. */
    data class Decision(val mode: Mode, val rule: Rule?)

    /** A rule matches when every field it sets equals the intent's value; schemes compare case-insensitively. */
    fun matches(rule: Rule, intent: IntentRequest): Boolean =
        rule.action == intent.action &&
            (rule.scheme == null || rule.scheme.equals(intent.scheme, ignoreCase = true)) &&
            (rule.packageName == null || rule.packageName == intent.packageName) &&
            (rule.category == null || rule.category in intent.categories)

    /** The matching rule with the most fields set wins; on a tie the stricter mode wins. */
    fun decide(rules: List<Rule>, unmatched: Mode, intent: IntentRequest): Decision {
        // maxWithOrNull keeps the first of equal rules, as Python's max does.
        val best = rules.filter { matches(it, intent) }
            .maxWithOrNull(compareBy<Rule>({ it.specificity }, { it.mode.ordinal }))
        return if (best == null) Decision(unmatched, null) else Decision(best.mode, best)
    }
}
