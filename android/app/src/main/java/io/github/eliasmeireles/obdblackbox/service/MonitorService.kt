package io.github.eliasmeireles.obdblackbox.service

import io.github.eliasmeireles.obdblackbox.i18n.AppLocales
import io.github.eliasmeireles.obdblackbox.i18n.text
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
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.ObdBlackBoxApp
import io.github.eliasmeireles.obdblackbox.ui.MainActivity

/** Keeps reading, recording and uploading while the screen is off or the app is in background. */
class MonitorService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdblackbox:monitor")
            .apply { acquire() }
        (application as ObdBlackBoxApp).container.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, text(R.string.msg_monitoramento), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(text(R.string.app_name))
            .setContentText(if ((application as ObdBlackBoxApp).container.uploadPolicy.enabled) text(R.string.msg_gravando_e_enviando_os_dados_do_carro) else text(R.string.msg_gravando_os_dados_do_carro_no_celular))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }
    }
}
