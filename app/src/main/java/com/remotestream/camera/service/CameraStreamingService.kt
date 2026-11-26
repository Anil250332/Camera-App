package com.remotestream.camera.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.remotestream.camera.CameraApp
import com.remotestream.camera.MainActivity
import com.remotestream.camera.R
import com.remotestream.camera.data.model.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class CameraStreamingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamGuard::SocketWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm?.createWifiLock(wifiMode, "CamGuard::WifiLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreamingService()
                return START_NOT_STICKY
            }
            null, ACTION_STANDBY -> {
                val state = (application as? CameraApp)?.sessionManager?.streamState?.value
                if (state != StreamState.STREAMING) {
                    startStandbyForeground()
                }
                // Only connect if not already connected (handles START_STICKY restarts cleanly)
                (application as? CameraApp)?.sessionManager?.connectIfNeeded()
                return START_STICKY
            }
            else -> {
                val viewerName = intent.getStringExtra(EXTRA_VIEWER_NAME) ?: "Authorized Viewer"
                val ok = startForegroundStreaming(viewerName)
                pendingForegroundReady?.complete(ok)
                pendingForegroundReady = null
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Do NOT downgrade an active stream to standby DATA_SYNC when user swipes from Recents
        val state = (application as? CameraApp)?.sessionManager?.streamState?.value
        if (state != StreamState.STREAMING) {
            startStandbyForeground()
        }
    }

    private fun startStandbyForeground() {
        createNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this, 1002,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamGuard • Ready")
            .setContentText("Connected in standby — waiting for trusted viewer")
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
    }

    private fun startForegroundStreaming(viewerName: String): Boolean {
        createNotificationChannel()

        val stopIntent = Intent(this, CameraStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1001, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openActivityPendingIntent = PendingIntent.getActivity(
            this, 1002,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 CamGuard Live Streaming")
            .setContentText("Streaming live video/audio to: $viewerName")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openActivityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            // Android 14+ blocks promoting a closed background app to CAMERA/MICROPHONE FGS; stay online in standby
            startStandbyForeground()
            false
        }
    }

    private fun stopStreamingService() {
        sendBroadcast(Intent(BROADCAST_STOP_STREAM))
        startStandbyForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingForegroundReady?.complete(false)
        pendingForegroundReady = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "camera_streaming_channel"
        const val NOTIFICATION_ID = 4001
        const val ACTION_STOP = "com.remotestream.camera.ACTION_STOP"
        const val ACTION_STANDBY = "com.remotestream.camera.ACTION_STANDBY"
        const val BROADCAST_STOP_STREAM = "com.remotestream.camera.STOP_STREAM"
        const val EXTRA_VIEWER_NAME = "extra_viewer_name"

        @Volatile
        private var pendingForegroundReady: CompletableDeferred<Boolean>? = null

        fun startStandby(context: Context) {
            try {
                val intent = Intent(context, CameraStreamingService::class.java).apply {
                    action = ACTION_STANDBY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        suspend fun startAndAwait(context: Context, viewerName: String): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            pendingForegroundReady = deferred
            return try {
                val intent = Intent(context, CameraStreamingService::class.java).apply {
                    putExtra(EXTRA_VIEWER_NAME, viewerName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                withTimeoutOrNull(2500L) { deferred.await() } ?: false
            } catch (_: Exception) {
                pendingForegroundReady = null
                false
            }
        }

        fun start(context: Context, viewerName: String) {
            try {
                val intent = Intent(context, CameraStreamingService::class.java).apply {
                    putExtra(EXTRA_VIEWER_NAME, viewerName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraStreamingService::class.java))
        }
    }
}
