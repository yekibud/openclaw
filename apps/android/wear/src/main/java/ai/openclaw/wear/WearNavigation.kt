package ai.openclaw.wear

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** A reply may end with `[navigate: <destination>]`; the app opens turn-by-turn directions to it. */
private val NAVIGATE_MARKER = Regex("""\s*\[navigate:\s*([^\]\n]{1,200})]\s*""", RegexOption.IGNORE_CASE)

internal data class WearReplyNavigation(
  val text: String,
  val destination: String?,
)

/** Splits a reply into the text to show/speak and an optional navigation destination (the last marker wins). */
internal fun parseReplyNavigation(reply: String): WearReplyNavigation {
  val matches = NAVIGATE_MARKER.findAll(reply).toList()
  if (matches.isEmpty()) return WearReplyNavigation(reply, null)
  val destination =
    matches
      .last()
      .groupValues[1]
      .trim()
      .takeIf { it.isNotEmpty() }
  return WearReplyNavigation(reply.replace(NAVIGATE_MARKER, " ").trim(), destination)
}

/** Opens Google Maps navigation on the watch, falling back to a map search when navigation intents are unsupported. */
internal fun launchNavigation(
  context: Context,
  destination: String,
): Boolean {
  val encoded = Uri.encode(destination)
  val candidates = listOf("google.navigation:q=$encoded", "geo:0,0?q=$encoded")
  for (uri in candidates) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return true
    } catch (_: ActivityNotFoundException) {
      continue
    }
  }
  return false
}
