package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WearPcmGainTest {
  private fun pcm(vararg samples: Int): ByteArray =
    ByteBuffer
      .allocate(samples.size * 2)
      .order(ByteOrder.LITTLE_ENDIAN)
      .apply { samples.forEach { putShort(it.toShort()) } }
      .array()

  private fun samples(bytes: ByteArray): List<Int> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return List(bytes.size / 2) { buffer.getShort(it * 2).toInt() }
  }

  @Test
  fun amplifiesAndSaturatesInsteadOfWrapping() {
    val frame = pcm(100, -100, 3_000, -3_000, 0)
    amplifyPcm16(frame, 12)
    assertEquals(listOf(1_200, -1_200, 32_767, -32_768, 0), samples(frame))
  }
}
