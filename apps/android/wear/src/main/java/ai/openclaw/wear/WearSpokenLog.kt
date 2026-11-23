package ai.openclaw.wear

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything the watch says aloud, kept in a small file (`adb shell run-as ai.openclaw.wear cat files/spoken-log.jsonl`)
 * and in logcat (tag WearSpoken). Agent replies are also in the Gateway transcript, but the watch's own notices and the
 * texts of failed or aborted runs are not, so those are queued and sent ahead of the user's next message as a
 * "[watch said: «…»]" note: otherwise nobody, Claw included, can tell afterwards what the user heard.
 */
internal class WearSpokenLog(
  private val file: File,
) {
  enum class Source(
    val noteForClaw: Boolean,
  ) {
    /** An agent reply that the transcript has. */
    REPLY(false),

    /** The text of an error or aborted run: shown and spoken, never saved in the transcript. */
    UNSAVED_REPLY(true),

    /** The watch's own notice, such as "I couldn't reach Claw". */
    NOTICE(true),
    GREETING(false),
    PREVIEW(false),
  }

  private val writer = Executors.newSingleThreadExecutor { Thread(it, "WearSpokenLog").apply { isDaemon = true } }
  private val lock = Any()
  private val unsavedTexts = ArrayDeque<String>()
  private val pendingNotes = ArrayDeque<String>()

  /** The Gateway sent [text] with an error or aborted run; speaking it later counts as [Source.UNSAVED_REPLY]. */
  fun markUnsaved(text: String) {
    val key = text.trim().takeIf(String::isNotEmpty) ?: return
    synchronized(lock) {
      unsavedTexts.addLast(key)
      while (unsavedTexts.size > MAX_UNSAVED) unsavedTexts.removeFirst()
    }
  }

  /** Records an assistant reply about to be read aloud. */
  fun reply(
    text: String,
    runId: String? = null,
  ) {
    val unsaved = synchronized(lock) { unsavedTexts.contains(text.trim()) }
    record(if (unsaved) Source.UNSAVED_REPLY else Source.REPLY, text, runId)
  }

  fun record(
    source: Source,
    text: String,
    runId: String? = null,
  ) {
    val spoken = text.trim().takeIf(String::isNotEmpty) ?: return
    Log.i(TAG, "${source.name}${runId?.let { " run=$it" } ?: ""}: $spoken")
    if (source.noteForClaw) {
      synchronized(lock) {
        pendingNotes.addLast(spoken)
        while (pendingNotes.size > MAX_NOTES) pendingNotes.removeFirst()
      }
    }
    val line =
      buildJsonObject {
        put("at", System.currentTimeMillis())
        put("source", source.name)
        runId?.let { put("runId", it) }
        put("text", spoken.take(MAX_LINE_CHARS))
      }.toString()
    writer.execute { append(line) }
  }

  /**
   * The note to put before the next message, or "" when the watch said nothing on its own since the last one. Taking it
   * clears it, so a note goes out once.
   */
  fun takeNoteForNextMessage(): String {
    val notes = synchronized(lock) { pendingNotes.toList().also { pendingNotes.clear() } }
    if (notes.isEmpty()) return ""
    // Brackets would end the note early and «» delimit its lines; the Gateway side strips exactly this prefix.
    val body = notes.joinToString("; ") { "«" + it.replace(Regex("[\\[\\]«»]"), " ").take(MAX_NOTE_LINE_CHARS) + "»" }
    return "[watch said: ${body.take(MAX_NOTE_CHARS)}] "
  }

  private fun append(line: String) {
    try {
      file.parentFile?.mkdirs()
      file.appendText(line + "\n")
      if (file.length() > MAX_FILE_BYTES) {
        val kept = file.readLines().takeLast(KEEP_LINES)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
      }
    } catch (e: Exception) {
      Log.w(TAG, "spoken log write failed: ${e.message}")
    }
  }

  companion object {
    private const val TAG = "WearSpoken"
    private const val MAX_UNSAVED = 20
    private const val MAX_NOTES = 5
    private const val MAX_LINE_CHARS = 2000
    private const val MAX_NOTE_LINE_CHARS = 300
    private const val MAX_NOTE_CHARS = 800
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val KEEP_LINES = 400
    const val FILE_NAME = "spoken-log.jsonl"
    private val NOTE_PREFIX = Regex("^\\s*\\[watch said: [^\\]]*\\]\\s*")

    /** [text] without the "[watch said: …]" note this log put before it. */
    fun withoutNote(text: String): String = text.replace(NOTE_PREFIX, "")
  }
}
