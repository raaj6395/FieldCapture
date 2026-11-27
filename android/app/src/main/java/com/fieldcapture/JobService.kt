package com.fieldcapture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class JobService : Service() {

  private val scope = MainScope()
  private var wake: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active job", NotificationManager.IMPORTANCE_LOW))
    startForeground(1, note("Starting"), types())
    wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldcapture:job").apply { acquire(4 * 60 * 60 * 1000L) }
    val js = (application as FieldCaptureApp).session
    scope.launch { js.state.collect { nm.notify(1, note("Job ${js.job.id} - $it")) } }
  }

  private fun types(): Int {
    fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    var t = 0
    if (has(Manifest.permission.BLUETOOTH_CONNECT)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (has(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    if (has(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    return t
  }

  private fun note(text: String): Notification =
      Notification.Builder(this, CHANNEL)
          .setContentTitle("FieldCapture")
          .setContentText(text)
          .setSmallIcon(android.R.drawable.ic_menu_camera)
          .setOngoing(true)
          .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
          .build()

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

  override fun onDestroy() {
    scope.cancel()
    wake?.takeIf { it.isHeld }?.release()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  companion object {
    private const val CHANNEL = "job"

    fun start(ctx: Context) {
      runCatching { ctx.startForegroundService(Intent(ctx, JobService::class.java)) }
    }
  }
}
