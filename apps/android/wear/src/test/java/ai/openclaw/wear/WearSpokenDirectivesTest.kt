package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WearSpokenDirectivesTest {
  @Test
  fun thinkHardPrefixBecomesInlineHighThinking() {
    assertEquals("/think high why is my build slow?", applySpokenDirectives("Think hard, why is my build slow?"))
    assertEquals("/think high plan my week", applySpokenDirectives("please think harder: plan my week"))
    assertEquals("/think high compare these", applySpokenDirectives("  THINK HARD compare these"))
  }

  @Test
  fun otherMessagesAreUnchanged() {
    assertEquals("I think hard drives fail", applySpokenDirectives("I think hard drives fail"))
    assertEquals("Thinking hard about lunch", applySpokenDirectives("Thinking hard about lunch"))
    assertEquals("What's on my calendar", applySpokenDirectives("What's on my calendar"))
  }

  @Test
  fun bareThinkHardStaysTextSoItCannotPersistASessionSetting() {
    assertEquals("Think hard.", applySpokenDirectives("Think hard."))
  }
}

class WearTalkStopPhraseTest {
  @Test
  fun stopRequestsEndTalkAloneOrAtTheEndOfASentence() {
    listOf(
      "Stop",
      "stop talking.",
      "End talk",
      "Goodbye!",
      "OK, that's all",
      "thanks, bye",
      "I'm done, thank you",
      "We’re done",
      "Yeah, yeah, yeah. Okay, you can stop now I meant we're done",
      "No, I mean, stop talking to me",
      "Let's end the conversation",
      "you can hang up now",
      "No, stop again",
      "No, I mean, turn off, I don't want to talk",
      "please turn it off",
      "I don't want to talk anymore.",
      "Okay, that's enough",
      "Stop conversation",
      "please stop the conversation",
      "Go away.",
      "OK, go away",
    ).forEach { org.junit.Assert.assertTrue(it, isTalkStopPhrase(it)) }
  }

  @Test
  fun requestsThatMentionStoppingDoNot() {
    listOf(
      "Stop the timer",
      "Navigate to my next event",
      "When does the bus stop running?",
      "that's all of my tasks?",
      "I'm done with the report, what's next on my list?",
      "Tell me when the rain will stop tomorrow morning and then my calendar",
      "Turn off the porch light reminder",
      "Remind me to shut down the server tonight",
      "I don't want to talk to Bob today, move that meeting",
      "Remind me to stop by the conversation pit tomorrow",
      "Should I go away for the weekend?",
    ).forEach { org.junit.Assert.assertFalse(it, isTalkStopPhrase(it)) }
  }
}
