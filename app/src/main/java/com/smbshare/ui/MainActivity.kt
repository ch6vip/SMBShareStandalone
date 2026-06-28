package com.smbshare.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
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
        private const val MAX_LOG_LINES = 100
    }

    private val processManager = SmbProcessManager()
    private val shellExecutor = ShellExecutor()
    private lateinit var downloader: SmbAssetDownloader

    // Android 13+ 通知权限申请器; 不授予则前台服务通知不显示
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不阻断主流程, 仅通知栏不显示 */ }

    // 视图引用在 onCreate 中一次性绑定，避免每次访问都 findViewById
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvProgress: TextView
    private lateinit var etShareName: TextInputEditText
    private lateinit var etSharePath: TextInputEditText
    private lateinit var etWorkgroup: TextInputEditText
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var layoutProgress: android.widget.LinearLayout
    private lateinit var switchReadOnly: MaterialSwitch
    private lateinit var switchSecureMode: MaterialSwitch
    private var statusIndicator: android.view.View? = null

    // 日志行数计数器，避免每次 appendLog 都全量 text.lines() 扫描
    private var logLineCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 一次性绑定所有视图
        tvStatus = findViewById(R.id.tv_status)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectInfo = findViewById(R.id.tv_connect_info)
        tvLog = findViewById(R.id.tv_log)
        tvProgress = findViewById(R.id.tv_progress)
        statusIndicator = findViewById(R.id.view_status_indicator)
        etShareName = findViewById(R.id.et_share_name)
        etSharePath = findViewById(R.id.et_share_path)
        etWorkgroup = findViewById(R.id.et_workgroup)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnInstall = findViewById(R.id.btn_install)
        btnCopyLog = findViewById(R.id.btn_copy_log)
        progressBar = findViewById(R.id.progress_bar)
        layoutProgress = findViewById(R.id.layout_progress)
        switchReadOnly = findViewById(R.id.switch_read_only)
        switchSecureMode = findViewById(R.id.switch_secure_mode)

        downloader = SmbAssetDownloader(this)

        setupClickListeners()
        requestNotificationPermissionIfNeeded()
        checkRootAndUpdateStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener { startSmbService() }
        btnStop.setOnClickListener { stopSmbService() }
        btnInstall.setOnClickListener { installDependencies() }
        btnCopyLog.setOnClickListener { copyLogToClipboard() }
    }

    private fun startSmbService() {
        // 只要能拿到局域网 IP 即可启动 (WiFi / 以太网 / USB 网络共享都算),
        // 不强制 WiFi: smbd 本身不关心链路类型。
        val lanIp = NetworkUtils.getWifiIpAddress() ?: NetworkUtils.getWifiIpFromManager(this)
        if (lanIp == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("网络错误")
                .setMessage("未检测到局域网地址，请先连接 WiFi 或以太网")
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
            // takeIf { isNotBlank() }: ?: 只挡 null, 挡不住空串/纯空格,
            // 否则用户清空输入框会生成非法的 [] share 名导致 smbd 异常
            val shareName = etShareName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: SmbConfigGenerator.DEFAULT_SHARE_NAME
            val sharePath = etSharePath.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: SmbConfigGenerator.DEFAULT_SHARE_PATH
            val workgroup = etWorkgroup.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: SmbConfigGenerator.DEFAULT_WORKGROUP
            val readOnly = switchReadOnly.isChecked
            val secureMode = switchSecureMode.isChecked
            // 安全模式开启时，由当前局域网 IP 推导 /24 网段作为 hosts allow，限定来源
            val hostsAllow = if (secureMode) SmbConfigGenerator.lanPrefixFromIp(lanIp) else null

            val intent = Intent(this@MainActivity, SmbService::class.java).apply {
                action = SmbService.ACTION_START
                putExtra(SmbService.EXTRA_SHARE_NAME, shareName)
                putExtra(SmbService.EXTRA_SHARE_PATH, sharePath)
                putExtra(SmbService.EXTRA_WORKGROUP, workgroup)
                putExtra(SmbService.EXTRA_READ_ONLY, readOnly)
                putExtra(SmbService.EXTRA_SECURE_MODE, secureMode)
                if (hostsAllow != null) putExtra(SmbService.EXTRA_HOSTS_ALLOW, hostsAllow)
            }
            if (readOnly) appendLog("只读模式: 客户端不可写入")
            if (secureMode) {
                appendLog("安全模式: 强制加密传输")
                if (hostsAllow != null) {
                    appendLog("安全模式: 仅允许网段 $hostsAllow 访问")
                } else {
                    appendLog("安全模式: 未能由 IP 推导网段, 跳过 hosts allow")
                }
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
                    // IP 可能在延迟期间变更，重新获取一次
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
                layoutProgress.visibility = android.view.View.VISIBLE
                progressBar.progress = 0

                val success = downloader.installSmbDependencies(
                    onProgress = { message, progress ->
                        runOnUiThread {
                            tvProgress.text = message
                            progressBar.progress = (progress * 100).toInt()
                            appendLog(message)
                        }
                    }
                )

                layoutProgress.visibility = android.view.View.GONE

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
                layoutProgress.visibility = android.view.View.GONE
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
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true

                    if (ip != null) {
                        tvDeviceIp.text = getString(R.string.device_ip, ip)
                        tvDeviceIp.visibility = android.view.View.VISIBLE

                        val shareName = etShareName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                            ?: SmbConfigGenerator.DEFAULT_SHARE_NAME
                        tvConnectInfo.text = getString(R.string.connect_info, ip, shareName)
                        tvConnectInfo.visibility = android.view.View.VISIBLE
                    }
                } else {
                    tvStatus.text = getString(R.string.smb_stopped)
                    tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                    statusIndicator?.setBackgroundResource(R.drawable.circle_red)
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
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
            logLineCount++

            // 超出限制时裁剪旧日志（计数器比每次 text.lines() 全量扫描更高效）
            if (logLineCount > MAX_LOG_LINES) {
                val lines = tvLog.text.split("\n")
                tvLog.text = lines.takeLast(MAX_LOG_LINES).joinToString("\n")
                logLineCount = MAX_LOG_LINES
            }
        }
    }
}
