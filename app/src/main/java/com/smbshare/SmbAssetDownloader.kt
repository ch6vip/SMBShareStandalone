package com.smbshare

import android.content.Context
import com.smbshare.utils.Md5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SMB 资源下载器
 * 优先从 APK assets 释放 smb3.tgz，失败时回退到网络下载
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
                onProgress?.invoke("设置权限…", 0.85f)
                makeExecutable(SmbConfigGenerator.SMB_EXECUTABLE)
                makeExecutable(SmbConfigGenerator.DBUS_EXECUTABLE)

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
            // 先写到 app 私有目录，再 mv 到目标 (避免跨用户权限问题)
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
    //  网络下载 (回退路径)
    // ===================================================================

    /**
     * 下载文件并校验 MD5
     */
    suspend fun downloadWithMd5Check(
        downloadUrl: String,
        destPath: String,
        expectedMd5: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                val destFile = File(destPath)
                // 确保目录存在
                destFile.parentFile?.mkdirs()

                // 下载文件
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.setRequestProperty("Referer", "https://www.123pan.com/")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")

                // 如果有 expectedMd5 但文件已存在且校验正确，跳过下载
                if (expectedMd5 != null && destFile.exists()) {
                    val existingMd5 = Md5Utils.md5(destFile.readBytes())
                    if (existingMd5.equals(expectedMd5, ignoreCase = true)) {
                        return@withContext DownloadResult(
                            success = true,
                            path = destPath,
                            md5 = existingMd5,
                            cached = true
                        )
                    }
                }

                val contentLength = connection.contentLength
                val inputStream = BufferedInputStream(connection.inputStream)
                val outputStream = FileOutputStream(destFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0 && onProgress != null) {
                        onProgress(totalBytesRead.toFloat() / contentLength)
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                // 计算 MD5
                val actualMd5 = Md5Utils.md5(destFile.readBytes())

                // MD5 校验
                if (expectedMd5 != null && !actualMd5.equals(expectedMd5, ignoreCase = true)) {
                    destFile.delete()
                    return@withContext DownloadResult(
                        success = false,
                        path = destPath,
                        md5 = actualMd5,
                        error = "MD5 mismatch: expected $expectedMd5, got $actualMd5"
                    )
                }

                DownloadResult(
                    success = true,
                    path = destPath,
                    md5 = actualMd5
                )
            } catch (e: Exception) {
                DownloadResult(
                    success = false,
                    path = destPath,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * 解压 .tgz / .tar.gz 文件
     */
    suspend fun extractTgz(tgzPath: String, destDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            // 创建目标目录
            shellExecutor.execute("mkdir -p $destDir", asRoot = true)

            // 使用 busybox tar 解压
            val busyboxPath = findBusybox()
            val result = shellExecutor.execute(
                "$busyboxPath tar -xzf \"$tgzPath\" -C \"$destDir\"",
                asRoot = true
            )
            result.isSuccess
        }
    }

    /**
     * 解压 zip 文件 (部分 SMB 包可能是 zip 格式)
     */
    suspend fun extractZip(zipPath: String, destDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            shellExecutor.execute("mkdir -p $destDir", asRoot = true)

            val busyboxPath = findBusybox()
            val result = shellExecutor.execute(
                "$busyboxPath unzip -o \"$zipPath\" -d \"$destDir\"",
                asRoot = true
            )
            result.isSuccess
        }
    }

    /**
     * 设置二进制文件的可执行权限
     */
    suspend fun makeExecutable(filePath: String): Boolean {
        val result = shellExecutor.execute("chmod 755 $filePath", asRoot = true)
        return result.isSuccess
    }

    /**
     * 查找 busybox 路径
     */
    private suspend fun findBusybox(): String {
        val candidates = listOf(
            "/nitiFile/busybox",
            "/data/zb/busybox",
            "/data/assetsFairu/busybox",
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/data/local/tmp/busybox"
        )
        for (path in candidates) {
            val result = shellExecutor.execute("test -f $path && echo exists", asRoot = true)
            if (result.stdout?.contains("exists") == true) {
                return path
            }
        }
        return "busybox" // fallback to PATH
    }

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
     * 优先从 assets 安装，失败时回退到网络下载
     */
    suspend fun installSmbDependencies(
        smbTgzUrl: String = DEFAULT_SMB_TGZ_URL,
        onProgress: ((String, Float) -> Unit)? = null
    ): Boolean {
        // 路径 1: 从 assets 安装
        val assetsOk = installFromAssets(onProgress)
        if (assetsOk) {
            return true
        }

        // 路径 2: 回退到网络下载
        onProgress?.invoke("assets 安装失败，尝试网络下载…", 0f)
        return installFromNetwork(smbTgzUrl, onProgress)
    }

    /**
     * 从网络下载并安装 (原逻辑)
     */
    private suspend fun installFromNetwork(
        smbTgzUrl: String,
        onProgress: ((String, Float) -> Unit)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
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

            // 下载 smb3.tgz
            onProgress?.invoke("下载 smb3.tgz…", 0.1f)
            val tgzPath = "${SmbConfigGenerator.SMB_INSTALL_DIR}/smb3.tgz"
            val downloadResult = downloadWithMd5Check(
                downloadUrl = smbTgzUrl,
                destPath = tgzPath,
                onProgress = { progress ->
                    onProgress?.invoke("下载中… ${(progress * 100).toInt()}%", 0.1f + progress * 0.6f)
                }
            )
            if (!downloadResult.success) {
                onProgress?.invoke("下载失败: ${downloadResult.error}", 0f)
                return@withContext false
            }

            // 停止旧的服务进程
            onProgress?.invoke("停止旧服务…", 0.7f)
            stopExistingServices()

            // 解压
            onProgress?.invoke("解压中…", 0.75f)
            val extractOk = extractTgz(tgzPath, SmbConfigGenerator.SMB_INSTALL_DIR)
            if (!extractOk) {
                onProgress?.invoke("解压失败", 0f)
                return@withContext false
            }

            // 设置执行权限
            onProgress?.invoke("设置权限…", 0.9f)
            makeExecutable(SmbConfigGenerator.SMB_EXECUTABLE)
            makeExecutable(SmbConfigGenerator.DBUS_EXECUTABLE)

            // 清理临时文件
            shellExecutor.execute("rm -f $tgzPath", asRoot = true)

            onProgress?.invoke("安装完成", 1f)
            true
        }
    }

    private suspend fun stopExistingServices() {
        val busyboxPath = findBusybox()
        shellExecutor.execute("$busyboxPath killall -9 smbd0 2>/dev/null", asRoot = true)
        shellExecutor.execute("$busyboxPath killall -9 dbus-daemon 2>/dev/null", asRoot = true)
    }

    data class DownloadResult(
        val success: Boolean,
        val path: String,
        val md5: String? = null,
        val error: String? = null,
        val cached: Boolean = false
    )

    companion object {
        // Original URLs from libourom.so (reverse-engineered)
        const val DEFAULT_SMB_TGZ_URL =
            "https://vip.123pan.cn/1815589153/app/assets/smb/smb3.tgz"
        const val DEFAULT_GVFSD_SH_URL =
            "https://vip.123pan.cn/1815589153/app/assets/smb/gvfsd.sh"
    }
}