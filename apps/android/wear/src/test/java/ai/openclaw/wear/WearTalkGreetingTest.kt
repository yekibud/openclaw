package ai.openclaw.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WearTalkGreetingTest {
  private fun wav(
    rate: Int,
    channels: Int,
    frames: Int,
  ): ByteArray {
    val data = frames * channels * 2
    return ByteBuffer
      .allocate(44 + data)
      .order(ByteOrder.LITTLE_ENDIAN)
      .apply {
        put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        put("fmt ".toByteArray())
          .putInt(16)
          .putShort(1)
          .putShort(channels.toShort())
          .putInt(rate)
        putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        put("data".toByteArray()).putInt(data)
        repeat(frames * channels) { putShort(1000) }
      }.array()
  }

  @Test
  fun resamplesToTheRealtimeRate() {
    val pcm = WearTalkGreeting.wavToRealtimePcm(wav(rate = 16_000, channels = 1, frames = 16_000))!!
    assertEquals(24_000 * 2, pcm.size)
    assertEquals(1_000L, WearTalkGreeting.durationMillis(pcm))
  }

  @Test
  fun keepsTheFirstChannelOfStereo() {
    val pcm = WearTalkGreeting.wavToRealtimePcm(wav(rate = 24_000, channels = 2, frames = 2_400))!!
    assertEquals(2_400 * 2, pcm.size)
  }

  @Test
  fun rejectsNonWavInput() {
    assertNull(WearTalkGreeting.wavToRealtimePcm(ByteArray(100)))
  }
}
