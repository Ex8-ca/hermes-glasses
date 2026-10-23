package com.chillygeek.hermesglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service that keeps the glasses conversation alive while the phone
 * is locked or in your pocket.
 *
 * Without this the DAT session dies the moment the Activity is destroyed, which
 * makes the whole thing unusable in practice — you would have to hold the phone
 * and keep the screen on to talk to your glasses.
 *
 * It also holds a partial wake lock, because Bluetooth audio routing and the
 * camera stream both need the CPU to stay awake.
 */
class GlassesService : Service() {

  companion object {
    private const val TAG = "GlassesService"
    private const val CHANNEL_ID = "glasses_channel"
    private const val NOTIFICATION_ID = 2001
    private const val WAKELOCK_TAG = "HermesGlasses::Service"
    private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    private const val ACTION_STOP = "com.chillygeek.hermesglasses.STOP"

    fun start(context: Context) {
      val intent = Intent(context, GlassesService::class.java).apply { `package` = context.packageName }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      // Route the stop through onStartCommand with a STOP action rather than
      // stopService(): once startForegroundService() has been called the system
      // requires startForeground() to follow, and stopping mid-start tears the
      // service down in a way that logs an error.
      val intent = Intent(context, GlassesService::class.java).apply {
        `package` = context.packageName
        action = ACTION_STOP
      }
      context.startService(intent)
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      Log.i(TAG, "stop requested")
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    startForeground(
        NOTIFICATION_ID,
        buildNotification(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
          0
        },
    )

    // Make sure the runtime exists even if the service was started directly.
    GlassesRuntime.init(applicationContext)

    Log.i(TAG, "foreground service running")
    return START_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  // ------------------------------------------------------------------ notification

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
        NotificationChannel(CHANNEL_ID, "Glasses conversation", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Keeps the Hermes glasses conversation running"
          setShowBadge(false)
        }
    val nm = getSystemService(NotificationManager::class.java)
    nm?.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val contentIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { `package` = packageName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    val stopIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, GlassesService::class.java).apply {
              `package` = packageName
              action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Notification.Builder(this, CHANNEL_ID)
        } else {
          @Suppress("DEPRECATION") Notification.Builder(this)
        }

    return builder
        .setContentTitle("Hermes Glasses")
        .setContentText("Listening — talk to your glasses")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(contentIntent)
        .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
        .setOngoing(true)
        .build()
  }

  // ------------------------------------------------------------------ wakelock

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
          }
      Log.i(TAG, "partial wake lock acquired")
    } catch (e: Exception) {
      Log.w(TAG, "could not acquire wake lock: ${e.message}")
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.takeIf { it.isHeld }?.release()
    } catch (e: Exception) {
      Log.w(TAG, "could not release wake lock: ${e.message}")
    }
    wakeLock = null
  }
}
