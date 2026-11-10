package ai.openclaw.wear

import android.app.Application
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger

class WearApplication : Application() {
  internal val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  internal val proxyClient: WearProxyClient by lazy {
    WearProxyClient.create(context = this, captureRoute = { directRuntime.capturePhoneProxy() })
  }

  internal val directRuntime: WearDirectRuntime by lazy { WearDirectRuntime(this, processScope) }

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
    if (remaining == 0) mainHandler.postDelayed(hideRunnable, HIDE_GRACE_MS)
  }

  internal fun isActivityVisible(): Boolean = visibleActivities.get() > 0

  private companion object {
    const val HIDE_GRACE_MS = 120_000L
  }
}
