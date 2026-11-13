package ai.openclaw.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only test seam: delivers a typed message to the direct runtime without the system RemoteInput
 * screen, whose keyboard hand-off is not scriptable reliably on the emulator.
 *
 * adb shell am broadcast -n ai.openclaw.app.debug/ai.openclaw.wear.WearE2eReceiver \
 *   -a ai.openclaw.wear.debug.SEND --es text "What needs my attention on work Slack?"
 *
 * SETUP applies a limited Gateway setup code exactly as the Connect button does, so a real watch can be
 * paired without typing a 180-character code through the system keyboard:
 *
 * adb shell am broadcast -n ai.openclaw.app.debug/ai.openclaw.wear.WearE2eReceiver \
 *   -a ai.openclaw.wear.debug.SETUP --es code "<setup code>"
 */
class WearE2eReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val runtime = (context.applicationContext as WearApplication).directRuntime
    when (intent.action) {
      ACTION_SEND -> {
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) runtime.send(text, runtime.inputOwner())
      }

      ACTION_SETUP -> {
        val code = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()
        if (code.isNotEmpty()) runtime.setup(code)
      }

      ACTION_AUTO_SPEAK -> {
        // Persisted setting; the activity reads it on creation, so relaunch the app afterwards.
        WearSettingsStore(context).writeAutoSpeak(intent.getBooleanExtra(EXTRA_ENABLED, true))
      }

      ACTION_SET_VOICE -> {
        WearSettingsStore(context).writeVoice(intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf { it.isNotEmpty() })
      }

      ACTION_SET_GREETING -> {
        WearSettingsStore(context).writeTalkGreeting(intent.getStringExtra(EXTRA_TEXT))
      }

      ACTION_SPEAK -> {
        // Scripted TTS check with the saved voice; a throwaway speaker that shuts down after the sample.
        val text =
          intent
            .getStringExtra(EXTRA_TEXT)
            ?.trim()
            .orEmpty()
            .ifEmpty { "This is how I will read your replies." }
        val speaker = WearReplySpeaker(context.applicationContext, WearSettingsStore(context).read().voiceName)
        speaker.speak(text)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ speaker.shutdown() }, 30_000)
      }

      ACTION_DUMP -> {
        val s = runtime.state.value
        val last = s.messages.lastOrNull()
        android.util.Log.w(
          "WearDirect",
          "dump: connected=${s.connected} status=${s.status} busy=${s.busy} error=${s.error} selected=${s.selected?.name} " +
            "sessionKey=${s.sessionKey} agent=${s.sessionAgentId} sessions=${s.sessions.size} messages=${s.messages.size} " +
            "last=${last?.role}:${last?.text?.take(60)} runId=${s.runId} stream=${s.streamText?.take(40)} " +
            "pendingSend=${s.pendingSend != null} sending=${s.sending} sendUnknown=${s.sendUnknown}",
        )
      }
    }
  }

  companion object {
    const val ACTION_SEND = "ai.openclaw.wear.debug.SEND"
    const val ACTION_SETUP = "ai.openclaw.wear.debug.SETUP"
    const val ACTION_DUMP = "ai.openclaw.wear.debug.DUMP"
    const val ACTION_AUTO_SPEAK = "ai.openclaw.wear.debug.AUTO_SPEAK"
    const val ACTION_SET_VOICE = "ai.openclaw.wear.debug.SET_VOICE"
    const val ACTION_SPEAK = "ai.openclaw.wear.debug.SPEAK"
    const val ACTION_SET_GREETING = "ai.openclaw.wear.debug.SET_GREETING"
    const val EXTRA_NAME = "name"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_TEXT = "text"
    const val EXTRA_CODE = "code"
  }
}
