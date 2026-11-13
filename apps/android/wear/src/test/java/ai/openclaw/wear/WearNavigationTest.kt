package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearNavigationTest {
  @Test
  fun markerIsRemovedAndDestinationReturned() {
    val parsed = parseReplyNavigation("Your next event is at 1:30. Starting directions. [navigate: 500 17th Ave, Seattle]")
    assertEquals("Your next event is at 1:30. Starting directions.", parsed.text)
    assertEquals("500 17th Ave, Seattle", parsed.destination)
  }

  @Test
  fun lastMarkerWinsAndAllAreStripped() {
    val parsed = parseReplyNavigation("[navigate: old place] Going now. [Navigate:  New Place ]")
    assertEquals("Going now.", parsed.text)
    assertEquals("New Place", parsed.destination)
  }

  @Test
  fun repliesWithoutMarkerAreUntouched() {
    val parsed = parseReplyNavigation("You have nothing on your calendar [today].")
    assertEquals("You have nothing on your calendar [today].", parsed.text)
    assertNull(parsed.destination)
  }
}
