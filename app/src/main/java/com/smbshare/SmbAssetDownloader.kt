package com.smbshare

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * SMB 资源安装器
 * 全部依赖 (smb3.tgz) 已内置在 APK assets 中，直接释放安装，不依赖任何网络。
 */
class SmbAssetDownloader(private val context: Context) {

    private val shellExecutor = ShellExecutor()

    // ===================================================================
    //  Assets 安装 (主路径)
    // ===================================================================

    /**
     * 从 APK assets 安装 SMB 依赖
     * 将内置的 smb3.tgz 释放到 /data/local/tmp 然后解压到 /data/zb/
     */
    suspend fun installFromAssets(
        onProgress: ((String, Float) -> Unit)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onProgress?.invoke("创建目录…", 0f)

                // 创建工作目录
                val dirs = listOf(
                    SmbConfigGenerator.SMB_INSTALL_DIR,
                    SmbConfigGenerator.SMB_LIB_DIR,
                    SmbConfigGenerator.SMB_CONFIG_DIR
                )
                for (dir in dirs) {
                    shellExecutor.execute("mkdir -p $dir", asRoot = true)
                }

                // 从 assets 复制 smb3.tgz 到 /data/local/tmp
                onProgress?.invoke("释放 smb3.tgz…", 0.1f)
                val tmpPath = "/data/local/tmp/smb3.tgz"
                val copied = copyAssetToFile("smb3.tgz", tmpPath)
                if (!copied) {
                    onProgress?.invoke("assets 中未找到 smb3.tgz", 0f)
                    return@withContext false
                }

                // 停止旧的服务进程
                onProgress?.invoke("停止旧服务…", 0.6f)
                stopExistingServices()

                // 解压到 /data/ (tgz 内包含 zb/ 前缀)
                onProgress?.invoke("解压中…", 0.7f)
                val busyboxPath = findBusybox()
                val extractResult = shellExecutor.execute(
                    "$busyboxPath tar -xzf \"$tmpPath\" -C /data/",
                    asRoot = true
                )
                if (!extractResult.isSuccess) {
                    onProgress?.invoke("解压失败", 0f)
                    shellExecutor.execute("rm -f $tmpPath", asRoot = true)
                    return@withContext false
                }

                // 设置执行权限
                //  - smbd0 主程序
                //  - ld-linux-aarch64.so.1 是动态链接器, 由内核作为 interpreter 加载, 需 exec 位
                onProgress?.invoke("设置权限…", 0.85f)
                makeExecutable(SmbConfigGenerator.SMB_EXECUTABLE)
                makeExecutable("${SmbConfigGenerator.SMB_INSTALL_DIR}/ld-linux-aarch64.so.1")

                // 清理临时文件
                shellExecutor.execute("rm -f $tmpPath", asRoot = true)

                onProgress?.invoke("安装完成", 1f)
                true
            } catch (e: Exception) {
                onProgress?.invoke("安装出错: ${e.message}", 0f)
                false
            }
        }
    }

    /**
     * 将 assets 中的文件复制到设备路径 (需要 root 写入 /data)
     */
    private suspend fun copyAssetToFile(assetName: String, destPath: String): Boolean {
        return try {
            val inputStream = context.assets.open(assetName)
            // 先写到 app 私有目录 (无需 root), 再用 root cat 到目标路径 (跨用户写 /data)
            val tmpFile = File(context.cacheDir, assetName)
            val outputStream = FileOutputStream(tmpFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // 用 root mv 到目标路径
            val result = shellExecutor.execute(
                "cat ${tmpFile.absolutePath} > $destPath && chmod 644 $destPath",
                asRoot = true
            )
            tmpFile.delete()
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    // ===================================================================
    //  辅助
    // ===================================================================

    /**
     * 设置二进制文件的可执行权限
     */
    suspend fun makeExecutable(filePath: String): Boolean {
        val result = shellExecutor.execute("chmod 755 $filePath", asRoot = true)
        return result.isSuccess
    }

    /**
     * 查找 busybox 路径 (委托共享 [BusyboxLocator], 候选列表唯一事实源)
     */
    private suspend fun findBusybox(): String = BusyboxLocator.find(shellExecutor)

    /**
     * 检查 smb3.tgz 是否已安装
     */
    suspend fun isSmbInstalled(): Boolean {
        val result = shellExecutor.execute(
            "test -f ${SmbConfigGenerator.SMB_EXECUTABLE} && echo installed",
            asRoot = true
        )
        return result.stdout?.contains("installed") == true
    }

    /**
     * 一键安装 SMB 依赖
     * 全部依赖已内置在 APK assets (smb3.tgz)，直接释放安装，不走网络。
     */
    suspend fun installSmbDependencies(
        onProgress: ((String, Float) -> Unit)? = null
    ): Boolean {
        return installFromAssets(onProgress)
    }

    private suspend fun stopExistingServices() {
        val busyboxPath = findBusybox()
        shellExecutor.execute("$busyboxPath killall -9 smbd0 2>/dev/null", asRoot = true)
    }
}