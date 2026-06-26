package com.smbshare.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbshare.R
import com.smbshare.ShellExecutor
import com.smbshare.SmbAssetDownloader
import com.smbshare.SmbConfigGenerator
import com.smbshare.SmbProcessManager
import com.smbshare.SmbService
import com.smbshare.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val processManager = SmbProcessManager()
    private val shellExecutor = ShellExecutor()
    private lateinit var downloader: SmbAssetDownloader

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvProgress: TextView
    private var statusIndicator: android.view.View? = null

    private val viewStatusIndicator: android.view.View?
        get() = findViewById(R.id.view_status_indicator)
    private val etShareName: com.google.android.material.textfield.TextInputEditText?
        get() = findViewById(R.id.et_share_name)
    private val etSharePath: com.google.android.material.textfield.TextInputEditText?
        get() = findViewById(R.id.et_share_path)
    private val etWorkgroup: com.google.android.material.textfield.TextInputEditText?
        get() = findViewById(R.id.et_workgroup)
    private val btnStart: com.google.android.material.button.MaterialButton?
        get() = findViewById(R.id.btn_start)
    private val btnStop: com.google.android.material.button.MaterialButton?
        get() = findViewById(R.id.btn_stop)
    private val btnInstall: com.google.android.material.button.MaterialButton?
        get() = findViewById(R.id.btn_install)
    private val progressBar: com.google.android.material.progressindicator.LinearProgressIndicator?
        get() = findViewById(R.id.progress_bar)
    private val layoutProgress: android.widget.LinearLayout?
        get() = findViewById(R.id.layout_progress)
    private val btnCopyLog: com.google.android.material.button.MaterialButton?
        get() = findViewById(R.id.btn_copy_log)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectInfo = findViewById(R.id.tv_connect_info)
        tvLog = findViewById(R.id.tv_log)
        tvProgress = findViewById(R.id.tv_progress)
        statusIndicator = viewStatusIndicator
        downloader = SmbAssetDownloader(this)

        setupClickListeners()
        checkRootAndUpdateStatus()
    }

    private fun setupClickListeners() {
        btnStart?.setOnClickListener {
            startSmbService()
        }
        btnStop?.setOnClickListener {
            stopSmbService()
        }
        btnInstall?.setOnClickListener {
            installDependencies()
        }
        btnCopyLog?.setOnClickListener {
            copyLogToClipboard()
        }
    }

    private fun startSmbService() {
        // 检查网络
        if (!NetworkUtils.isWifiConnected(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("网络错误")
                .setMessage("请先连接 WiFi 网络")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // 检查 SMB 是否已安装
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) { downloader.isSmbInstalled() }
            if (!installed) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("未安装")
                    .setMessage("SMB 服务组件未安装，请先点击\"安装 SMB 依赖\"")
                    .setPositiveButton("去安装") { _, _ -> installDependencies() }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            // 启动 foreground service
            val shareName = etShareName?.text?.toString() ?: SmbConfigGenerator.DEFAULT_SHARE_NAME
            val sharePath = etSharePath?.text?.toString() ?: SmbConfigGenerator.DEFAULT_SHARE_PATH
            val workgroup = etWorkgroup?.text?.toString() ?: SmbConfigGenerator.DEFAULT_WORKGROUP

            val intent = Intent(this@MainActivity, SmbService::class.java).apply {
                action = SmbService.ACTION_START
                putExtra(SmbService.EXTRA_SHARE_NAME, shareName)
                putExtra(SmbService.EXTRA_SHARE_PATH, sharePath)
                putExtra(SmbService.EXTRA_WORKGROUP, workgroup)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            appendLog("正在启动 SMB 服务…")

            // 延迟检查状态 + 输出诊断
            lifecycleScope.launch {
                kotlinx.coroutines.delay(4000)
                refreshStatus()

                val running = withContext(Dispatchers.IO) { processManager.isRunning() }
                if (running) {
                    val ip = withContext(Dispatchers.IO) {
                        NetworkUtils.getWifiIpAddress()
                            ?: NetworkUtils.getWifiIpFromManager(this@MainActivity)
                    }
                    appendLog("✅ SMB 服务运行中")
                    if (ip != null) {
                        appendLog("局域网地址: \\\\$ip\\$shareName")
                        appendLog("smb://$ip/$shareName")
                    } else {
                        appendLog("⚠️ 未获取到局域网 IP，请确认已连 WiFi")
                    }
                } else {
                    appendLog("❌ smbd0 未运行，诊断信息:")
                    val diag = withContext(Dispatchers.IO) { processManager.getStatusDiagnosis() }
                    diag.lines().filter { it.isNotBlank() }.forEach { appendLog("  $it") }
                }
            }
        }
    }

    private fun stopSmbService() {
        val intent = Intent(this, SmbService::class.java).apply {
            action = SmbService.ACTION_STOP
        }
        startService(intent)
        appendLog("正在停止 SMB 服务…")

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            refreshStatus()
        }
    }

    private fun installDependencies() {
        lifecycleScope.launch {
            try {
                layoutProgress?.visibility = android.view.View.VISIBLE
                progressBar?.progress = 0

                val success = downloader.installSmbDependencies(
                    onProgress = { message, progress ->
                        runOnUiThread {
                            tvProgress.text = message
                            progressBar?.progress = (progress * 100).toInt()
                            appendLog(message)
                        }
                    }
                )

                layoutProgress?.visibility = android.view.View.GONE

                if (success) {
                    Toast.makeText(this@MainActivity, "安装完成", Toast.LENGTH_SHORT).show()
                    appendLog("SMB 依赖安装成功")
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("安装失败")
                        .setMessage("无法释放内置 SMB 组件，请确认已授予 Root 权限")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (e: Exception) {
                layoutProgress?.visibility = android.view.View.GONE
                appendLog("安装出错: ${e.message}")
            }
        }
    }

    private fun checkRootAndUpdateStatus() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { shellExecutor.checkRoot() }
            if (!hasRoot) {
                appendLog("警告: 未检测到 Root 权限")
                Toast.makeText(this@MainActivity, R.string.root_required, Toast.LENGTH_LONG).show()
            } else {
                appendLog("Root 权限: OK")
            }
            refreshStatus()
        }
    }

    private suspend fun refreshStatus() {
        withContext(Dispatchers.IO) {
            val running = processManager.isRunning()
            val ip = NetworkUtils.getWifiIpAddress()
                ?: NetworkUtils.getWifiIpFromManager(this@MainActivity)

            withContext(Dispatchers.Main) {
                if (running) {
                    tvStatus.text = getString(R.string.smb_running)
                    tvStatus.setTextColor(getColor(com.google.android.material.R.color.material_dynamic_primary40))
                    statusIndicator?.setBackgroundResource(R.drawable.circle_green)
                    btnStart?.isEnabled = false
                    btnStop?.isEnabled = true

                    if (ip != null) {
                        tvDeviceIp.text = getString(R.string.device_ip, ip)
                        tvDeviceIp.visibility = android.view.View.VISIBLE

                        val shareName =
                            etShareName?.text?.toString() ?: SmbConfigGenerator.DEFAULT_SHARE_NAME
                        tvConnectInfo.text = getString(R.string.connect_info, ip, shareName)
                        tvConnectInfo.visibility = android.view.View.VISIBLE
                    }
                } else {
                    tvStatus.text = getString(R.string.smb_stopped)
                    tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                    statusIndicator?.setBackgroundResource(R.drawable.circle_red)
                    btnStart?.isEnabled = true
                    btnStop?.isEnabled = false
                    tvDeviceIp.visibility = android.view.View.INVISIBLE
                    tvConnectInfo.visibility = android.view.View.INVISIBLE
                }
            }
        }
    }

    private fun copyLogToClipboard() {
        val logText = tvLog.text?.toString() ?: ""
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SMB Share Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            tvLog.append("\n[$timestamp] $message")

            // 限制日志行数
            val lines = tvLog.text.lines()
            if (lines.size > 100) {
                tvLog.text = lines.takeLast(100).joinToString("\n")
            }
        }
    }
}