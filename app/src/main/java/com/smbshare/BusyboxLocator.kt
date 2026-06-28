package com.smbshare

/**
 * busybox 定位器 (唯一事实源)
 *
 * 历史上 SmbProcessManager / SmbAssetDownloader / scripts 各持一份候选路径列表且互不一致,
 * 导致「安装期能找到 /data/zb/busybox, 启动期找不到」的 killall 回退 PATH bug。
 * 统一在此维护候选列表, 三处共用。scripts/start_smb.sh 应同步此列表。
 *
 * 顺序约定: 原 ROM 工具路径在前 (/nitiFile, /data/assetsFairu), 释放产物 (/data/zb) 次之,
 * 系统路径兜底; 全部落空则回退 PATH 里的 `busybox`。
 */
object BusyboxLocator {

    val CANDIDATES: List<String> = listOf(
        "/nitiFile/busybox",
        "/data/zb/busybox",
        "/data/assetsFairu/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
        "/data/local/tmp/busybox"
    )

    const val FALLBACK = "busybox"

    /**
     * 一次 su 跑完整 shell 循环定位 busybox, 替代「每个候选各一次 su」。
     * 返回第一个存在的候选路径, 全部落空返回 FALLBACK (走 PATH)。
     */
    suspend fun find(shellExecutor: ShellExecutor): String {
        val probe = CANDIDATES.joinToString(" ") { "test -f \"$it\" && echo \"$it\";" }
        val result = shellExecutor.execute(probe, asRoot = true)
        val hit = result.stdout?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() && it.startsWith("/") }
        return hit ?: FALLBACK
    }
}
