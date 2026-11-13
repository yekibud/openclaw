package ai.openclaw.wear

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

internal class WearReplySpeaker(
  context: Context,
  voiceName: String? = null,
) {
  // TTS can wait for its initialization lock or Binder. Keep its entire lifecycle
  // and callbacks on one queue; UI callers never acquire a lock held by the engine.
  private val worker = HandlerThread("WearReplySpeaker").apply { start() }
  private val handler = Handler(worker.looper)
  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
  private val _failed = MutableStateFlow(false)
  val failed: StateFlow<Boolean> = _failed.asStateFlow()

  /** Installed English voices (Indian English first), by engine voice name. Empty until the engine is ready. */
  private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
  val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()
  private var preferredVoice: String? = voiceName
  private val audioFocus = WearAudioFocusController(context, ::stop)

  private var engine: TextToSpeech? = null
  private var ready = false
  private var initializationFailed = false
  private var closed = false
  private var pendingText: String? = null
  private var activeUtterance: String? = null

  init {
    handler.post {
      engine =
        TextToSpeech(context.applicationContext) { status ->
          handler.post {
            if (!closed) {
              ready = status == TextToSpeech.SUCCESS
              initializationFailed = !ready
              if (ready) {
                configureEngine()
              } else if (pendingText != null) {
                fail()
              }
            }
          }
        }.also { created ->
          created.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String) {
                handler.post {
                  if (!closed && activeUtterance == utteranceId) _isSpeaking.value = true
                }
              }

              override fun onDone(utteranceId: String) = finish(utteranceId, failed = false)

              @Suppress("OVERRIDE_DEPRECATION")
              override fun onError(utteranceId: String) = finish(utteranceId, failed = true)

              override fun onError(
                utteranceId: String,
                errorCode: Int,
              ) = finish(utteranceId, failed = true)

              override fun onStop(
                utteranceId: String,
                interrupted: Boolean,
              ) = finish(utteranceId, failed = false)
            },
          )
        }
    }
  }

  private fun configureEngine() {
    val currentEngine = engine ?: return
    currentEngine.language = Locale.getDefault()
    currentEngine.setAudioAttributes(wearSpeechAudioAttributes)
    val all = runCatching { currentEngine.voices.orEmpty() }.getOrDefault(emptySet()).filter { it.locale.language == "en" }
    // Network voices are fine on a watch with LTE; voices whose data is not installed cannot be selected here.
    // Google TTS flags whole language packs as not installed, but its network voices still synthesize without the
    // pack (the engine accepted them), so keep network voices; drop only local voices whose data is missing.
    val voices =
      all
        .filter { it.isNetworkConnectionRequired || !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareBy({ it.locale.country != "IN" }, { it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))
    _availableVoices.value = voices.map { it.name }
    applyPreferredVoice()
    pendingText?.let(::speakReady)
  }

  private fun applyPreferredVoice() {
    val currentEngine = engine ?: return
    val wanted = preferredVoice ?: return
    val voice = runCatching { currentEngine.voices.orEmpty() }.getOrDefault(emptySet()).firstOrNull { it.name == wanted } ?: return
    currentEngine.setVoice(voice)
  }

  /** Switch the reading voice by engine voice name (null = engine default); takes effect on the next utterance. */
  fun setVoice(name: String?) {
    handler.post {
      if (closed) return@post
      preferredVoice = name
      if (name == null) engine?.language = Locale.getDefault() else applyPreferredVoice()
    }
  }

  fun speak(text: String) {
    val normalized = text.trim().takeIf(String::isNotEmpty) ?: return
    handler.post {
      if (closed) return@post
      speakReady(normalized)
    }
  }

  private fun speakReady(text: String) {
    stopSpeaking()
    if (initializationFailed) {
      fail()
      return
    }
    if (!ready) {
      pendingText = text
      return
    }
    if (!audioFocus.request()) {
      fail()
      return
    }
    val utterance = UUID.randomUUID().toString()
    activeUtterance = utterance
    val result = engine?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance)
    if (result != TextToSpeech.SUCCESS) finish(utterance, failed = true)
  }

  private fun finish(
    utteranceId: String,
    failed: Boolean,
  ) {
    handler.post {
      // A flushed/stopped request cannot clear a replacement request's focus or error.
      if (closed || activeUtterance != utteranceId) return@post
      activeUtterance = null
      _isSpeaking.value = false
      _failed.value = failed
      audioFocus.abandon()
    }
  }

  private fun fail() {
    pendingText = null
    activeUtterance = null
    _isSpeaking.value = false
    _failed.value = true
    audioFocus.abandon()
  }

  fun stop() {
    handler.post {
      if (!closed) stopSpeaking()
    }
  }

  private fun stopSpeaking() {
    pendingText = null
    activeUtterance = null
    _isSpeaking.value = false
    _failed.value = false
    audioFocus.abandon()
    engine?.stop()
  }

  fun shutdown() {
    handler.post {
      if (closed) return@post
      closed = true
      try {
        stopSpeaking()
        engine?.shutdown()
        engine = null
        ready = false
      } finally {
        // Late callbacks and calls are harmless: post() rejects them after the queue exits.
        worker.quitSafely()
      }
    }
  }
}
