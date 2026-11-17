package ai.openclaw.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class WearVoiceChatState { Off, Listening, Sending, Speaking }

/**
 * Low-power voice chat: the watch's speech recognizer turns each utterance into text, which is sent to Claw like a typed
 * message; the reply is read by the watch voice, then it listens again. No audio stream to the Gateway, and every
 * request reaches the agent with the user's own words (GPT-Live Talk sometimes answered without consulting it).
 * Holding the screen is push-to-talk: listening ends when the hold ends. Runs on the main thread (SpeechRecognizer).
 */
internal class WearVoiceChat(
  private val context: Context,
  private val onUtterance: (String) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private val _state = MutableStateFlow(WearVoiceChatState.Off)
  val state: StateFlow<WearVoiceChatState> = _state.asStateFlow()
  private var recognizer: SpeechRecognizer? = null
  private var lastHeardMillis = 0L
  private var held = false

  val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

  fun start() =
    main.post {
      if (_state.value != WearVoiceChatState.Off) return@post
      lastHeardMillis = SystemClock.elapsedRealtime()
      listen()
    }

  fun stop() =
    main.post {
      held = false
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
      _state.value = WearVoiceChatState.Off
    }

  /** The reply to the last utterance arrived and is being read; listening resumes when [replyFinished] runs. */
  fun replyStarted() = main.post { if (_state.value != WearVoiceChatState.Off) _state.value = WearVoiceChatState.Speaking }

  fun replyFinished() =
    main.post {
      if (_state.value == WearVoiceChatState.Off) return@post
      lastHeardMillis = SystemClock.elapsedRealtime()
      listen()
    }

  /** No reply came (failed send, timeout): listen again rather than leave voice chat stuck. */
  fun replyMissing() = replyFinished()

  fun holdStart() =
    main.post {
      if (_state.value == WearVoiceChatState.Off) return@post
      held = true
      if (_state.value != WearVoiceChatState.Listening) listen()
    }

  fun holdEnd() =
    main.post {
      if (!held) return@post
      held = false
      if (_state.value == WearVoiceChatState.Listening) recognizer?.stopListening() // ends the utterance now
    }

  private fun listen() {
    val current =
      recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
        created.setRecognitionListener(listener)
        recognizer = created
      }
    _state.value = WearVoiceChatState.Listening
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Pauses while thinking out loud should not cut a request off (Talk uses 1 s; people pause longer here).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MS)
      }
    current.startListening(intent)
  }

  private fun idleTooLong(): Boolean = SystemClock.elapsedRealtime() - lastHeardMillis > IDLE_MILLIS

  private val listener =
    object : RecognitionListener {
      override fun onResults(results: Bundle?) {
        val text =
          results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (_state.value == WearVoiceChatState.Off) return
        if (text.isEmpty()) {
          onError(SpeechRecognizer.ERROR_NO_MATCH)
          return
        }
        lastHeardMillis = SystemClock.elapsedRealtime()
        if (isTalkStopPhrase(text)) {
          stop()
          return
        }
        _state.value = WearVoiceChatState.Sending
        onUtterance(text)
      }

      override fun onError(error: Int) {
        if (_state.value != WearVoiceChatState.Listening) return
        // Silence or nothing understood: keep listening until the idle limit, like Talk's idle timeout.
        if (idleTooLong()) stop() else main.postDelayed({ if (_state.value == WearVoiceChatState.Listening) listen() }, RETRY_DELAY_MS)
      }

      override fun onReadyForSpeech(params: Bundle?) = Unit

      override fun onBeginningOfSpeech() = Unit

      override fun onRmsChanged(rmsdB: Float) = Unit

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() = Unit

      override fun onPartialResults(partialResults: Bundle?) = Unit

      override fun onEvent(
        eventType: Int,
        params: Bundle?,
      ) = Unit
    }

  private companion object {
    const val END_OF_SPEECH_SILENCE_MS = 1_800L
    const val IDLE_MILLIS = 30_000L
    const val RETRY_DELAY_MS = 300L
  }
}
