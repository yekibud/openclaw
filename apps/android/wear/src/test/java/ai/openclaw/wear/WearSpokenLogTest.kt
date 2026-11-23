package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WearSpokenLogTest {
  @get:Rule val folder = TemporaryFolder()

  private fun log(): Pair<WearSpokenLog, File> {
    val file = File(folder.root, WearSpokenLog.FILE_NAME)
    return WearSpokenLog(file) to file
  }

  private fun awaitLines(
    file: File,
    count: Int,
  ): List<String> {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      if (file.exists() && file.readLines().size >= count) return file.readLines()
      Thread.sleep(10)
    }
    error("spoken log did not reach $count lines")
  }

  @Test
  fun everySpokenLineIsWrittenWithItsSource() {
    val (spoken, file) = log()
    spoken.reply("Next, an all-day event: laundry.")
    spoken.record(WearSpokenLog.Source.NOTICE, "I couldn't reach Claw. Please try again in a moment.")
    val lines = awaitLines(file, 2)
    assertTrue(lines[0].contains("\"source\":\"REPLY\"") && lines[0].contains("laundry"))
    assertTrue(lines[1].contains("\"source\":\"NOTICE\""))
  }

  @Test
  fun onlyLinesMissingFromTheTranscriptBecomeANoteOnce() {
    val (spoken, _) = log()
    spoken.reply("A saved reply.")
    spoken.record(WearSpokenLog.Source.GREETING, "Hi")
    assertEquals("", spoken.takeNoteForNextMessage())

    spoken.markUnsaved("Session history is busy; retry shortly")
    spoken.reply("Session history is busy; retry shortly")
    spoken.record(WearSpokenLog.Source.NOTICE, "I couldn't reach [Claw]")
    assertEquals(
      "[watch said: «Session history is busy; retry shortly»; «I couldn't reach  Claw »] ",
      spoken.takeNoteForNextMessage(),
    )
    assertEquals("", spoken.takeNoteForNextMessage())
  }

  @Test
  fun theNoteIsHiddenFromTheUsersBubble() {
    assertEquals("move that to today", WearSpokenLog.withoutNote("[watch said: «I couldn't reach Claw»] move that to today"))
    assertEquals("keep [watch said: x] here", WearSpokenLog.withoutNote("keep [watch said: x] here"))
  }
}
