package com.smbshare

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smbshare.ui.MainActivity
import kotlinx.coroutines.*

/**
 * SMB Foreground Service
 * 在后台维持 SMB 服务的运行，并在通知栏显示状态
 */
class SmbService : Service() {

    companion object {
        private const val TAG = "SmbService"
        const val ACTION_START = "com.smbshare.action.START_SMB"
        const val ACTION_STOP = "com.smbshare.action.STOP_SMB"
        const val EXTRA_SHARE_NAME = "share_name"
        const val EXTRA_SHARE_PATH = "share_path"
        const val EXTRA_WORKGROUP = "workgroup"
        const val EXTRA_READ_ONLY = "read_only"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processManager = SmbProcessManager()
    private var isSmbRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): SmbService = this@SmbService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val shareName = intent.getStringExtra(EXTRA_SHARE_NAME)
                    ?: SmbConfigGenerator.DEFAULT_SHARE_NAME
                val sharePath = intent.getStringExtra(EXTRA_SHARE_PATH)
                    ?: SmbConfigGenerator.DEFAULT_SHARE_PATH
                val workgroup = intent.getStringExtra(EXTRA_WORKGROUP)
                    ?: SmbConfigGenerator.DEFAULT_WORKGROUP
                val readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
                startSmb(shareName, sharePath, workgroup, readOnly)
            }
            ACTION_STOP -> {
                stopSmb()
            }
            else -> {
                // intent == null: 进程被杀后系统用 START_REDELIVER_INTENT 重新拉起,
                // 但 redeliver 失败或异常路径下 intent 仍可能为 null。
                // startForegroundService 后必须在 5s 内 startForeground, 否则
                // 抛 RemoteServiceException / ANR, 所以这里先把通知顶上再自我了结。
                startForegroundNotification()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // 用 REDELIVER_INTENT: 被杀后系统会带着原始 intent(含 share 配置) 重新投递,
        // 而非 START_STICKY 的 null intent。
        return START_REDELIVER_INTENT
    }

    private fun startSmb(shareName: String, sharePath: String, workgroup: String, readOnly: Boolean) {
        startForegroundNotification()

        serviceScope.launch {
            try {
                val result = processManager.start(shareName, sharePath, workgroup, readOnly)
                if (result.success) {
                    isSmbRunning = true
                    updateNotification(true)
                    Log.i(TAG, "SMB started: ${result.message}")
                } else {
                    isSmbRunning = false
                    updateNotification(false)
                    Log.e(TAG, "SMB start failed: ${result.message}")
                    // 如果启动失败，停止服务
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB start error", e)
                stopSelf()
            }
        }
    }

    private fun stopSmb() {
        serviceScope.launch {
            try {
                processManager.stop()
                isSmbRunning = false
                Log.i(TAG, "SMB stopped")
            } catch (e: Exception) {
                Log.e(TAG, "SMB stop error", e)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SmbService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SmbShareApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMB 文件共享")
            .setContentText("SMB 服务启动中…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()

        startForeground(SmbShareApp.NOTIFICATION_ID, notification)
    }

    private fun updateNotification(running: Boolean) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SmbService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SmbShareApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMB 文件共享")
            .setContentText(
                if (running) "SMB 服务运行中 — 设备可被局域网其他设备访问"
                else "SMB 服务已停止"
            )
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(running)
            .setContentIntent(pendingIntent)
            .apply {
                if (running) {
                    addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
                }
            }
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(SmbShareApp.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}