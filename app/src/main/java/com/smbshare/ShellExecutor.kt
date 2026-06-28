package com.smbshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Root shell 执行器
 * 封装 su 命令调用，支持执行多条命令和脚本
 * 当 su 不可用时 (KernelSU 未授权等) 自动降级为普通 shell
 */
class ShellExecutor {

    /**
     * 执行单个命令 (同步，需要在 IO 线程调用)
     * @param command 要执行的命令
     * @param asRoot 是否以 root 权限执行
     * @return ExecutionResult 包含 exitCode, stdout, stderr
     */
    suspend fun execute(command: String, asRoot: Boolean = true): ExecutionResult {
        return withContext(Dispatchers.IO) {
            executeInternal(command, asRoot)
        }
    }

    /**
     * 执行多条命令
     *
     * 用换行 + `set -e` 拼接, 任意一条失败即中止并返回非零。
     * 不再用 `($it) && ($it)`: 后者遇到命令内部含 && / 引号 / 重定向时会被括号改变语义。
     */
    suspend fun executeCommands(commands: List<String>, asRoot: Boolean = true): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val script = buildString {
                append("set -e\n")
                commands.forEach { append(it).append('\n') }
            }
            executeInternal(script, asRoot)
        }
    }

    /**
     * 执行 shell 脚本文件
     */
    suspend fun executeScript(scriptPath: String, asRoot: Boolean = true): ExecutionResult {
        return withContext(Dispatchers.IO) {
            executeInternal("sh \"$scriptPath\"", asRoot)
        }
    }

    /**
     * 检查 root 权限是否可用
     * 必须真正拿到 uid=0 才算 root。
     * 不能用 exitCode==0 判断: su 不可用时会降级到 sh, sh 跑 `id` 同样返回 0,
     * 那样无 root 设备也会被误判为有 root。
     */
    suspend fun checkRoot(): Boolean {
        val result = execute("id", asRoot = true)
        return result.stdout?.contains("uid=0") == true
    }

    /**
     * 检查进程是否在运行
     */
    suspend fun isProcessRunning(processName: String): Boolean {
        val result = execute("pidof $processName", asRoot = true)
        return result.exitCode == 0 && !result.stdout.isNullOrBlank()
    }

    private fun executeInternal(command: String, asRoot: Boolean): ExecutionResult {
        // 尝试 su，失败则降级为 sh
        val shell = if (asRoot) "su" else "sh"
        try {
            return runWithShell(shell, command)
        } catch (e: java.io.IOException) {
            // su 不可用 (KernelSU 未授权 / error=13)，降级为 sh
            if (asRoot) {
                try {
                    return runWithShell("sh", command)
                } catch (e2: Exception) {
                    return ExecutionResult(-1, null, e2.message)
                }
            }
            return ExecutionResult(-1, null, e.message)
        }
    }

    private fun runWithShell(shell: String, command: String): ExecutionResult {
        val process = Runtime.getRuntime().exec(arrayOf(shell, "-c", command))

        // 必须并发读 stdout / stderr: 若顺序读, 命令往一个管道写满而我们正堵在另一个管道,
        // 进程写阻塞 -> 双向死锁。用独立线程各读一个流。
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { synchronized(stdout) { stdout.appendLine(it) } }
            }
        }
        val stderrThread = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { synchronized(stderr) { stderr.appendLine(it) } }
            }
        }
        stdoutThread.start()
        stderrThread.start()

        // 设置超时 (30s)，防止 shell 命令永久阻塞 IO 线程；
        // waitFor(timeout) 在 API 26+ 可用 (minSdk = 26 故无需版本判断)
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        // 等读取线程把残余输出排空 (最多再等 2s)
        stdoutThread.join(2000)
        stderrThread.join(2000)
        process.destroy()

        val exitCode = if (finished) process.exitValue() else -1

        return ExecutionResult(
            exitCode = exitCode,
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd().let {
                if (!finished) "[TIMEOUT] $it".trimEnd() else it
            }
        )
    }

    data class ExecutionResult(
        val exitCode: Int,
        val stdout: String?,
        val stderr: String?
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val output: String get() = listOfNotNull(stdout, stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
