package com.example.osimsofa

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the Bluetooth link alive while the app is not on screen (improve-plan B01): a
 * `connectedDevice` foreground service, started while the app is in front (as Android 12+ requires)
 * once the sofa is operable, stopped when the link closes. Its notification shows the sofa's state
 * (e.g. 「躺平・全身按摩 進行中・約剩 12 分鐘」), the latest outcome, and a 停止 action.
 */
class SofaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SofaApplication
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(this, app.controller.lastEvent?.text), type)
            running = true
        } catch (e: Exception) {
            app.conn.log("前景服務無法啟動：$e")
            starting = false
            stopSelf()
            return START_NOT_STICKY
        }
        starting = false
        // The link may have closed between startForegroundService() and here.
        if (stopAsked || !app.conn.operable) {
            stopAsked = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        starting = false
        super.onDestroy()
    }

    /** The notification's 停止. A broadcast, so it works whatever the app's state. */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (context.applicationContext as SofaApplication).controller.stopAll(SofaController.Source.NOTIFICATION)
        }
    }

    companion object {
        private const val CHANNEL = "sofa"
        private const val NOTIFICATION_ID = 1
        @Volatile private var running = false      // in the foreground
        @Volatile private var starting = false     // startForegroundService() called, onStartCommand not yet
        @Volatile private var stopAsked = false

        fun start(ctx: Context) {
            stopAsked = false
            try {
                starting = true
                ContextCompat.startForegroundService(ctx, Intent(ctx, SofaService::class.java))
            } catch (e: Exception) {
                starting = false
                (ctx.applicationContext as SofaApplication).conn.log("前景服務無法啟動：$e")
            }
        }

        /** Android requires startForeground() after startForegroundService(): a stop that comes
         *  before the service got there is carried out right after it, not by stopService(). */
        fun stop(ctx: Context) {
            if (starting && !running) { stopAsked = true; return }
            ctx.stopService(Intent(ctx, SofaService::class.java))
        }

        /** Refresh the notification: the sofa's state as the title, [text] (the latest outcome) below. */
        fun update(ctx: Context, text: String?) {
            if (!running) return
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, build(ctx, text))
        }

        private fun build(ctx: Context, text: String?): android.app.Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "沙發連線", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "連線中顯示沙發目前的狀態，可在此停止" })
            }
            val app = ctx.applicationContext as SofaApplication
            val state = app.controller.summary(app.conn.status)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, StopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sofa)
                .setContentTitle(state)                                   // what the sofa is doing now
                .setContentText(text ?: "可在此停止沙發")                  // the latest outcome
                .setStyle(NotificationCompat.BigTextStyle().bigText(text ?: "可在此停止沙發"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "停止", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
