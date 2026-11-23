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
      "That will be all.",
      "Thanks, that will be all",
      "That is all.",
      "OK, that is all for now",
      "That is it",
      "Stop, thank you so much",
      "Okay, we're done for now, thanks",
      "that would be all",
      "That should be all, thanks",
      "That'd be all.",
      "that will be it",
      "That'll do",
      "Nothing else, thanks",
      "OK, that's everything",
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
      "That will be all the groceries for this week?",
      "That is all I need from the store?",
      "Hold on, stop, stop, no stop. Um, can, until I tell you not to, um, can you, um, speak more slowly with longer pauses in",
      "Stop, wait, I want to add something else to the list first",
      "Please don't stop",
      "No stop, keep going",
      "That would be all the eggs we need?",
      "Is there nothing else on my list today?",
      "That'll do for the groceries, now add a reminder to call Mom",
    ).forEach { org.junit.Assert.assertFalse(it, isTalkStopPhrase(it)) }
  }
}
