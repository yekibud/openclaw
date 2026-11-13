package ai.openclaw.wear

import ai.openclaw.app.LocationMode
import ai.openclaw.app.hasPermission
import ai.openclaw.wear.shared.WearRealtimeTalkRole
import ai.openclaw.wear.shared.WearRealtimeTalkStatus
import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

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
  val latestAssistant = state.messages.lastOrNull { it.role == "assistant" }
  val latestAssistantKey = latestAssistant?.let { "${it.id}:${it.timestamp}:${it.text.hashCode()}" }
  var seenAssistantKey by remember { mutableStateOf(latestAssistantKey) }
  LaunchedEffect(latestAssistantKey) {
    if (latestAssistantKey == seenAssistantKey) return@LaunchedEffect
    seenAssistantKey = latestAssistantKey
    val awaiting = awaitingReplySince != null
    awaitingReplySince = null
    if (!awaiting || latestAssistant == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
    if (autoSpeak) speaker.speak(parseReplyNavigation(latestAssistant.text).text)
  }
  // Maps opens only for a navigation reply that arrives after the user's own send or while their Talk session runs
  // (Talk consult answers reach the chat history, not the reply speaker). Markers already present are never replayed.
  var solicitedSince by remember { mutableStateOf<Long?>(null) }
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
  val holdScreen = awaitingReplySince != null || state.sending || state.runId != null || state.talk.active || state.talkBusy
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
      if (granted) runtime.startTalk()
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
      talkWanted = false
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
    if (state.talk.active) {
      talkWanted = false
    } else if (resumed && state.connected && state.sessionKey != null && !state.talkBusy) {
      // A reconnect flips connected and reruns this effect, which then probes the fresh connection.
      if (!runtime.probeConnection()) return@LaunchedEffect
      talkWanted = false
      if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) runtime.startTalk() else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
  if (!manage && page == "chat" && currentReview == null && (talkWanted || state.talk.active || state.talkBusy)) {
    BackHandler {
      talkWanted = false
      runtime.stopTalk()
    }
    WearPage(pageLabel = stringResource(R.string.talk)) {
      item { DirectText(if (state.talk.active || state.talk.statusText.isNotBlank()) state.talk.statusText else state.status) }
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
      item {
        SecondaryButton(stringResource(R.string.watch_talk_stop), true) {
          talkWanted = false
          runtime.stopTalk()
        }
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
          speaker.speak("This is how I will read your replies.")
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
      val talkActive = state.talk.active
      item {
        SecondaryButton(stringResource(if (talkActive) R.string.watch_talk_stop else R.string.talk), state.connected && state.sessionKey != null && !state.talkBusy) {
          when {
            talkActive -> runtime.stopTalk()
            context.hasPermission(Manifest.permission.RECORD_AUDIO) -> runtime.startTalk()
            else -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        }
      }
      if (talkActive || state.talk.status == WearRealtimeTalkStatus.ERROR) {
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
private const val AUTO_TALK_WAIT_MS = 45_000L
private const val SOLICITED_NAVIGATION_WINDOW_MS = 5 * 60_000L
private const val CLOCK_SKEW_MS = 30_000L
private const val GREETING_HOLD_PAD_MS = 400L
