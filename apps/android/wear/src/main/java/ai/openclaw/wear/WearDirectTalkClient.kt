package ai.openclaw.wear

import ai.openclaw.app.node.asStringOrNull
import ai.openclaw.app.node.parseJsonParamsObject
import ai.openclaw.app.voice.RealtimeAgentCoordinator
import ai.openclaw.app.voice.RealtimeAgentSession
import ai.openclaw.app.voice.requestPhoneRealtimeSessionWithLanguageFallback
import ai.openclaw.wear.shared.WearProtocol
import ai.openclaw.wear.shared.WearRealtimeTalkEntry
import ai.openclaw.wear.shared.WearRealtimeTalkRole
import ai.openclaw.wear.shared.WearRealtimeTalkSnapshot
import ai.openclaw.wear.shared.WearRealtimeTalkStatus
import ai.openclaw.wear.shared.WearReplyText
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private enum class DirectTalkState(
  val status: WearRealtimeTalkStatus,
  val text: String,
) {
  Connecting(WearRealtimeTalkStatus.CONNECTING, "Connecting…"),
  Listening(WearRealtimeTalkStatus.LISTENING, "Listening"),
  Thinking(WearRealtimeTalkStatus.THINKING, "Agent working"),
  Speaking(WearRealtimeTalkStatus.SPEAKING, "Speaking…"),
}

/**
 * Gateway-relay realtime Talk owned entirely by the watch. The relay session, microphone,
 * speaker, and agent consult never touch the phone; the Gateway keeps tools and transcripts.
 * The relay protocol mirrors the phone's Wear bridge, with the Data Layer replaced by local audio.
 */
internal class WearDirectTalkClient(
  context: Context,
  private val scope: CoroutineScope,
  private val isConnected: () -> Boolean,
  private val requestGateway: suspend (method: String, paramsJson: String?, timeoutMs: Long) -> String,
  private val sendGatewayFrame:
    suspend (
      method: String,
      paramsJson: String?,
      timeoutMs: Long,
      onError: (String) -> Unit,
    ) -> Unit,
  private val onSnapshot: (WearRealtimeTalkSnapshot) -> Unit = {},
  private val onAudioState: (capturing: Boolean, playing: Boolean) -> Unit = { _, _ -> },
) {
  private class Attempt(
    val sessionKey: String,
    val id: String = UUID.randomUUID().toString(),
  )

  private val lifecycleMutex = Mutex()
  private val stateLock = Any()
  private val lifecycleGeneration = AtomicLong()
  private val _snapshot = MutableStateFlow(WearRealtimeTalkSnapshot())
  val snapshot: StateFlow<WearRealtimeTalkSnapshot> = _snapshot
  private val _isCapturing = MutableStateFlow(false)
  val isCapturing: StateFlow<Boolean> = _isCapturing
  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying
  private val _mouthLevel = MutableStateFlow(0f)
  val mouthLevel: StateFlow<Float> = _mouthLevel

  @Volatile private var sessionId: String? = null

  @Volatile private var activeAttempt: Attempt? = null

  private var audioFrames: Channel<ByteArray>? = null
  private var appendJob: Job? = null
  private var outputQueue: Channel<ByteArray?>? = null
  private var outputJob: Job? = null
  private var eventScope: CoroutineScope? = null

  // The relay owns full text; the snapshot keeps a bounded projection for the watch screen.
  private var conversation = emptyList<WearRealtimeTalkEntry>()
  private val streamingEntryIds = mutableMapOf<WearRealtimeTalkRole, String>()
  private val agent =
    RealtimeAgentCoordinator(
      parentScope = scope,
      requestGateway = requestGateway,
      onWorking = { activeSession ->
        synchronized(stateLock) {
          if (sessionId == activeSession.relaySessionId) updateActiveState(DirectTalkState.Thinking)
        }
      },
      onError = { _, message -> Log.w(TAG, message) },
    )

  // Local audio. Capture pauses while the speaker plays and resumes once playback drains.
  // Lock order: stateLock is never taken while audioLock is held.
  private val audioLock = Any()
  private val audioFocus = WearAudioFocusController(context) { scope.launch { stop() } }

  @Volatile private var audioRecord: AudioRecord? = null
  private var captureJob: Job? = null
  private var audioTrack: AudioTrack? = null
  private var playbackEndsAtMillis = 0L
  private var playbackIdleJob: Job? = null
  private var mouthFrames: Channel<Float>? = null
  private var mouthJob: Job? = null
  private val mouthLevelAccumulator = Pcm16MouthLevelAccumulator()

  suspend fun start(sessionKey: String): Boolean =
    lifecycleMutex.withLock {
      val attempt = Attempt(sessionKey)
      val generation =
        synchronized(stateLock) {
          if (!isConnected() || sessionId != null || activeAttempt != null) return@withLock false
          conversation = emptyList()
          streamingEntryIds.clear()
          activeAttempt = attempt
          updateActiveState(DirectTalkState.Connecting)
          lifecycleGeneration.get()
        }

      fun stale(): Boolean = generation != lifecycleGeneration.get() || activeAttempt !== attempt || !isConnected()

      val language =
        Locale
          .getDefault()
          .language
          .lowercase(Locale.ROOT)
          .takeIf { it.length == ISO_639_1_LANGUAGE_LENGTH }
      val payload =
        try {
          requestPhoneRealtimeSessionWithLanguageFallback(language) { requestedLanguage ->
            requestGateway(
              "talk.session.create",
              buildJsonObject {
                put("sessionKey", JsonPrimitive(sessionKey))
                put("mode", JsonPrimitive("realtime"))
                put("transport", JsonPrimitive("gateway-relay"))
                put("brain", JsonPrimitive("agent-consult"))
                if (requestedLanguage != null) put("language", JsonPrimitive(requestedLanguage))
              }.toString(),
              SESSION_CREATE_TIMEOUT_MILLIS,
            )
          }
        } catch (err: Throwable) {
          if (err is CancellationException) throw err
          synchronized(stateLock) { if (!stale()) fail(attempt, err.message ?: "Unable to start Talk") }
          return@withLock false
        }
      val root = parseJsonParamsObject(payload)
      val created = root?.get("relaySessionId").asStringOrNull() ?: root?.get("sessionId").asStringOrNull()
      val activated =
        synchronized(stateLock) {
          when {
            stale() -> {
              if (activeAttempt === attempt) resetLocked()
              false
            }

            created.isNullOrBlank() -> {
              fail(attempt, "Talk returned no session")
              false
            }

            else -> {
              agent.beginSession(RealtimeAgentSession(relaySessionId = created, sessionKey = sessionKey))
              sessionId = created
              eventScope?.cancel()
              eventScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
              startOutputLoop(attempt, created)
              startAppendLoop(attempt, created)
              updateActiveState(DirectTalkState.Listening)
              true
            }
          }
        }
      if (!activated) {
        if (!created.isNullOrBlank()) runCatching { closeGatewaySession(created) }
        return@withLock false
      }
      val captured = synchronized(audioLock) { runCatching { startCaptureLocked(attempt) } }
      if (captured.isFailure) {
        fail(attempt, "Watch microphone is unavailable")
        return@withLock false
      }
      true
    }

  suspend fun stop(): WearRealtimeTalkSnapshot =
    lifecycleMutex.withLock {
      val closing = synchronized(stateLock) { sessionId.also { resetLocked() } }
      closeAudio()
      if (closing != null) runCatching { closeGatewaySession(closing) }
      _snapshot.value
    }

  /** Direct transport ended: the Gateway retires the relay with the socket, so only local state closes. */
  fun disconnectLocal() {
    synchronized(stateLock) {
      if (sessionId != null || activeAttempt != null) {
        lifecycleGeneration.incrementAndGet()
        resetLocked()
      }
    }
    closeAudio()
  }

  fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    val obj = parseJsonParamsObject(payloadJson) ?: return
    if (event == "chat") {
      val runId = obj["runId"].asStringOrNull() ?: return
      val state = obj["state"].asStringOrNull() ?: return
      agent.handleChatEvent(
        sessionKey = obj["sessionKey"].asStringOrNull(),
        runId = runId,
        state = state,
        message = obj["message"],
      )
      return
    }
    if (event != "talk.event") return
    val eventSessionId = obj["relaySessionId"].asStringOrNull() ?: obj["sessionId"].asStringOrNull()
    val (attempt, currentSessionId) =
      synchronized(stateLock) {
        val currentSessionId = sessionId
        if (currentSessionId == null || eventSessionId != currentSessionId) return
        val attempt = activeAttempt ?: return
        attempt to currentSessionId
      }
    when (obj["type"].asStringOrNull()) {
      "ready", "inputAudio" -> {
        updateActiveStateIfCurrent(attempt, currentSessionId, DirectTalkState.Listening)
      }

      "audio" -> {
        val encoded = obj["audioBase64"].asStringOrNull() ?: return
        if (encoded.length > OUTPUT_QUEUE_BASE64_CHAR_CAPACITY) {
          fail(attempt, "Talk audio exceeds the relay buffer", currentSessionId)
          return
        }
        val bytes =
          runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
            ?: return
        if (bytes.size % PCM_16_BYTES != 0) {
          fail(attempt, "Invalid Talk audio frame", currentSessionId)
          return
        }
        if (enqueueOutput(attempt, currentSessionId, bytes)) {
          updateActiveStateIfCurrent(attempt, currentSessionId, DirectTalkState.Speaking)
        }
      }

      "clear" -> {
        enqueueOutput(attempt, currentSessionId, null)
      }

      "mark" -> {
        val markName = obj["markName"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
        acknowledgeMark(attempt, currentSessionId, markName)
      }

      "transcript" -> {
        handleTranscriptEvent(attempt, currentSessionId, obj)
      }

      "toolCall" -> {
        synchronized(stateLock) {
          if (!isCurrent(attempt, currentSessionId)) return
          val callId = obj["callId"].asStringOrNull() ?: return
          val name = obj["name"].asStringOrNull() ?: return
          agent.handleToolCall(callId = callId, name = name, args = obj["args"], forced = obj["forced"].asBooleanOrNull() == true)
        }
      }

      "error" -> {
        fail(attempt, obj["message"].asStringOrNull() ?: "Talk failed", currentSessionId)
      }

      "close" -> {
        synchronized(stateLock) {
          if (!isCurrent(attempt, currentSessionId)) return
          lifecycleGeneration.incrementAndGet()
          resetLocked()
        }
        closeAudio()
      }
    }
  }

  private fun acknowledgeMark(
    attempt: Attempt,
    activeSessionId: String,
    markName: String,
  ) {
    val ownerScope = synchronized(stateLock) { eventScope.takeIf { isCurrent(attempt, activeSessionId) } } ?: return
    ownerScope.launch {
      synchronized(stateLock) { if (!isCurrent(attempt, activeSessionId)) return@launch }
      runCatching {
        val params =
          buildJsonObject {
            put("sessionId", JsonPrimitive(activeSessionId))
            put("markName", JsonPrimitive(markName))
          }
        requestGateway("talk.session.acknowledgeMark", params.toString(), 8_000L)
      }
    }
  }

  private fun handleTranscriptEvent(
    attempt: Attempt,
    activeSessionId: String,
    obj: JsonObject,
  ) {
    synchronized(stateLock) {
      if (!isCurrent(attempt, activeSessionId)) return
      val text = obj["text"].asStringOrNull()?.takeIf(String::isNotBlank) ?: return
      val final = obj["final"].asBooleanOrNull() == true
      val role =
        when (obj["role"].asStringOrNull()) {
          "user" -> WearRealtimeTalkRole.USER
          "assistant" -> WearRealtimeTalkRole.ASSISTANT
          else -> return
        }
      upsertConversation(role, text, final)
      if (role == WearRealtimeTalkRole.USER && final) updateActiveState(DirectTalkState.Thinking)
    }
  }

  private fun startOutputLoop(
    attempt: Attempt,
    activeSessionId: String,
  ) {
    val messages = Channel<ByteArray?>(capacity = OUTPUT_QUEUE_CAPACITY)
    outputQueue?.close()
    outputQueue = messages
    outputJob?.cancel()
    outputJob =
      scope.launch(Dispatchers.IO) {
        for (audio in messages) {
          if (!isCurrent(attempt, activeSessionId)) continue
          val written =
            try {
              if (audio == null) {
                synchronized(audioLock) { if (isCurrent(attempt, activeSessionId)) clearOutputLocked(attempt) }
                updateActiveStateIfCurrent(attempt, activeSessionId, DirectTalkState.Listening)
                false
              } else {
                writeOutput(attempt, activeSessionId, audio)
              }
            } catch (err: Throwable) {
              if (err is CancellationException) throw err
              fail(attempt, "Watch speaker is unavailable", activeSessionId)
              break
            }
          if (written) schedulePlaybackIdle(attempt, activeSessionId)
        }
      }
  }

  private fun enqueueOutput(
    attempt: Attempt,
    activeSessionId: String,
    audio: ByteArray?,
  ): Boolean {
    val accepted =
      synchronized(stateLock) {
        if (!isCurrent(attempt, activeSessionId)) return false
        outputQueue?.trySend(audio)?.isSuccess == true
      }
    if (!accepted) fail(attempt, "Talk audio queue overflowed", activeSessionId)
    return accepted
  }

  private fun startAppendLoop(
    attempt: Attempt,
    activeSessionId: String,
  ) {
    audioFrames?.close()
    appendJob?.cancel()
    // Dropping the oldest microphone frame under backpressure beats ending the call.
    val frames = Channel<ByteArray>(capacity = INPUT_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    audioFrames = frames
    appendJob =
      scope.launch {
        for (frame in frames) {
          if (!isCurrent(attempt, activeSessionId) || _snapshot.value.speaking) continue
          val params =
            buildJsonObject {
              put("sessionId", JsonPrimitive(activeSessionId))
              put("audioBase64", JsonPrimitive(Base64.encodeToString(frame, Base64.NO_WRAP)))
              put("timestamp", JsonPrimitive(SystemClock.elapsedRealtime()))
            }
          try {
            sendGatewayFrame("talk.session.appendAudio", params.toString(), 8_000L) { message ->
              fail(attempt, message, activeSessionId)
            }
          } catch (err: Throwable) {
            if (err is CancellationException) throw err
            fail(attempt, err.message ?: "Unable to send watch audio", activeSessionId)
          }
        }
      }
  }

  @SuppressLint("MissingPermission")
  private fun startCaptureLocked(attempt: Attempt) {
    if (_isCapturing.value || _isPlaying.value || activeAttempt !== attempt) return
    val frameBytes = WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ * PCM_16_BYTES * CAPTURE_FRAME_MILLIS / 1_000
    val minimumBuffer =
      AudioRecord.getMinBufferSize(
        WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    require(minimumBuffer > 0)
    val recorder =
      AudioRecord
        .Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(audioFormat(AudioFormat.CHANNEL_IN_MONO))
        .setBufferSizeInBytes(maxOf(minimumBuffer * 2, frameBytes * 4))
        .build()
    audioRecord = recorder
    check(recorder.state == AudioRecord.STATE_INITIALIZED)
    recorder.startRecording()
    // Native start failure can return normally while the recorder remains stopped.
    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    setCapturing(true)
    captureJob =
      scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(frameBytes)
        try {
          while (currentCoroutineContext().isActive && _isCapturing.value && audioRecord === recorder && activeAttempt === attempt) {
            val read = recorder.read(buffer, 0, buffer.size)
            val evenBytes = read - (read and 1)
            if (!_isCapturing.value || audioRecord !== recorder) break
            check(read >= 0) { "Watch microphone read failed: $read" }
            if (evenBytes == 0) {
              yield()
              continue
            }
            audioFrames?.trySend(buffer.copyOf(evenBytes))
          }
        } catch (err: Throwable) {
          if (err is CancellationException) throw err
          sessionId?.let { fail(attempt, "Watch microphone failed", it) }
        } finally {
          synchronized(audioLock) {
            // pauseCaptureLocked may already have taken and released this recorder.
            if (audioRecord === recorder) {
              audioRecord = null
              runCatching { recorder.stop() }
              runCatching { recorder.release() }
            }
          }
        }
      }
  }

  /** Returns true when the frame reached the speaker; the caller then schedules the idle check. */
  private fun writeOutput(
    attempt: Attempt,
    activeSessionId: String,
    bytes: ByteArray,
  ): Boolean =
    synchronized(audioLock) {
      if (!isCurrent(attempt, activeSessionId)) return false
      if (!_isPlaying.value) {
        pauseCaptureLocked()
        check(audioFocus.request())
      }
      val mouthLevels = mouthLevelAccumulator.append(bytes)
      val track = audioTrack ?: createAudioTrack(bytes.size).also { audioTrack = it }
      var written = 0
      while (written < bytes.size) {
        val count = track.write(bytes, written, bytes.size - written)
        if (count <= 0) break
        written += count
      }
      check(written == bytes.size)
      if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
      setPlaying(true)
      if (mouthLevels.isNotEmpty()) {
        val timeline = mouthTimelineLocked()
        mouthLevels.forEach { level -> timeline.trySend(level) }
      }
      val durationMillis =
        ((written / PCM_16_BYTES.toDouble()) / WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ * 1_000.0)
          .toLong()
          .coerceAtLeast(1L)
      playbackEndsAtMillis = maxOf(SystemClock.elapsedRealtime(), playbackEndsAtMillis) + durationMillis
      true
    }

  private fun mouthTimelineLocked(): Channel<Float> {
    mouthFrames?.let { return it }
    val frames = Channel<Float>(capacity = MOUTH_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    mouthFrames = frames
    mouthJob =
      scope.launch {
        try {
          for (level in frames) {
            synchronized(audioLock) { if (mouthFrames === frames) _mouthLevel.value = level }
            delay(MOUTH_FRAME_MILLIS.toLong())
          }
        } finally {
          synchronized(audioLock) { if (mouthFrames === frames) _mouthLevel.value = 0f }
        }
      }
    return frames
  }

  private fun createAudioTrack(frameBytes: Int): AudioTrack {
    val minimumBuffer =
      AudioTrack.getMinBufferSize(
        WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    require(minimumBuffer > 0)
    return AudioTrack
      .Builder()
      .setAudioAttributes(wearSpeechAudioAttributes)
      .setAudioFormat(audioFormat(AudioFormat.CHANNEL_OUT_MONO))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(minimumBuffer * 2, frameBytes * 4))
      .build()
      .also { check(it.state == AudioTrack.STATE_INITIALIZED) }
  }

  private fun audioFormat(channelMask: Int): AudioFormat =
    AudioFormat
      .Builder()
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(WearProtocol.REALTIME_AUDIO_SAMPLE_RATE_HZ)
      .setChannelMask(channelMask)
      .build()

  private fun schedulePlaybackIdle(
    attempt: Attempt,
    activeSessionId: String,
  ) {
    synchronized(audioLock) {
      playbackIdleJob?.cancel()
      val scheduledPlaybackEndMillis = playbackEndsAtMillis
      val finalFrameDurationMillis = mouthLevelAccumulator.pendingFrameDurationMillis()
      playbackIdleJob =
        scope.launch {
          if (finalFrameDurationMillis > 0L) {
            val finalFrameStartsAtMillis = scheduledPlaybackEndMillis - finalFrameDurationMillis
            while (SystemClock.elapsedRealtime() < finalFrameStartsAtMillis) delay(MOUTH_FRAME_MILLIS.toLong())
            synchronized(audioLock) {
              if (activeAttempt === attempt && playbackEndsAtMillis == scheduledPlaybackEndMillis) {
                mouthLevelAccumulator.flush().forEach { level -> mouthTimelineLocked().trySend(level) }
              }
            }
          }
          while (SystemClock.elapsedRealtime() < scheduledPlaybackEndMillis) delay(MOUTH_FRAME_MILLIS.toLong())
          delay(PLAYBACK_DRAIN_GRACE_MILLIS)
          val drained =
            synchronized(audioLock) {
              if (activeAttempt === attempt && playbackEndsAtMillis == scheduledPlaybackEndMillis) {
                clearOutputLocked(attempt)
                true
              } else {
                false
              }
            }
          if (drained) updateActiveStateIfCurrent(attempt, activeSessionId, DirectTalkState.Listening)
        }
    }
  }

  private fun clearOutputLocked(attempt: Attempt?) {
    playbackIdleJob?.cancel()
    playbackIdleJob = null
    playbackEndsAtMillis = 0L
    val activeMouthFrames = mouthFrames
    mouthFrames = null
    activeMouthFrames?.close()
    mouthJob?.cancel()
    mouthJob = null
    mouthLevelAccumulator.reset()
    _mouthLevel.value = 0f
    val track = audioTrack
    audioTrack = null
    runCatching { track?.pause() }
    runCatching { track?.flush() }
    runCatching { track?.stop() }
    runCatching { track?.release() }
    setPlaying(false)
    audioFocus.abandon()
    if (attempt != null && activeAttempt === attempt) {
      runCatching { startCaptureLocked(attempt) }
        .onFailure { sessionId?.let { fail(attempt, "Watch microphone is unavailable", it) } }
    }
  }

  private fun setCapturing(value: Boolean) {
    if (_isCapturing.value == value) return
    _isCapturing.value = value
    onAudioState(value, _isPlaying.value)
  }

  private fun setPlaying(value: Boolean) {
    if (_isPlaying.value == value) return
    _isPlaying.value = value
    onAudioState(_isCapturing.value, value)
  }

  private fun pauseCaptureLocked() {
    setCapturing(false)
    captureJob?.cancel()
    captureJob = null
    val recorder = audioRecord
    audioRecord = null
    runCatching { recorder?.stop() }
    runCatching { recorder?.release() }
  }

  private fun closeAudio() {
    synchronized(audioLock) {
      pauseCaptureLocked()
      clearOutputLocked(attempt = null)
    }
  }

  private fun upsertConversation(
    role: WearRealtimeTalkRole,
    text: String,
    final: Boolean,
  ) {
    val entries = conversation.toMutableList()
    val entryId = streamingEntryIds[role] ?: UUID.randomUUID().toString()
    val index = entries.indexOfFirst { entry -> entry.id == entryId }
    val entry =
      WearRealtimeTalkEntry(
        id = entryId,
        role = role,
        text = if (text.length <= WearReplyText.MAX_TEXT_LENGTH) text else WearReplyText.preview(text),
        streaming = !final,
        textTruncated = text.length > WearReplyText.MAX_TEXT_LENGTH,
        fullTextAvailable = text.length <= WearReplyText.MAX_TEXT_LENGTH,
        textRevision = (entries.getOrNull(index)?.textRevision ?: 0L) + 1L,
      )
    if (index >= 0) entries[index] = entry else entries += entry
    if (final) streamingEntryIds.remove(role) else streamingEntryIds[role] = entryId
    conversation = entries.takeLast(MAX_CONVERSATION_ENTRIES)
    setSnapshot(
      _snapshot.value.copy(
        conversation =
          conversation.map { item ->
            val preview = WearReplyText.preview(item.text)
            item.copy(text = preview, textTruncated = item.textTruncated || preview != item.text)
          },
      ),
    )
  }

  private fun updateActiveState(state: DirectTalkState) {
    setSnapshot(
      _snapshot.value.copy(
        active = true,
        listening = state == DirectTalkState.Listening,
        speaking = state == DirectTalkState.Speaking,
        status = state.status,
        statusText = state.text,
        attemptId = activeAttempt?.id,
      ),
    )
  }

  private fun updateActiveStateIfCurrent(
    attempt: Attempt,
    activeSessionId: String,
    state: DirectTalkState,
  ) {
    synchronized(stateLock) {
      if (!isCurrent(attempt, activeSessionId)) return
      updateActiveState(state)
    }
  }

  private fun isCurrent(
    attempt: Attempt,
    expectedSessionId: String,
  ): Boolean = activeAttempt === attempt && sessionId == expectedSessionId

  private fun fail(
    attempt: Attempt,
    message: String,
    expectedSessionId: String? = null,
  ) {
    val closingSession =
      synchronized(stateLock) {
        // Only the relay that owns the failure may tear down; a late error must not stop a replacement.
        if (activeAttempt !== attempt) return
        if (expectedSessionId != null && sessionId != expectedSessionId) return
        Log.w(TAG, message)
        val closingSession = sessionId
        agent.resetTransport()
        setSnapshot(
          _snapshot.value.copy(
            active = false,
            listening = false,
            speaking = false,
            status = WearRealtimeTalkStatus.ERROR,
            statusText = message.take(MAX_STATUS_LENGTH),
            conversation = _snapshot.value.conversation.map { it.copy(fullTextAvailable = false) },
          ),
        )
        closeTransportLocked()
        conversation = emptyList()
        closingSession
      }
    scope.launch {
      closeAudio()
      closingSession?.let { runCatching { closeGatewaySession(it) } }
    }
  }

  private suspend fun closeGatewaySession(closingSession: String) {
    val params = buildJsonObject { put("sessionId", JsonPrimitive(closingSession)) }
    requestGateway("talk.session.close", params.toString(), 5_000L)
  }

  private fun closeTransportLocked() {
    sessionId = null
    activeAttempt = null
    audioFrames?.close()
    audioFrames = null
    appendJob?.cancel()
    appendJob = null
    outputQueue?.close()
    outputQueue = null
    outputJob?.cancel()
    outputJob = null
    eventScope?.cancel()
    eventScope = null
  }

  private fun resetLocked() {
    val closingAttemptId = activeAttempt?.id
    agent.resetTransport()
    closeTransportLocked()
    streamingEntryIds.clear()
    conversation = emptyList()
    setSnapshot(WearRealtimeTalkSnapshot(attemptId = closingAttemptId))
  }

  private fun setSnapshot(snapshot: WearRealtimeTalkSnapshot) {
    _snapshot.value = snapshot
    onSnapshot(snapshot)
  }

  private companion object {
    const val TAG = "WearDirectTalk"
    const val MAX_CONVERSATION_ENTRIES = 20
    const val MAX_STATUS_LENGTH = 160
    const val INPUT_QUEUE_CAPACITY = 64
    const val OUTPUT_QUEUE_CAPACITY = 64
    const val OUTPUT_QUEUE_BYTE_CAPACITY = WearProtocol.MAX_REALTIME_AUDIO_FRAME_BYTES * 128
    const val OUTPUT_QUEUE_BASE64_CHAR_CAPACITY = (OUTPUT_QUEUE_BYTE_CAPACITY + 2) / 3 * 4
    const val SESSION_CREATE_TIMEOUT_MILLIS = 15_000L
    const val CAPTURE_FRAME_MILLIS = 100
    const val ISO_639_1_LANGUAGE_LENGTH = 2
    const val MOUTH_QUEUE_CAPACITY = 256
    const val PCM_16_BYTES = 2
    const val PLAYBACK_DRAIN_GRACE_MILLIS = 120L
  }
}

private fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
