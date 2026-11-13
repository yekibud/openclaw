package ai.openclaw.wear

import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/** Debug-build Talk telemetry: one log line per second with mic frames sent, their level, and reply audio received. */
internal class WearTalkDiagnostics(
  private val enabled: Boolean,
) {
  private var windowStart = SystemClock.elapsedRealtime()
  private var sentFrames = 0
  private var sentPeakRms = 0.0
  private var receivedBytes = 0L
  private val events = mutableMapOf<String, Int>()

  @Synchronized
  fun sent(frame: ByteArray) {
    if (!enabled) return
    sentFrames += 1
    sentPeakRms = maxOf(sentPeakRms, pcm16Rms(frame))
    flushIfDue()
  }

  fun note(message: String) {
    if (enabled) Log.i(TAG, message)
  }

  @Synchronized
  fun received(bytes: ByteArray) {
    if (!enabled) return
    receivedBytes += bytes.size
    flushIfDue()
  }

  @Synchronized
  fun event(
    type: String?,
    transcript: String?,
  ) {
    if (!enabled) return
    events.merge(type ?: "?", 1, Int::plus)
    if (transcript != null) Log.i(TAG, "transcript: ${transcript.take(80)}")
    flushIfDue()
  }

  private fun flushIfDue() {
    val now = SystemClock.elapsedRealtime()
    if (now - windowStart < 1_000) return
    Log.i(TAG, "sent=$sentFrames peakRms=${sentPeakRms.toInt()} receivedBytes=$receivedBytes events=$events")
    windowStart = now
    sentFrames = 0
    sentPeakRms = 0.0
    receivedBytes = 0
    events.clear()
  }

  private companion object {
    const val TAG = "WearTalkDiag"
  }
}

/** Root-mean-square level of little-endian 16-bit PCM (0 = silence, ~32767 = full scale). */
internal fun pcm16Rms(pcm16: ByteArray): Double {
  var sum = 0.0
  var count = 0
  var i = 0
  while (i + 1 < pcm16.size) {
    val sample = (pcm16[i].toInt() and 0xff) or (pcm16[i + 1].toInt() shl 8)
    sum += sample.toDouble() * sample
    count += 1
    i += 2
  }
  return if (count == 0) 0.0 else sqrt(sum / count)
}
