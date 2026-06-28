package com.smbshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * SMB 进程管理器
 * 管理 smbd0 的生命周期
 *
 * smbd0 是 SambaDroid 编译的 Samba 4.x SMB 服务端守护进程。
 * 它不依赖 dbus-daemon (二进制自带所需库)，RPATH 已烧死为
 * /data/zb:/data/zb/samba，解压到 /data/zb 即可直接运行。
 */
class SmbProcessManager {

    private val shellExecutor = ShellExecutor()

    /**
     * 启动 SMB 服务
     * 1. 生成 smb.conf
     * 2. 启动 smbd0 (-D daemon 模式)
     *
     * @param shareName 共享名称
     * @param sharePath 共享路径
     * @param workgroup 工作组名
     */
    suspend fun start(
        shareName: String = SmbConfigGenerator.DEFAULT_SHARE_NAME,
        sharePath: String = SmbConfigGenerator.DEFAULT_SHARE_PATH,
        workgroup: String = SmbConfigGenerator.DEFAULT_WORKGROUP,
        readOnly: Boolean = false,
        secureMode: Boolean = false,
        hostsAllow: String? = null
    ): StartResult {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 检查 root
                if (!shellExecutor.checkRoot()) {
                    return@withContext StartResult(false, "需要 Root 权限")
                }

                // 2. 检查二进制文件是否存在
                if (!checkBinaryExists()) {
                    return@withContext StartResult(
                        false,
                        "SMB 二进制文件未安装，请先安装依赖"
                    )
                }

                // 3. 先停止已有的实例
                stopExistingInstances()

                // 4. 创建必要目录
                createDirectories()

                // 5. 生成 smb.conf
                val configWritten = SmbConfigGenerator.writeConfigFile(
                    shareName = shareName,
                    sharePath = sharePath,
                    workgroup = workgroup,
                    readOnly = readOnly,
                    secureMode = secureMode,
                    hostsAllow = hostsAllow,
                    shellExecutor = shellExecutor
                )
                if (!configWritten) {
                    return@withContext StartResult(false, "写入配置文件失败")
                }

                // 6. 启动 smbd0 (对应原 app libourom.so 中的命令: `export TMPDIR=/data/zb/lib; smbd0 $@`)
                //  - smbd0 不依赖 dbus-daemon (SambaDroid 二进制自带所需库, tgz 里也没有 dbus)
                //  - smbd0 的 RPATH 已烧死为 /data/zb:/data/zb/samba, 动态库无需 LD_LIBRARY_PATH
                //  - smbd0 -D 自身 fork 成 daemon; </dev/null >/dev/null 2>&1 切断 fd 继承,
                //    否则 ShellExecutor 的 readLine() 循环会一直等管道 EOF 而阻塞
                val startScript = buildString {
                    append("export TMPDIR=${SmbConfigGenerator.SMB_LIB_DIR}\n")
                    append("${SmbConfigGenerator.SMB_EXECUTABLE} -D -s ${SmbConfigGenerator.DEFAULT_CONFIG_PATH} </dev/null >/dev/null 2>&1\n")
                }

                // smbd0 -D 自身 fork 成 daemon 后立即返回, 直接 exitCode 不可靠 (fd 已重定向),
                // 真正的成功判定靠下方 pidof, 故此处不消费返回值。
                shellExecutor.execute(startScript, asRoot = true)

                // 7. 等待并验证是否成功启动
                // 使用协程 delay 而非 shell sleep: 正确挂起协程而不占用 IO 线程
                delay(2000)
                if (isRunning()) {
                    StartResult(
                        success = true,
                        message = "SMB 服务已启动",
                        shareDetails = "\\\\<device-ip>\\$shareName"
                    )
                } else {
                    val status = getStatusDiagnosis()
                    StartResult(false, "smbd 启动失败", status)
                }
            } catch (e: Exception) {
                StartResult(false, "启动失败: ${e.message}")
            }
        }
    }

    /**
     * 停止 SMB 服务
     * 停止 smbd0，清理 smb.conf
     */
    suspend fun stop(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                stopExistingInstances()

                // 清理配置文件 (可选，保留的话下次可直接启动); rm -f 必成功, 无需消费返回值
                shellExecutor.execute(
                    "rm -f \"${SmbConfigGenerator.DEFAULT_CONFIG_PATH}\"",
                    asRoot = true
                )

                !isRunning()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 检查 SMB 服务是否在运行
     */
    suspend fun isRunning(): Boolean {
        return shellExecutor.isProcessRunning("smbd0")
    }

    /**
     * 获取诊断信息 (用于调试启动失败 / UI 显示)
     */
    suspend fun getStatusDiagnosis(): String {
        val sb = StringBuilder()

        // 检查文件是否存在
        val checkFiles = listOf(
            SmbConfigGenerator.SMB_EXECUTABLE,
            SmbConfigGenerator.DEFAULT_CONFIG_PATH
        )
        for (file in checkFiles) {
            val result = shellExecutor.execute("test -f \"$file\" && echo OK || echo MISSING", asRoot = true)
            sb.appendLine("$file: ${result.stdout?.trim() ?: "?"}")
        }

        // 用 testparm 校验配置语法 (只解析配置, 不 bind 端口, 不会与已有 smbd0 冲突)
        val testparm = "${SmbConfigGenerator.SMB_INSTALL_DIR}/testparm"
        val parmErr = shellExecutor.execute(
            "test -f \"$testparm\" && \"$testparm\" -s \"${SmbConfigGenerator.DEFAULT_CONFIG_PATH}\" 2>&1 | head -8",
            asRoot = true
        )
        if (!parmErr.output.isNullOrBlank()) {
            sb.appendLine("testparm: ${parmErr.output.trim()}")
        }

        // 检查端口
        val ports = listOf(139, 445)
        for (port in ports) {
            val result = shellExecutor.execute(
                "netstat -tlnp 2>/dev/null | grep \":$port \" || echo 'not listening'",
                asRoot = true
            )
            sb.appendLine("Port $port: ${result.stdout?.trim() ?: "?"}")
        }

        return sb.toString()
    }

    /**
     * 停止已有的 smbd0 实例
     */
    private suspend fun stopExistingInstances() {
        val busybox = findBusybox()
        // killall 与等待合并为单条命令: 一次 su 调用, delay 不占 IO 线程
        shellExecutor.execute("$busybox killall -9 smbd0 2>/dev/null || true", asRoot = true)
        delay(1000)
    }

    private suspend fun checkBinaryExists(): Boolean {
        val result = shellExecutor.execute(
            "test -f \"${SmbConfigGenerator.SMB_EXECUTABLE}\" && echo all_ok",
            asRoot = true
        )
        return result.stdout?.contains("all_ok") == true
    }

    private suspend fun createDirectories() {
        // 合并为单条命令: 一次 su 调用即可, 省掉多次 fork/exec 的 root 提权开销
        val dirs = listOf(
            SmbConfigGenerator.SMB_INSTALL_DIR,
            SmbConfigGenerator.SMB_LIB_DIR,
            SmbConfigGenerator.SMB_CONFIG_DIR
        ).joinToString(" ") { "\"$it\"" }
        shellExecutor.execute("mkdir -p $dirs", asRoot = true)
    }

    private suspend fun findBusybox(): String {
        return BusyboxLocator.find(shellExecutor)
    }

    data class StartResult(
        val success: Boolean,
        val message: String,
        val shareDetails: String? = null
    )
}
