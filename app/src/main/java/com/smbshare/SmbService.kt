package com.smbshare

import android.app.Notification
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
        const val EXTRA_SECURE_MODE = "secure_mode"
        const val EXTRA_HOSTS_ALLOW = "hosts_allow"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processManager = SmbProcessManager()

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
                val secureMode = intent.getBooleanExtra(EXTRA_SECURE_MODE, false)
                val hostsAllow = intent.getStringExtra(EXTRA_HOSTS_ALLOW)
                startSmb(shareName, sharePath, workgroup, readOnly, secureMode, hostsAllow)
            }
            ACTION_STOP -> {
                stopSmb()
            }
            else -> {
                // intent == null: 进程被杀后系统用 START_REDELIVER_INTENT 重新拉起，
                // 但 redeliver 失败或异常路径下 intent 仍可能为 null。
                // startForegroundService 后必须在 5s 内 startForeground，否则抛异常，
                // 所以这里先把通知顶上再自我了结。
                startForeground(SmbShareApp.NOTIFICATION_ID, buildNotification(running = false, initializing = false))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // 用 REDELIVER_INTENT: 被杀后系统会带着原始 intent(含 share 配置) 重新投递，
        // 而非 START_STICKY 的 null intent。
        return START_REDELIVER_INTENT
    }

    private fun startSmb(
        shareName: String,
        sharePath: String,
        workgroup: String,
        readOnly: Boolean,
        secureMode: Boolean,
        hostsAllow: String?
    ) {
        startForeground(SmbShareApp.NOTIFICATION_ID, buildNotification(running = false, initializing = true))

        serviceScope.launch {
            try {
                val result = processManager.start(
                    shareName, sharePath, workgroup, readOnly, secureMode, hostsAllow
                )
                val running = result.success
                // updateNotification 涉及 NotificationManager，可在任意线程调用
                updateNotification(running)
                if (running) {
                    Log.i(TAG, "SMB started: ${result.message}")
                } else {
                    Log.e(TAG, "SMB start failed: ${result.message}")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB start error", e)
                stopSelf()
            }
        }
    }

    private fun stopSmb() {
        // stopForeground 在主线程立即执行，防止协程被取消时通知残留
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceScope.launch {
            try {
                processManager.stop()
                Log.i(TAG, "SMB stopped")
            } catch (e: Exception) {
                Log.e(TAG, "SMB stop error", e)
            }
            // stopSelf 是线程安全的，可在协程中调用
            stopSelf()
        }
    }

    /**
     * 构建前台服务通知（统一入口，消除重复代码）
     *
     * @param running  true = 运行中，false = 已停止/启动中
     * @param initializing true = 启动中文案，仅在 running=false 时有效
     */
    private fun buildNotification(running: Boolean, initializing: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
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

        val contentText = when {
            running -> getString(R.string.notification_running)
            initializing -> getString(R.string.notification_starting)
            else -> getString(R.string.notification_stopped)
        }

        return NotificationCompat.Builder(this, SmbShareApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(running)
            .setContentIntent(contentIntent)
            .apply {
                if (running) {
                    addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop_action), stopIntent)
                }
            }
            .build()
    }

    private fun updateNotification(running: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(SmbShareApp.NOTIFICATION_ID, buildNotification(running))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}
