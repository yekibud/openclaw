package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearVoiceSettingsTest {
  @Test
  fun voiceMarkerIsAppliedAndRemoved() {
    val reply = parseReplyVoice("Done, I'll read replies slowly now. [voice: speed=slow; pauses=long]")
    assertEquals("Done, I'll read replies slowly now.", reply.text)
    assertEquals(WearSpeechSpeed.Slow, reply.speed)
    assertEquals(WearSpeechPauses.LongPause, reply.pauses)
  }

  @Test
  fun partialOrUnknownValuesChangeOnlyWhatIsValid() {
    val reply = parseReplyVoice("Okay. [voice: speed=warp; pauses=none]")
    assertNull(reply.speed)
    assertEquals(WearSpeechPauses.NoPause, reply.pauses)
    assertEquals("Okay.", reply.text)
  }

  @Test
  fun repliesWithoutMarkerAreUnchanged() {
    val reply = parseReplyVoice("You have three events today.")
    assertEquals("You have three events today.", reply.text)
    assertNull(reply.speed)
    assertNull(reply.pauses)
  }

  @Test
  fun sentencesSplitOnEndPunctuationOnly() {
    assertEquals(listOf("One.", "Two!", "Is it three?"), splitSentences("One. Two! Is it three?"))
    assertEquals(listOf("No punctuation here"), splitSentences("No punctuation here"))
    assertEquals(listOf("Call Dr. Smith.", "Then lunch."), splitSentences("Call Dr. Smith. Then lunch."))
    assertEquals(listOf("Ask J. Guinan, e.g. about Tailscale.", "Done."), splitSentences("Ask J. Guinan, e.g. about Tailscale. Done."))
  }
}
