package moe.kiwi.thinkos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.ThinkApplication
import moe.kiwi.thinkos.shell.CrashRecovery
import moe.kiwi.thinkos.shell.KioskState

class KioskService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            CrashRecovery.schedule(this@KioskService)
            if (KioskState.shouldRecover()) {
                startActivity(Intent(this@KioskService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "runtime")
            .setSmallIcon(R.drawable.ic_thinkos).setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_body)).setContentIntent(open)
            .setShowWhen(false).setOngoing(true).build())
        (application as ThinkApplication).runtime.start()
        CrashRecovery.schedule(this)
        if (KioskState.shouldRecover()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        handler.postDelayed(watchdog, 30_000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashRecovery.schedule(this)
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { CrashRecovery.schedule(this, 1_000) }
    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        CrashRecovery.schedule(this, 1_000)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
