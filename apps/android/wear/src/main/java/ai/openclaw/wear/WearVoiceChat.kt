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
import android.util.Log
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
  private var speechActive = false
  private var sendOnResult = false

  // The recognizer ends an utterance at the first short pause ("okay, check my …"), so pieces are collected here and
  // sent only after a quiet follow-up window (or when the hold ends).
  private val heard = StringBuilder()
  private val sendIfQuiet =
    Runnable {
      if (_state.value == WearVoiceChatState.Listening && !speechActive && !held && heard.isNotEmpty()) send()
    }

  // Barge-in: during a silent pause between reply sentences the mic listens; words heard there interrupt the reply.
  private var bargeIn: ((Boolean) -> Unit)? = null
  private var bargeInEndsAt = 0L
  private val bargeInTimeout = Runnable { if (bargeIn != null && !speechActive) endBargeIn(interrupted = false) }

  val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

  /**
   * Listen for [windowMillis] while the reply pauses. [onOutcome] runs once: true when the user spoke (voice chat is
   * then listening and collecting their words, so the reply must end), false when the pause passed in silence.
   */
  fun listenDuringPause(
    windowMillis: Long,
    onOutcome: (Boolean) -> Unit,
  ) = main.post {
    if (_state.value != WearVoiceChatState.Speaking) {
      onOutcome(_state.value != WearVoiceChatState.Off && _state.value != WearVoiceChatState.Sending)
      return@post
    }
    bargeIn = onOutcome
    bargeInEndsAt = SystemClock.elapsedRealtime() + windowMillis
    main.postDelayed(bargeInTimeout, windowMillis)
    startRecognizer()
  }

  /** A tap or the Assist button during the reply: stop reading and listen now. */
  fun interrupt() =
    main.post {
      if (_state.value != WearVoiceChatState.Speaking) return@post
      Log.i(TAG, "interrupted by touch")
      endBargeIn(interrupted = true)
      listen()
    }

  private fun endBargeIn(interrupted: Boolean) {
    val outcome = bargeIn ?: return
    bargeIn = null
    main.removeCallbacks(bargeInTimeout)
    if (!interrupted) recognizer?.cancel()
    outcome(interrupted)
  }

  /** With [greeting], the caller is reading a greeting first and calls [replyFinished] when it ends. */
  fun start(greeting: Boolean) =
    main.post {
      if (_state.value != WearVoiceChatState.Off) return@post
      Log.i(TAG, "start greeting=$greeting")
      lastHeardMillis = SystemClock.elapsedRealtime()
      if (greeting) _state.value = WearVoiceChatState.Speaking else listen()
    }

  fun stop() =
    main.post {
      if (_state.value != WearVoiceChatState.Off) Log.i(TAG, "stop")
      endBargeIn(interrupted = true)
      held = false
      sendOnResult = false
      heard.clear()
      main.removeCallbacks(sendIfQuiet)
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
      _state.value = WearVoiceChatState.Off
    }

  /** The reply to the last utterance arrived and is being read; listening resumes when [replyFinished] runs. */
  fun replyStarted() = main.post { if (_state.value != WearVoiceChatState.Off) _state.value = WearVoiceChatState.Speaking }

  fun replyFinished() =
    main.post {
      // Already listening: a hold interrupted the reply and started listening itself.
      if (_state.value == WearVoiceChatState.Off || _state.value == WearVoiceChatState.Listening) return@post
      lastHeardMillis = SystemClock.elapsedRealtime()
      listen()
    }

  /** No reply came (failed send, timeout): listen again rather than leave voice chat stuck. */
  fun replyMissing() =
    main.post {
      Log.w(TAG, "no reply; listening again")
      replyFinished()
    }

  fun holdStart() =
    main.post {
      if (_state.value == WearVoiceChatState.Off) return@post
      held = true
      sendOnResult = false
      main.removeCallbacks(sendIfQuiet)
      endBargeIn(interrupted = true)
      if (_state.value != WearVoiceChatState.Listening) listen()
    }

  fun holdEnd() =
    main.post {
      if (!held) return@post
      held = false
      if (_state.value != WearVoiceChatState.Listening) return@post
      if (speechActive) {
        sendOnResult = true
        recognizer?.stopListening() // ends the utterance now; onResults sends it
      } else if (heard.isNotEmpty()) {
        send()
      }
    }

  private fun listen() {
    speechActive = false
    _state.value = WearVoiceChatState.Listening
    startRecognizer()
  }

  private fun startRecognizer() {
    val current =
      recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
        created.setRecognitionListener(listener)
        recognizer = created
      }
    current.cancel() // a second startListening on a busy recognizer fails with ERROR_RECOGNIZER_BUSY
    speechActive = false
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MS)
      }
    current.startListening(intent)
  }

  private fun send() {
    main.removeCallbacks(sendIfQuiet)
    sendOnResult = false
    val text = heard.toString().trim()
    heard.clear()
    recognizer?.cancel()
    if (text.isEmpty()) {
      listen()
      return
    }
    if (isTalkStopPhrase(text)) {
      stop()
      return
    }
    Log.i(TAG, "send chars=${text.length}")
    _state.value = WearVoiceChatState.Sending
    onUtterance(text)
  }

  private fun idleTooLong(): Boolean = SystemClock.elapsedRealtime() - lastHeardMillis > IDLE_MILLIS

  private val listener =
    object : RecognitionListener {
      override fun onResults(results: Bundle?) {
        speechActive = false
        val text =
          results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (bargeIn != null) {
          if (text.isEmpty()) {
            onError(SpeechRecognizer.ERROR_NO_MATCH)
            return
          }
          // Words in the pause: the reply ends here and these words start the next request.
          Log.i(TAG, "interrupted by voice")
          _state.value = WearVoiceChatState.Listening
          endBargeIn(interrupted = true)
        }
        if (_state.value != WearVoiceChatState.Listening) return
        if (text.isNotEmpty()) {
          lastHeardMillis = SystemClock.elapsedRealtime()
          if (heard.isNotEmpty()) heard.append(' ')
          heard.append(text)
        }
        when {
          heard.isEmpty() -> {
            onError(SpeechRecognizer.ERROR_NO_MATCH)
          }

          sendOnResult -> {
            send()
          }

          // A stop phrase ends at once; anything else may continue after the pause.
          isTalkStopPhrase(heard.toString()) -> {
            stop()
          }

          held -> {
            listen()
          }

          else -> {
            listen()
            main.postDelayed(sendIfQuiet, FOLLOW_UP_MS)
          }
        }
      }

      override fun onError(error: Int) {
        speechActive = false
        if (bargeIn != null) {
          // Noise or nothing understood: keep the pause open while there is time, otherwise the reply goes on.
          if (bargeInEndsAt - SystemClock.elapsedRealtime() > BARGE_IN_MIN_RESTART_MS) {
            main.postDelayed({ if (bargeIn != null) startRecognizer() }, RETRY_DELAY_MS)
          } else {
            endBargeIn(interrupted = false)
          }
          return
        }
        if (_state.value != WearVoiceChatState.Listening) return
        Log.i(TAG, "recognizer error=$error collected=${heard.isNotEmpty()}")
        when {
          // Silence after what was already said: the utterance is complete.
          heard.isNotEmpty() && !held -> send()

          !held && idleTooLong() -> stop()

          else -> main.postDelayed({ if (_state.value == WearVoiceChatState.Listening) listen() }, RETRY_DELAY_MS)
        }
      }

      override fun onBeginningOfSpeech() {
        speechActive = true
        main.removeCallbacks(sendIfQuiet)
      }

      override fun onReadyForSpeech(params: Bundle?) = Unit

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
    const val TAG = "WearVoiceChat"
    const val END_OF_SPEECH_SILENCE_MS = 1_800L

    /** After a piece of speech, how long to wait for more before sending (covers mid-sentence pauses). */
    const val FOLLOW_UP_MS = 1_500L
    const val IDLE_MILLIS = 30_000L
    const val RETRY_DELAY_MS = 300L
    const val BARGE_IN_MIN_RESTART_MS = 1_200L
  }
}
