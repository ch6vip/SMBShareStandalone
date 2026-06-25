package com.smbshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root shell 执行器
 * 封装 su 命令调用，支持执行多条命令和脚本
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
     */
    suspend fun executeCommands(commands: List<String>, asRoot: Boolean = true): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val script = commands.joinToString(" && ") { "($it)" }
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
     */
    suspend fun checkRoot(): Boolean {
        val result = execute("id", asRoot = true)
        return result.stdout?.contains("uid=0") == true ||
                result.stdout?.contains("uid=0(root)") == true ||
                result.exitCode == 0
    }

    /**
     * 检查进程是否在运行
     */
    suspend fun isProcessRunning(processName: String): Boolean {
        val result = execute("pidof $processName", asRoot = true)
        return result.exitCode == 0 && !result.stdout.isNullOrBlank()
    }

    private fun executeInternal(command: String, asRoot: Boolean): ExecutionResult {
        val shell = if (asRoot) "su" else "sh"
        val process = Runtime.getRuntime().exec(arrayOf(shell, "-c", command))

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        var line: String?
        while (stdoutReader.readLine().also { line = it } != null) {
            stdout.appendLine(line)
        }
        while (stderrReader.readLine().also { line = it } != null) {
            stderr.appendLine(line)
        }

        val exitCode = process.waitFor()
        stdoutReader.close()
        stderrReader.close()
        process.destroy()

        return ExecutionResult(
            exitCode = exitCode,
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd()
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