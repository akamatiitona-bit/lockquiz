package com.example.lockquiz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class LockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlay: QuizOverlay

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT && Prefs(c).enabled) showQuiz()
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = QuizOverlay(this, scope) {
            if (!Prefs(this).enabled) stopSelf()
        }
        startAsForeground()
        // USER_PRESENT = 端末のロック(PIN/指紋など)を解除した直後
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_NOW) showQuiz()
        return START_STICKY
    }

    private fun showQuiz() {
        if (Settings.canDrawOverlays(this)) overlay.show()
    }

    private fun startAsForeground() {
        val channelId = "lockquiz"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "ロッククイズ", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("ロッククイズ 動作中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        overlay.forceClose()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW_NOW = "com.example.lockquiz.SHOW_NOW"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).enabled) {
            ContextCompat.startForegroundService(context, Intent(context, LockService::class.java))
        }
    }
}
