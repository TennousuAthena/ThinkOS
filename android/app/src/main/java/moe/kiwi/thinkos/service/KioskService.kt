package moe.kiwi.thinkos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.ThinkApplication
import moe.kiwi.thinkos.shell.CrashRecovery
import moe.kiwi.thinkos.shell.KioskState
import moe.kiwi.thinkos.shell.ForegroundTask
import moe.kiwi.thinkos.shell.ManagementExit

class KioskService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (ManagementExit.isReleased(this@KioskService)) { stopSelf(); return }
            CrashRecovery.schedule(this@KioskService)
            // An Android app the user opened (ADR-035) keeps the screen while it plays sound: the time to come back starts over.
            if (KioskState.foreign && getSystemService(AudioManager::class.java).isMusicActive) {
                KioskState.extendForeign((application as ThinkApplication).runtime.system.appsReturnAfterMillis)
            }
            if (KioskState.shouldRecover()) {
                ForegroundTask.restore(this@KioskService, "watchdog return")
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (ManagementExit.isReleased(this)) { stopSelf(); return }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "runtime")
            .setSmallIcon(R.drawable.ic_thinkos).setContentTitle(getString(R.string.service_title))
            .setContentIntent(open)
            .setShowWhen(false).setOngoing(true).build())
        if (!(application as ThinkApplication).updateGuard.allowsRuntime) { stopSelf(); return }
        (application as ThinkApplication).runtime.start()
        CrashRecovery.schedule(this)
        if (KioskState.shouldRecover()) {
            ForegroundTask.restore(this, "watchdog recovery")
        }
        handler.postDelayed(watchdog, 30_000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ManagementExit.isReleased(this)) { stopSelf(); return START_NOT_STICKY }
        if (!(application as ThinkApplication).updateGuard.allowsRuntime) { stopSelf(); return START_NOT_STICKY }
        CrashRecovery.schedule(this)
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!ManagementExit.isReleased(this) && (application as ThinkApplication).updateGuard.allowsRuntime) CrashRecovery.schedule(this, 1_000)
    }
    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (!ManagementExit.isReleased(this) && (application as ThinkApplication).updateGuard.allowsRuntime) CrashRecovery.schedule(this, 1_000)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
