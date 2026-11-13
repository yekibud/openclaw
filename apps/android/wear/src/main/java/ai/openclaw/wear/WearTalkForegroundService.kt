package ai.openclaw.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs only while direct-mode Talk is active. Wear drops an app into ambient mode on wrist-down regardless of
 * keep-screen-on, and the system then silences a foreground-only microphone; a microphone foreground service (plus a
 * CPU wake lock and the app's visible hold) keeps capture and the Gateway connection alive until Talk ends.
 */
class WearTalkForegroundService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null
  private var holdingVisible = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    if (wakeLock == null) {
      wakeLock =
        getSystemService(PowerManager::class.java)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openclaw:talk")
          .apply { acquire(MAX_TALK_MILLIS) }
    }
    if (!holdingVisible) {
      holdingVisible = true
      (application as WearApplication).onActivityStarted()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    if (holdingVisible) {
      holdingVisible = false
      (application as WearApplication).onActivityStopped()
    }
    super.onDestroy()
  }

  private fun notification(): android.app.Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.talk), NotificationManager.IMPORTANCE_LOW))
    }
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.talk))
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setContentIntent(open)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "openclaw_talk"
    private const val NOTIFICATION_ID = 7301
    private const val MAX_TALK_MILLIS = 30 * 60_000L

    /** Must be called while the app is in the foreground (Talk starts from the visible UI). */
    internal fun start(context: Context) {
      runCatching { ContextCompat.startForegroundService(context, Intent(context, WearTalkForegroundService::class.java)) }
    }

    internal fun stop(context: Context) {
      context.stopService(Intent(context, WearTalkForegroundService::class.java))
    }
  }
}
