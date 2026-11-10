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
 */
class WearE2eReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    if (intent.action != ACTION_SEND) return
    val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
    if (text.isEmpty()) return
    val runtime = (context.applicationContext as WearApplication).directRuntime
    runtime.send(text, runtime.inputOwner())
  }

  companion object {
    const val ACTION_SEND = "ai.openclaw.wear.debug.SEND"
    const val EXTRA_TEXT = "text"
  }
}
