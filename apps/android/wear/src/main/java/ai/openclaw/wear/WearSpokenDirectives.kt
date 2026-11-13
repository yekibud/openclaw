package ai.openclaw.wear

private val THINK_HARD_PREFIX = Regex("""^\s*(?:please\s*,?\s*)?think\s+hard(?:er)?\b[\s,.:;!-]*""", RegexOption.IGNORE_CASE)

/**
 * Turns spoken shortcuts into Gateway directives that are easy to say but awkward to dictate.
 * "Think hard, <question>" becomes an inline `/think high` hint, which applies to that message only.
 * A bare "think hard" is left as text: a directive-only message would persist the level for the session.
 */
internal fun applySpokenDirectives(message: String): String {
  val match = THINK_HARD_PREFIX.find(message) ?: return message
  val rest = message.substring(match.range.last + 1).trim()
  return if (rest.isEmpty()) message else "/think high $rest"
}

// Each pattern must end its clause (punctuation or end of utterance), so "stop the timer" or "I'm done with the report"
// never ends Talk, while lead-ins such as "Okay, you can stop now" or "No, I mean, stop talking to me" do.
private const val CLAUSE_END = """\s*(?:$|[.!?,;—-])"""

private val TALK_STOP_PATTERNS =
  listOf(
    """\bstop(?: (?:now|again|it|that|there|please|already|talking(?: to me)?|listening))*$CLAUSE_END""",
    """\b(?:turn|switch|shut) (?:it |this |talk |yourself )?(?:off|down)$CLAUSE_END""",
    """\b(?:i )?(?:don't|do not) want to talk(?: anymore| now| to you| any more)?$CLAUSE_END""",
    """\b(?:be quiet|shut up|that's enough|enough for now|leave me alone)$CLAUSE_END""",
    """\b(?:we're|we are|i'm|i am) (?:all )?(?:done|finished)(?: now| here| talking| for now)?$CLAUSE_END""",
    """\b(?:end|exit|close|quit|finish) (?:the |this |our )?(?:talk|conversation|chat|session|call)\b""",
    """\bhang up\b""",
    """\b(?:good ?bye|bye(?: bye)?|good night|that's (?:all|it)(?: for now)?)\W*$""",
  ).map { Regex(it, RegexOption.IGNORE_CASE) }

/**
 * True when a final user transcript asks to end Talk, alone or at the end of a longer sentence ("Okay, you can stop now, I
 * meant we're done"); a stop word that starts a request ("stop the timer") does not count.
 */
internal fun isTalkStopPhrase(utterance: String): Boolean {
  val text = utterance.trim().replace('’', '\'').replace('‘', '\'')
  return TALK_STOP_PATTERNS.any { it.containsMatchIn(text) }
}
