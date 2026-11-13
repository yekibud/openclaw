package ai.openclaw.wear

import ai.openclaw.wear.shared.WearProtocol
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * The Talk start greeting as PCM16 mono at the realtime rate, synthesized once and cached. Talk plays it through its
 * own voice-call output: system TTS on the media stream is held back while Talk owns the audio, and arrives late.
 */
internal object WearTalkGreeting {
  private const val SYNTH_TIMEOUT_MS = 20_000L

  suspend fun pcm(
    context: Context,
    text: String,
    voiceName: String?,
  ): ByteArray? =
    withContext(Dispatchers.IO) {
      val phrase = text.trim().takeIf(String::isNotEmpty) ?: return@withContext null
      val key = sha256("$phrase|${voiceName.orEmpty()}").take(16)
      val cached = File(context.cacheDir, "talk-greeting-$key.pcm")
      if (cached.length() > 0) return@withContext cached.readBytes()
      val wav = File(context.cacheDir, "talk-greeting-$key.wav")
      val synthesized = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { synthesize(context, phrase, voiceName, wav) } == true
      val pcm = if (synthesized) wavToRealtimePcm(wav.readBytes()) else null
      wav.delete()
      pcm?.also { cached.writeBytes(it) }
    }

  fun durationMillis(pcm: ByteArray): Long = pcm.size * 1_000L / (WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ * 2)

  private suspend fun synthesize(
    context: Context,
    text: String,
    voiceName: String?,
    target: File,
  ): Boolean {
    var engine: TextToSpeech? = null
    try {
      val ready =
        suspendCancellableCoroutine { continuation ->
          engine =
            TextToSpeech(context.applicationContext) { status ->
              if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS)
            }
        }
      val tts = engine ?: return false
      if (!ready) return false
      voiceName?.let { name -> runCatching { tts.voices.orEmpty().firstOrNull { it.name == name } }.getOrNull()?.let(tts::setVoice) }
      return suspendCancellableCoroutine { continuation ->
        tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
              if (continuation.isActive) continuation.resume(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              if (continuation.isActive) continuation.resume(false)
            }
          },
        )
        if (tts.synthesizeToFile(text, Bundle(), target, "greeting") != TextToSpeech.SUCCESS && continuation.isActive) {
          continuation.resume(false)
        }
      }
    } finally {
      engine?.shutdown()
    }
  }

  /** PCM16 WAV (any rate, mono or stereo) to mono PCM16 at the realtime rate, by linear interpolation. */
  internal fun wavToRealtimePcm(wav: ByteArray): ByteArray? {
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (wav.size < 44 || String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") return null
    var offset = 12
    var channels = 0
    var rate = 0
    var bits = 0
    var dataStart = -1
    var dataSize = 0
    while (offset + 8 <= wav.size) {
      val id = String(wav, offset, 4)
      val size = buffer.getInt(offset + 4)
      val body = offset + 8
      when (id) {
        "fmt " -> {
          channels = buffer.getShort(body + 2).toInt()
          rate = buffer.getInt(body + 4)
          bits = buffer.getShort(body + 14).toInt()
        }

        "data" -> {
          dataStart = body
          dataSize = minOf(size, wav.size - body)
        }
      }
      if (dataStart >= 0 && rate > 0) break
      offset = body + size + (size and 1)
    }
    if (dataStart < 0 || bits != 16 || channels !in 1..2 || rate <= 0) return null
    val frames = dataSize / (2 * channels)
    val mono = ShortArray(frames) { i -> buffer.getShort(dataStart + i * 2 * channels) }
    val target = WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ
    val outFrames = (frames.toLong() * target / rate).toInt()
    val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until outFrames) {
      val position = i.toDouble() * rate / target
      val index = position.toInt().coerceAtMost(frames - 1)
      val next = (index + 1).coerceAtMost(frames - 1)
      val fraction = position - index
      out.putShort((mono[index] + (mono[next] - mono[index]) * fraction).toInt().toShort())
    }
    return out.array()
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
