package ai.openclaw.wear

import ai.openclaw.app.LocationMode
import ai.openclaw.app.hasPermission
import ai.openclaw.wear.shared.WearRealtimeTalkRole
import ai.openclaw.wear.shared.WearRealtimeTalkStatus
import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Composable
internal fun WearConnectionHost(
  app: WearApplication,
  initialPage: WearHomePage,
  navigationRequest: WearNavigationRequest?,
  onNavigationRequestHandled: (Int) -> Unit,
  onMessageSubmitted: () -> Unit = {},
) {
  val runtime = app.directRuntime
  val state by runtime.state.collectAsState()
  var manage by remember { mutableStateOf(false) }
  // Presentation changes retain Phone Proxy work; selecting Direct retires its owner.
  val phoneProxyOwner = if (state.selected == null) rememberViewModelStoreOwner() else null
  if (!manage && !state.connectionManagementRequired && state.selected == null && !state.busy) {
    val viewModel = viewModel<WearViewModel>(checkNotNull(phoneProxyOwner))
    OpenClawWearApp(
      viewModel,
      remember { WearSettingsStore(app) },
      remember { WearReplySpeaker(app) },
      initialPage,
      navigationRequest,
      onNavigationRequestHandled,
      onMessageSubmitted = onMessageSubmitted,
      onManageConnection = { manage = true },
    )
  } else {
    OpenClawWearTheme(themeMode = remember { WearSettingsStore(app).read().themeMode }) {
      AppScaffold {
        val talkRequestId =
          navigationRequest?.takeIf { it.target == WearLaunchTarget.Voice }?.id
            ?: INITIAL_TALK_REQUEST.takeIf { initialPage == WearHomePage.Voice }
        WearDirectContent(runtime, state, manage || state.connectionManagementRequired, onManage = { manage = it }, talkRequestId = talkRequestId)
      }
    }
    navigationRequest?.let { request ->
      LaunchedEffect(request.id) { onNavigationRequestHandled(request.id) }
    }
  }
}

@Composable
private fun WearDirectContent(
  runtime: WearDirectRuntime,
  state: WearDirectState,
  manage: Boolean,
  onManage: (Boolean) -> Unit,
  talkRequestId: Int? = null,
) {
  var page by remember { mutableStateOf("chat") }
  var review by remember { mutableStateOf<WearApproval?>(null) }
  var confirmation by remember { mutableStateOf<String?>(null) }
  var forget by remember { mutableStateOf<String?>(null) }
  var inputOwner by remember { mutableStateOf<WearInputOwner?>(null) }
  var enterSetup by remember { mutableStateOf(false) }
  var setupCode by remember { mutableStateOf("") }
  var openReply by remember(state.selected?.stableId, state.sessionKey) { mutableStateOf<WearDirectReply?>(null) }
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(review?.id) {
    while (review != null) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }
  LaunchedEffect(state.selected?.stableId, state.sessionKey) {
    review = null
    confirmation = null
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) setupCode = "" }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val context = LocalContext.current
  // Direct mode owns its own reply speaker: the phone-proxy UI's speaker and auto-speak never run here.
  val settingsStore = remember { WearSettingsStore(context) }
  val speaker = remember { WearReplySpeaker(context.applicationContext, settingsStore.read().voiceName) }
  val availableVoices by speaker.availableVoices.collectAsState()
  var voiceName by remember { mutableStateOf(settingsStore.read().voiceName) }
  DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
  val speaking by speaker.isSpeaking.collectAsState()
  var autoSpeak by remember { mutableStateOf(settingsStore.read().autoSpeak) }
  var awaitingReplySince by remember { mutableStateOf<Long?>(null) }
  var talkEngine by remember { mutableStateOf(settingsStore.read().talkEngine) }
  var speechSpeed by remember { mutableStateOf(settingsStore.read().speechSpeed) }
  var speechPauses by remember { mutableStateOf(settingsStore.read().speechPauses) }
  val solicitedSinceHolder = remember { mutableStateOf<Long?>(null) }
  // Low-power voice chat: watch speech recognition -> Claw (like a typed message) -> watch voice at the user's pace.
  // (id, words): a fresh id per utterance keys the send effect, so repeating the same words still sends, and the effect
  // never changes its own key (clearing it would cancel the send at its first suspension).
  val voiceSend = remember { mutableStateOf<Pair<Long, String>?>(null) }
  val scope = rememberCoroutineScope()
  var voiceReading by remember { mutableStateOf<Job?>(null) }
  val voiceChat =
    remember {
      WearVoiceChat(context.applicationContext) { text -> voiceSend.value = System.nanoTime() to text }
    }
  val unreachableText = stringResource(R.string.watch_voice_chat_unreachable)
  LaunchedEffect(voiceSend.value) {
    val text = voiceSend.value?.second ?: return@LaunchedEffect
    // A socket kept from the background can be silently dead: check it first, as Talk does, so words are not lost.
    if (!runtime.probeConnection()) awaitVoiceConnection(runtime)
    // A previous send can still be waiting for its acknowledgement; retry briefly rather than drop the words.
    val deadline = System.currentTimeMillis() + VOICE_CHAT_SEND_RETRY_MS
    var accepted = false
    while (!accepted) {
      val sentAt = System.currentTimeMillis()
      awaitingReplySince = sentAt
      solicitedSinceHolder.value = sentAt
      accepted = runtime.send(text, runtime.inputOwner())
      if (accepted) break
      awaitingReplySince = null
      if (System.currentTimeMillis() > deadline) break
      delay(VOICE_CHAT_SEND_STEP_MS)
    }
    if (accepted && awaitVoiceSendAck(runtime)) return@LaunchedEffect
    if (accepted) {
      // No acknowledgement: reconnect and resend once (same idempotency key, so the Gateway cannot run it twice).
      android.util.Log.w("WearVoiceChat", "send not acknowledged; reconnecting and resending")
      runtime.probeConnection()
      awaitVoiceConnection(runtime)
      withTimeoutOrNull(VOICE_CHAT_ACK_MS) { runtime.state.first { !it.sending } }
      runtime.retrySend()
      if (awaitVoiceSendAck(runtime)) return@LaunchedEffect
    }
    // Never wait in silence: say it did not get through, then listen (or wait for a hold) again.
    android.util.Log.w("WearVoiceChat", "send failed; telling the user")
    awaitingReplySince = null
    runtime.abandonPendingSend()
    voiceChat.replyStarted()
    speaker.speak(unreachableText, WearSpeechPace(speechSpeed.rate, 0)) { voiceChat.replyFinished() }
  }
  DisposableEffect(voiceChat) { onDispose { voiceChat.stop() } }
  val voiceState by voiceChat.state.collectAsState()
  val voiceActive = voiceState != WearVoiceChatState.Off
  // Wrist-down drops the app into ambient and the background, where Android cuts its network and microphone; the
  // microphone foreground service that keeps GPT-Live Talk alive does the same for voice chat.
  LaunchedEffect(voiceActive) {
    if (voiceActive) {
      WearTalkForegroundService.start(context)
    } else if (!runtime.state.value.talk.active) {
      WearTalkForegroundService.stop(context)
    }
  }
  LaunchedEffect(voiceState) {
    // A send that never got a reply (refused while another was pending, failed) must not leave voice chat waiting.
    if (voiceState == WearVoiceChatState.Sending) {
      delay(VOICE_CHAT_REPLY_TIMEOUT_MS)
      if (voiceChat.state.value == WearVoiceChatState.Sending) voiceChat.replyMissing()
    }
  }
  val latestAssistant = state.messages.lastOrNull { it.role == "assistant" }
  val latestAssistantKey = latestAssistant?.let { "${it.id}:${it.timestamp}:${it.text.hashCode()}" }
  var seenAssistantKey by remember { mutableStateOf(latestAssistantKey) }
  val runIdle = state.runId == null
  LaunchedEffect(latestAssistantKey, runIdle) {
    if (latestAssistantKey == seenAssistantKey) return@LaunchedEffect
    val since = awaitingReplySince
    // The reply is the newest assistant text once the run is over: text written before tool calls ("let me check")
    // and history reloads of older replies must not be read as the answer, or the real answer arrives unheard.
    val older = (latestAssistant?.timestamp ?: Long.MAX_VALUE) < (since ?: 0L) - CLOCK_SKEW_MS
    if (since != null && (!runIdle || older)) return@LaunchedEffect
    seenAssistantKey = latestAssistantKey
    val awaiting = since != null
    awaitingReplySince = null
    if (!awaiting || latestAssistant == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
    // "[voice: speed=…; pauses=…]" from Claw after a spoken change: apply and keep it, never read it out.
    val reply = parseReplyVoice(parseReplyNavigation(latestAssistant.text).text)
    reply.speed?.let {
      speechSpeed = it
      settingsStore.writeSpeechSpeed(it)
    }
    reply.pauses?.let {
      speechPauses = it
      settingsStore.writeSpeechPauses(it)
    }
    if (voiceActive && voiceChat.state.value == WearVoiceChatState.Listening) {
      // The user is already saying something new (a hold during the wait); the reply stays on screen unread.
    } else if (voiceActive) {
      voiceChat.replyStarted()
      voiceReading?.cancel()
      voiceReading = scope.launch { readVoiceReply(reply.text, speaker, voiceChat, speechSpeed.rate, speechPauses.millis) }
    } else if (autoSpeak) {
      speaker.speak(reply.text)
    }
  }
  // Maps opens only for a navigation reply that arrives after the user's own send or while their Talk session runs
  // (Talk consult answers reach the chat history, not the reply speaker). Markers already present are never replayed.
  var solicitedSince by solicitedSinceHolder
  LaunchedEffect(state.talk.active) { if (state.talk.active) solicitedSince = System.currentTimeMillis() }
  val navigationReply = state.messages.lastOrNull { it.role == "assistant" && parseReplyNavigation(it.text).destination != null }
  val navigationKey = navigationReply?.let { "${it.id}:${it.timestamp}" }
  var handledNavigationKey by remember { mutableStateOf(navigationKey) }
  LaunchedEffect(navigationKey) {
    if (navigationKey == null || navigationKey == handledNavigationKey) return@LaunchedEffect
    handledNavigationKey = navigationKey
    val since = solicitedSince ?: return@LaunchedEffect
    val fresh =
      System.currentTimeMillis() - since < SOLICITED_NAVIGATION_WINDOW_MS && (navigationReply.timestamp ?: 0L) >= since - CLOCK_SKEW_MS
    val destination = parseReplyNavigation(navigationReply.text).destination
    if (fresh && destination != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      solicitedSince = null
      launchNavigation(context, destination)
    }
  }
  // Greet once per Talk session as soon as it listens, so the user hears that it is on. The greeting is synthesized once
  // (cached) and played through Talk's own voice-call output: system TTS is held back while Talk owns the audio. The mic
  // is withheld from the model while it plays so the model does not answer the greeting.
  val greetingText = remember { settingsStore.read().talkGreeting.trim() }
  var greetingPcm by remember { mutableStateOf<ByteArray?>(null) }
  // Low-power voice chat greets with the watch voice and starts listening once the greeting has been read.
  val startVoiceChat = {
    if (voiceChat.state.value == WearVoiceChatState.Off) {
      val cachedGreeting = greetingPcm
      voiceChat.start(greeting = greetingText.isNotEmpty())
      // The cached copy plays at once; the watch voice would first fetch the network voice (about a second).
      if (cachedGreeting != null && greetingText.isNotEmpty()) {
        scope.launch {
          WearTalkGreeting.play(cachedGreeting)
          voiceChat.replyFinished()
        }
      } else if (greetingText.isNotEmpty()) {
        speaker.speak(greetingText, WearSpeechPace(speechSpeed.rate, 0)) { voiceChat.replyFinished() }
      }
    }
  }
  LaunchedEffect(greetingText, voiceName) {
    greetingPcm = if (greetingText.isEmpty()) null else WearTalkGreeting.pcm(context, greetingText, voiceName)
  }
  var greetedTalkAttempt by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(state.talk.attemptId, state.talk.status) {
    val attemptId = state.talk.attemptId ?: return@LaunchedEffect
    val ready = state.talk.status == WearRealtimeTalkStatus.LISTENING || state.talk.status == WearRealtimeTalkStatus.SPEAKING
    if (!state.talk.active || !ready || attemptId == greetedTalkAttempt) return@LaunchedEffect
    greetedTalkAttempt = attemptId
    val pcm = greetingPcm ?: return@LaunchedEffect
    runtime.talk.holdInput(WearTalkGreeting.durationMillis(pcm) + GREETING_HOLD_PAD_MS)
    runtime.talk.playLocal(pcm)
  }
  // Hold the screen while a reply is pending or Talk runs: Wear's 15 s timeout is shorter than most agent turns, and a
  // backgrounded app has its microphone silenced by the system.
  val view = LocalView.current
  val holdScreen = awaitingReplySince != null || state.sending || state.runId != null || state.talk.active || state.talkBusy || voiceActive
  DisposableEffect(view, holdScreen) {
    view.keepScreenOn = holdScreen
    onDispose { view.keepScreenOn = false }
  }
  val locationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      if (grants.values.any { it }) runtime.setLocationMode(LocationMode.WhileUsing)
    }
  val microphoneLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted && talkEngine == WearTalkEngine.LowPower) {
        startVoiceChat()
      } else if (granted) {
        runtime.startTalk()
      }
    }
  // Assistant button / Talk tile: start Talk as soon as the direct connection is ready, without a tap.
  var handledTalkRequest by remember { mutableStateOf<Int?>(null) }
  var talkWanted by remember { mutableStateOf(false) }
  LaunchedEffect(talkRequestId) {
    if (talkRequestId != null && talkRequestId != handledTalkRequest) {
      handledTalkRequest = talkRequestId
      talkWanted = true
    }
  }
  LaunchedEffect(talkWanted) {
    if (talkWanted) {
      delay(AUTO_TALK_WAIT_MS)
      if (talkWanted) {
        // Never leave an Assist press unanswered: say why nothing started.
        android.util.Log.w("WearVoiceChat", "assist: gave up waiting (connected=${runtime.state.value.connected})")
        talkWanted = false
        speaker.speak(unreachableText)
      }
    }
  }
  DisposableEffect(view, talkWanted) {
    if (talkWanted) view.keepScreenOn = true
    onDispose { if (!holdScreen) view.keepScreenOn = false }
  }
  val lifecycleState by lifecycle.currentStateFlow.collectAsState()
  val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
  LaunchedEffect(talkWanted, resumed, state.connected, state.sessionKey, state.talkBusy, state.talk.active) {
    if (!talkWanted) return@LaunchedEffect
    android.util.Log.i(
      "WearVoiceChat",
      "assist: resumed=$resumed connected=${state.connected} session=${state.sessionKey != null} busy=${state.talkBusy} " +
        "talk=${state.talk.active} voice=${voiceChat.state.value}",
    )
    if (state.talk.active || voiceActive) {
      talkWanted = false
      // The Assist button while the watch reads a reply: stop reading and listen.
      if (voiceChat.state.value == WearVoiceChatState.Speaking || voiceChat.state.value == WearVoiceChatState.Waiting) {
        voiceReading?.cancel()
        speaker.stop()
        voiceChat.interrupt()
      }
    } else if (resumed && talkEngine == WearTalkEngine.LowPower && voiceChat.available &&
      context.hasPermission(Manifest.permission.RECORD_AUDIO)
    ) {
      // Voice chat needs the Gateway only to send, and checks the connection then: greet and listen now, while the
      // connection comes up during the user's first sentence (it took ~6 s after a wake).
      talkWanted = false
      runtime.wakeConnection()
      startVoiceChat()
    } else if (resumed && state.connected && state.sessionKey != null && !state.talkBusy) {
      // A reconnect flips connected and reruns this effect, which then probes the fresh connection.
      if (!runtime.probeConnection()) {
        android.util.Log.i("WearVoiceChat", "assist: connection was stale; waiting for the reconnect")
        return@LaunchedEffect
      }
      talkWanted = false
      when {
        !context.hasPermission(Manifest.permission.RECORD_AUDIO) -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        talkEngine == WearTalkEngine.LowPower && voiceChat.available -> startVoiceChat()
        else -> runtime.startTalk()
      }
    }
  }
  val messageLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val message =
        result.data
          ?.let(RemoteInput::getResultsFromIntent)
          ?.getCharSequence("message")
          ?.toString()
      if (result.resultCode == Activity.RESULT_OK && message != null) {
        inputOwner?.let {
          awaitingReplySince = System.currentTimeMillis()
          solicitedSince = awaitingReplySince
          runtime.send(message, it)
        }
      }
      inputOwner = null
    }

  fun input(
    key: String,
    title: String,
  ): Intent {
    val remote = RemoteInput.Builder(key).setLabel(title).build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
      RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(remote))
      RemoteInputIntentHelper.putTitleExtra(it, title)
    }
  }
  BackHandler(manage || page != "chat" || review != null) {
    when {
      state.connectionManagementRequired -> {
        runtime.cancelSetup()
        onManage(false)
      }

      confirmation != null -> {
        confirmation = null
      }

      review != null -> {
        review = null
      }

      page != "chat" -> {
        page = "chat"
      }

      else -> {
        onManage(false)
      }
    }
  }
  val currentReview = review?.let { selected -> state.approvals.firstOrNull { it.id == selected.id } ?: selected }
  val title =
    when {
      manage -> stringResource(R.string.watch_connection)
      currentReview != null -> stringResource(R.string.watch_approval)
      page == "sessions" -> stringResource(R.string.watch_sessions)
      page == "approvals" -> stringResource(R.string.watch_approvals)
      else -> stringResource(R.string.chat)
    }
  val setupLabel = stringResource(R.string.watch_setup_code)
  val messageLabel = stringResource(R.string.message)
  openReply?.let { reply ->
    val message = reply.message
    ReplyReader(
      WearOpenReply(message = message, target = null, localText = parseReplyNavigation(message.text).text.takeIf { message.textTruncated == false }, supported = true),
      readReply = reply.readPage,
      onDismiss = { openReply = null },
    )
    return
  }
  // While Talk starts or runs, show only Talk: launched from the assistant button the user must see at a glance that it
  // is listening, without scrolling the chat page.
  if (!manage && page == "chat" && currentReview == null && (talkWanted || state.talk.active || state.talkBusy || voiceActive)) {
    val stopAll = {
      talkWanted = false
      if (voiceActive) {
        voiceReading?.cancel()
        voiceChat.stop()
        speaker.stop()
      } else {
        runtime.stopTalk()
      }
    }
    BackHandler { stopAll() }
    val pushToTalk by runtime.talk.pushToTalk.collectAsState()
    // The screen stays on for Talk, but dimmed: full brightness only while the screen is held, Claw speaks, or for a few
    // seconds after a touch (to read the transcript).
    var touchedAt by remember { mutableStateOf(0L) }
    var recentlyTouched by remember { mutableStateOf(false) }
    LaunchedEffect(touchedAt) {
      if (touchedAt == 0L) return@LaunchedEffect
      recentlyTouched = true
      delay(TALK_BRIGHT_AFTER_TOUCH_MS)
      recentlyTouched = false
    }
    val voiceStatus =
      when (voiceState) {
        WearVoiceChatState.Listening -> stringResource(R.string.watch_voice_chat_listening)
        WearVoiceChatState.Sending -> stringResource(R.string.watch_voice_chat_thinking)
        WearVoiceChatState.Speaking -> stringResource(R.string.watch_voice_chat_speaking)
        WearVoiceChatState.Waiting -> stringResource(R.string.watch_voice_chat_waiting)
        WearVoiceChatState.Off -> null
      }
    var voiceHeld by remember { mutableStateOf(false) }
    val bright = pushToTalk == WearPushToTalk.Held || state.talk.speaking || recentlyTouched || voiceHeld || speaking
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, bright) {
      fun setBrightness(level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = level }
      }
      setBrightness(if (bright) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else TALK_DIM_BRIGHTNESS)
      onDispose { setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
    // Hold anywhere (past PUSH_TO_TALK_HOLD_MS, so taps and scrolling stay normal) to speak with the mic at the mouth;
    // the first hold switches this session to push-to-talk, a double tap returns to open listening.
    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(state.talk.active, voiceActive) {
          if (!state.talk.active && !voiceActive) return@pointerInput
          detectTapGestures(
            onDoubleTap = { if (voiceActive) voiceChat.openListening() else runtime.talk.pushToTalkOff() },
            onTap = {
              if (voiceActive && voiceChat.state.value == WearVoiceChatState.Speaking) {
                voiceReading?.cancel()
                speaker.stop()
                voiceChat.interrupt()
              }
            },
            onPress = {
              touchedAt = System.currentTimeMillis()
              var holding = false
              coroutineScope {
                val hold =
                  launch {
                    delay(PUSH_TO_TALK_HOLD_MS)
                    holding = true
                    if (voiceActive) {
                      voiceHeld = true
                      voiceReading?.cancel()
                      speaker.stop() // a hold interrupts the reply, like speaking over GPT-Live
                      voiceChat.holdStart()
                    } else {
                      runtime.talk.pushToTalkPress()
                    }
                  }
                tryAwaitRelease()
                hold.cancel()
              }
              if (holding && voiceActive) {
                voiceHeld = false
                voiceChat.holdEnd()
              } else if (holding) {
                runtime.talk.pushToTalkRelease()
              }
            },
          )
        },
    ) {
      WearPage(pageLabel = stringResource(R.string.talk)) {
        item {
          DirectText(
            voiceStatus ?: if (state.talk.active || state.talk.statusText.isNotBlank()) state.talk.statusText else state.status,
          )
        }
        when (pushToTalk) {
          WearPushToTalk.Held -> {
            item { DirectText(stringResource(R.string.watch_push_to_talk_held)) }
          }

          WearPushToTalk.Waiting -> {
            item { DirectText(stringResource(R.string.watch_push_to_talk_on)) }
          }

          WearPushToTalk.Off -> {}
        }
        if (voiceActive) {
          state.messages.takeLast(2).forEach { message ->
            item {
              MessageBubble(
                message.chatRole,
                parseReplyVoice(parseReplyNavigation(message.text).text).text,
                truncated = message.textTruncated == true,
                onOpenReply = {},
              )
            }
          }
        }
        if (!voiceActive) {
          state.talk.conversation.takeLast(2).forEach { entry ->
            item {
              MessageBubble(
                if (entry.role == WearRealtimeTalkRole.USER) WearChatRole.USER else WearChatRole.ASSISTANT,
                entry.text,
                truncated = entry.textTruncated,
                streaming = entry.streaming,
                onOpenReply = {},
              )
            }
          }
        }
        item { SecondaryButton(stringResource(R.string.watch_talk_stop), true) { stopAll() } }
      }
    }
    return
  }
  WearPage(pageLabel = title) {
    item { DirectText(state.selected?.name ?: stringResource(R.string.watch_phone_proxy)) }
    state.error?.let { error -> item { DirectText(error) } }
    if (state.busy) item { DirectText(stringResource(R.string.watch_updating_connection)) }
    if (manage && state.trust == null) {
      item {
        SecondaryButton(setupLabel, !state.busy) {
          enterSetup = !enterSetup
          setupCode = ""
        }
      }
      if (enterSetup) {
        item {
          BasicTextField(
            value = setupCode,
            onValueChange = { if (it.length <= 16_384) setupCode = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = TextStyle(color = OpenClawWearTheme.colors.text, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            decorationBox = { field ->
              if (setupCode.isEmpty()) Text(setupLabel)
              field()
            },
          )
        }
        item {
          SecondaryButton(stringResource(R.string.watch_connect), !state.busy && setupCode.isNotBlank()) {
            runtime.setup(setupCode)
            setupCode = ""
            enterSetup = false
            onManage(false)
          }
        }
      }
      state.gateways.forEach { gateway ->
        item {
          SecondaryButton(gateway.name, !state.busy) {
            runtime.selectGateway(gateway.stableId)
            onManage(false)
          }
        }
        item {
          SecondaryButton(stringResource(R.string.watch_forget), !state.busy) { forget = gateway.stableId }
        }
      }
      forget?.let { id ->
        item { DirectText(stringResource(R.string.watch_forget_confirmation)) }
        item {
          SecondaryButton(stringResource(R.string.watch_confirm_forget), !state.busy) {
            runtime.forget(id)
            forget = null
          }
        }
      }
      item {
        SecondaryButton(stringResource(R.string.watch_phone_proxy), !state.busy) {
          runtime.selectPhoneProxy()
          onManage(false)
        }
      }
      item {
        SecondaryButton(stringResource(R.string.auto_speak) + ": " + stringResource(if (autoSpeak) R.string.on else R.string.off), true) {
          autoSpeak = !autoSpeak
          settingsStore.writeAutoSpeak(autoSpeak)
        }
      }
      // Cycle through the installed English voices (Indian English first); each tap speaks a sample in the new voice.
      item {
        val label = voiceName?.removePrefix("en-")?.replace("-x-", " ")?.replace("-", " ") ?: "default"
        SecondaryButton("Voice: $label", availableVoices.isNotEmpty()) {
          val index = availableVoices.indexOf(voiceName)
          val next = if (index < 0 || index == availableVoices.lastIndex) availableVoices.firstOrNull() else availableVoices[index + 1]
          voiceName = next
          settingsStore.writeVoice(next)
          speaker.setVoice(next)
          speaker.speak("This is how I will read your replies.", WearSpeechPace(speechSpeed.rate, 0L), onDone = null)
        }
      }
      // Assist button engine: GPT-Live Talk, or low-power voice chat (watch speech recognition, every request to Claw).
      item {
        val label = stringResource(if (talkEngine == WearTalkEngine.LowPower) R.string.watch_talk_engine_low_power else R.string.watch_talk_engine_live)
        SecondaryButton(stringResource(R.string.watch_talk_engine) + ": " + label, true) {
          talkEngine = if (talkEngine == WearTalkEngine.LowPower) WearTalkEngine.Live else WearTalkEngine.LowPower
          settingsStore.writeTalkEngine(talkEngine)
        }
      }
      // How the watch voice reads replies in low-power voice chat (Claw can also change these by voice).
      item {
        SecondaryButton(stringResource(R.string.watch_speech_speed) + ": " + speechSpeed.rawValue, true) {
          speechSpeed = WearSpeechSpeed.entries[(speechSpeed.ordinal + 1) % WearSpeechSpeed.entries.size]
          settingsStore.writeSpeechSpeed(speechSpeed)
          speaker.speak("This is my reading speed. And this is the next sentence.", WearSpeechPace(speechSpeed.rate, speechPauses.millis), onDone = null)
        }
      }
      item {
        SecondaryButton(stringResource(R.string.watch_speech_pauses) + ": " + speechPauses.rawValue, true) {
          speechPauses = WearSpeechPauses.entries[(speechPauses.ordinal + 1) % WearSpeechPauses.entries.size]
          settingsStore.writeSpeechPauses(speechPauses)
        }
      }
      val sharingLocation = state.locationMode != LocationMode.Off
      val hasLocationPermission =
        context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
      item {
        SecondaryButton(stringResource(if (sharingLocation) R.string.watch_location_on else R.string.watch_location_off), !state.busy) {
          when {
            sharingLocation -> runtime.setLocationMode(LocationMode.Off)
            hasLocationPermission -> runtime.setLocationMode(LocationMode.WhileUsing)
            else -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
          }
        }
      }
      if (sharingLocation) {
        item { DirectText(stringResource(if (state.locationShared) R.string.watch_location_shared else R.string.watch_location_not_shared)) }
      }
    } else if (state.trust != null) {
      val prompt = state.trust
      item { DirectText(stringResource(R.string.watch_certificate)) }
      item { DirectText(prompt.fingerprint) }
      prompt.previous?.let { previous -> item { DirectText(stringResource(R.string.watch_previous_certificate, previous)) } }
      item { SecondaryButton(stringResource(R.string.watch_trust_certificate), true) { runtime.acceptCertificate(prompt) } }
      item { SecondaryButton(stringResource(R.string.watch_reject_certificate), true, runtime::disconnect) }
    } else if (currentReview != null) {
      val approval = currentReview
      item { DirectText(approvalTitle(approval)) }
      approval.details.forEach { detail ->
        item {
          DirectText(
            when {
              detail.label == null -> detail.value
              detail.value.isEmpty() -> stringResource(detail.label)
              else -> stringResource(detail.label, detail.value)
            },
          )
        }
      }
      approval.externalResolution?.let { external ->
        item { DirectText(stringResource(R.string.watch_approval_external_resolution, external.label)) }
      }
      approval.reviewIssue?.let { issue -> item { DirectText(stringResource(issue)) } }
      approval.sourceSessionKey?.let { item { DirectText(it) } }
      item { DirectText(approval.status) }
      val enabled = state.connected && state.approvalsReady && approval.id !in state.resolving
      approval.decisions.forEach { decision ->
        item {
          SecondaryButton(decisionLabel(decision), enabled && approval.canResolve(decision, now)) {
            confirmation = decision
          }
        }
      }
      confirmation?.let { decision ->
        item {
          SecondaryButton(stringResource(R.string.watch_confirm_decision, decisionLabel(decision)), enabled && approval.canResolve(decision, now)) {
            runtime.resolve(approval, decision)
            confirmation = null
          }
        }
      }
      if (approval.id in state.resolving) item { DirectText(stringResource(R.string.watch_approval_unconfirmed)) }
    } else if (page == "sessions") {
      state.sessions.forEach { session ->
        item {
          SecondaryButton(session.title, state.connected) {
            runtime.selectSession(session.key, session.agentId)
            page = "chat"
          }
        }
      }
    } else if (page == "approvals") {
      if (!state.approvalsReady) item { DirectText(stringResource(R.string.watch_approvals_unavailable)) }
      if (state.approvalsIncomplete) item { DirectText(stringResource(R.string.watch_approvals_incomplete)) }
      state.approvals.forEach { approval ->
        item { SecondaryButton("${approvalTitle(approval)}: ${approval.status}", true) { review = approval } }
      }
      if (state.approvalsReady && state.approvals.isEmpty()) item { DirectText(stringResource(R.string.watch_no_approvals)) }
    } else {
      item { DirectText(state.status) }
      item { SecondaryButton(stringResource(R.string.watch_sessions), state.connected) { page = "sessions" } }
      item { SecondaryButton(stringResource(R.string.watch_approvals), state.connected) { page = "approvals" } }
      val talkActive = state.talk.active || voiceActive
      item {
        SecondaryButton(stringResource(if (talkActive) R.string.watch_talk_stop else R.string.talk), state.connected && state.sessionKey != null && !state.talkBusy) {
          when {
            voiceActive -> voiceChat.stop()
            state.talk.active -> runtime.stopTalk()
            !context.hasPermission(Manifest.permission.RECORD_AUDIO) -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            talkEngine == WearTalkEngine.LowPower && voiceChat.available -> startVoiceChat()
            else -> runtime.startTalk()
          }
        }
      }
      if (state.talk.active || state.talk.status == WearRealtimeTalkStatus.ERROR) {
        item { DirectText(state.talk.statusText) }
        state.talk.conversation.takeLast(2).forEach { entry ->
          item {
            MessageBubble(
              if (entry.role == WearRealtimeTalkRole.USER) WearChatRole.USER else WearChatRole.ASSISTANT,
              entry.text,
              truncated = entry.textTruncated,
              streaming = entry.streaming,
              onOpenReply = {},
            )
          }
        }
      }
      state.messages.forEach { message ->
        item { MessageBubble(message.chatRole, parseReplyNavigation(message.text).text, truncated = message.textTruncated == true, onOpenReply = { openReply = runtime.openReply(message, state) }) }
      }
      state.streamText?.let { text -> item { StreamingBubble(text) } }
      state.pendingSend?.let { pending ->
        item { MessageBubble(WearChatRole.USER, pending.message, truncated = false, onOpenReply = {}) }
      }
      if (latestAssistant != null) {
        item {
          SecondaryButton(stringResource(if (speaking) R.string.stop_speaking else R.string.speak_reply), true) {
            if (speaking) speaker.stop() else speaker.speak(parseReplyNavigation(latestAssistant.text).text)
          }
        }
      }
      item {
        SecondaryButton(messageLabel, state.connected && state.sessionKey != null && state.pendingSend == null && !state.sending && !state.sendUnknown) {
          inputOwner = runtime.inputOwner()
          messageLauncher.launch(input("message", messageLabel))
        }
      }
      val pending = state.pendingSend
      if (pending != null && !state.sending) {
        item { DirectText(stringResource(if (state.sendUnknown) R.string.watch_message_unconfirmed else R.string.message_not_sent)) }
        item { SecondaryButton(stringResource(R.string.retry), state.connected, runtime::retrySend) }
        if (!state.sendUnknown) {
          item {
            SecondaryButton(stringResource(R.string.watch_discard_message), true) {
              runtime.discardPendingSend(pending)
              awaitingReplySince = null
            }
          }
        }
      }
      if (state.runId != null) item { SecondaryButton(stringResource(R.string.watch_stop), state.connected, runtime::abort) }
    }
    if (!manage) {
      item { SecondaryButton(stringResource(R.string.refresh), state.connected, runtime::refresh) }
      item {
        SecondaryButton(stringResource(if (state.connected) R.string.watch_disconnect else R.string.watch_reconnect), !state.busy) {
          if (state.connected) runtime.disconnect() else runtime.reconnect()
        }
      }
      item { SecondaryButton(stringResource(R.string.watch_connection), true) { onManage(true) } }
    }
  }
}

@Composable
private fun DirectText(text: String) {
  Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), textAlign = TextAlign.Center)
}

@Composable
private fun approvalTitle(approval: WearApproval): String = approval.title.ifEmpty { stringResource(if (approval.kind == "exec") R.string.watch_command_approval else R.string.watch_approval) }

@Composable
private fun decisionLabel(decision: String): String =
  stringResource(
    when (decision) {
      "allow-once" -> R.string.watch_allow_once
      "allow-always" -> R.string.watch_allow_always
      else -> R.string.watch_deny
    },
  )

private const val INITIAL_TALK_REQUEST = -1
private const val AUTO_TALK_WAIT_MS = 20_000L
private const val SOLICITED_NAVIGATION_WINDOW_MS = 5 * 60_000L
private const val CLOCK_SKEW_MS = 30_000L
private const val GREETING_HOLD_PAD_MS = 400L

private const val PUSH_TO_TALK_HOLD_MS = 300L
private const val TALK_DIM_BRIGHTNESS = 0.15f
private const val TALK_BRIGHT_AFTER_TOUCH_MS = 8_000L
private const val VOICE_CHAT_REPLY_TIMEOUT_MS = 120_000L
private const val VOICE_CHAT_SEND_RETRY_MS = 10_000L
private const val VOICE_CHAT_ACK_MS = 8_000L
private const val VOICE_CHAT_CONNECT_MS = 10_000L

/** Pauses at least this long listen for an interruption (the recognizer needs about half a second to start). */
private const val VOICE_CHAT_LISTENING_PAUSE_MS = 2_000L
private const val VOICE_CHAT_SEND_STEP_MS = 500L

/**
 * Read a low-power voice chat reply sentence by sentence. Long pauses listen for the user (they are silent, so the mic
 * does not hear the watch voice): words there end the reply and start the next request. Short pauses just wait.
 */
private suspend fun readVoiceReply(
  text: String,
  speaker: WearReplySpeaker,
  voiceChat: WearVoiceChat,
  rate: Float,
  pauseMillis: Long,
) {
  val sentences = if (pauseMillis > 0) splitSentences(text).ifEmpty { listOf(text) } else listOf(text)
  sentences.forEachIndexed { index, sentence ->
    if (index > 0) {
      if (pauseMillis >= VOICE_CHAT_LISTENING_PAUSE_MS) {
        val interrupted =
          suspendCancellableCoroutine { done -> voiceChat.listenDuringPause(pauseMillis) { if (done.isActive) done.resume(it) } }
        if (interrupted) return
      } else {
        delay(pauseMillis)
      }
    }
    suspendCancellableCoroutine { done -> speaker.speak(sentence, WearSpeechPace(rate, 0)) { if (done.isActive) done.resume(Unit) } }
  }
  voiceChat.replyFinished()
}

private suspend fun awaitVoiceConnection(runtime: WearDirectRuntime) {
  withTimeoutOrNull(VOICE_CHAT_CONNECT_MS) { runtime.state.first { it.connected } }
}

/** True once the Gateway acknowledged the send (pending cleared); false on timeout or an unconfirmed failure. */
private suspend fun awaitVoiceSendAck(runtime: WearDirectRuntime): Boolean =
  withTimeoutOrNull(VOICE_CHAT_ACK_MS) {
    runtime.state.first { (it.pendingSend == null && !it.sending) || it.sendUnknown }
  }?.let { !it.sendUnknown } ?: false
