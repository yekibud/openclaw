package ai.openclaw.wear

// Claw ends a reply with "[voice: speed=slow; pauses=long]" after the user changes how the watch reads replies by voice
// ("speak slower", "five-second pauses"); the watch applies and keeps it, and never reads the marker aloud.
private val VOICE_MARKER = Regex("""\s*\[voice:\s*([^\]\n]{1,120})]\s*""", RegexOption.IGNORE_CASE)
private val VOICE_FIELD = Regex("""(speed|pauses)\s*=\s*([a-z]+)""", RegexOption.IGNORE_CASE)

internal data class WearReplyVoice(
  val text: String,
  val speed: WearSpeechSpeed? = null,
  val pauses: WearSpeechPauses? = null,
)

internal fun parseReplyVoice(reply: String): WearReplyVoice {
  var speed: WearSpeechSpeed? = null
  var pauses: WearSpeechPauses? = null
  VOICE_MARKER.findAll(reply).forEach { marker ->
    VOICE_FIELD.findAll(marker.groupValues[1]).forEach { field ->
      when (field.groupValues[1].lowercase()) {
        "speed" -> WearSpeechSpeed.fromRawValue(field.groupValues[2])?.let { speed = it }
        "pauses" -> WearSpeechPauses.fromRawValue(field.groupValues[2])?.let { pauses = it }
      }
    }
  }
  return WearReplyVoice(reply.replace(VOICE_MARKER, " ").trim(), speed, pauses)
}
