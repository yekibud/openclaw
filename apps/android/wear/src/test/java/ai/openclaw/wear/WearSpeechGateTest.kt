package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WearSpeechGateTest {
  private fun frame(level: Int): ByteArray =
    ByteBuffer
      .allocate(200)
      .order(ByteOrder.LITTLE_ENDIAN)
      .apply { repeat(100) { putShort((if (it % 2 == 0) level else -level).toShort()) } }
      .array()

  @Test
  fun silenceIsHeldBackUntilSpeechThenReleasedWithPreRollAndHangover() {
    val gate = WearSpeechGate(threshold = 400.0, preRoll = 2, hangover = 3)
    assertEquals(0, gate.offer(frame(100)).size)
    assertEquals(0, gate.offer(frame(100)).size)
    assertEquals(0, gate.offer(frame(100)).size)
    // Speech: the two buffered quiet frames before it go out with it.
    assertEquals(3, gate.offer(frame(2000)).size)
    // Hangover: three quiet frames still pass, so the provider hears the pause that ends the turn.
    repeat(3) { assertEquals(1, gate.offer(frame(100)).size) }
    assertEquals(0, gate.offer(frame(100)).size)
  }

  @Test
  fun continuousSpeechPassesFrameByFrame() {
    val gate = WearSpeechGate(threshold = 400.0, preRoll = 4, hangover = 15)
    repeat(10) { assertEquals(1, gate.offer(frame(3000)).size) }
  }
}
