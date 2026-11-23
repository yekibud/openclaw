package ai.openclaw.wear

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class WearApplication : Application() {
  internal val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  internal val proxyClient: WearProxyClient by lazy {
    WearProxyClient.create(context = this, captureRoute = { directRuntime.capturePhoneProxy() })
  }

  internal val directRuntime: WearDirectRuntime by lazy { WearDirectRuntime(this, processScope) }

  internal val spokenLog: WearSpokenLog by lazy { WearSpokenLog(File(filesDir, WearSpokenLog.FILE_NAME)) }

  internal val gatewayRepository: WearGatewayRepository by lazy {
    WearGatewayRepository(proxyClient)
  }

  private val visibleActivities = AtomicInteger()

  private val mainHandler = Handler(Looper.getMainLooper())
  private val hideRunnable = Runnable { if (visibleActivities.get() == 0) directRuntime.setVisible(false) }

  internal fun onActivityStarted() {
    visibleActivities.incrementAndGet()
    mainHandler.removeCallbacks(hideRunnable)
    directRuntime.setVisible(true)
  }

  internal fun onActivityStopped() {
    val remaining = visibleActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    // System screens (RemoteInput keyboard, permission prompts) stop the activity for seconds; keep the
    // Gateway session alive across that hop so the send they return with is not parked as "not connected".
    // A screen that went dark is not such a hop: holding both sockets open for two minutes after each of ~90 daily
    // screen-offs kept the radio busy for hours (battery stats 2026-11-17), so retire sooner unless Talk is running.
    if (remaining == 0) {
      val screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false
      val grace = if (screenOff && !directRuntime.state.value.talk.active) SCREEN_OFF_HIDE_GRACE_MS else HIDE_GRACE_MS
      mainHandler.postDelayed(hideRunnable, grace)
    }
  }

  internal fun isActivityVisible(): Boolean = visibleActivities.get() > 0

  private companion object {
    const val HIDE_GRACE_MS = 120_000L
    const val SCREEN_OFF_HIDE_GRACE_MS = 15_000L
  }
}
