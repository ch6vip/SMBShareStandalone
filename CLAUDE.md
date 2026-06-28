# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目本质

从加固 App `com.example.ourom`（v2.88，360 加固）的 native 库 `libourom.so` 中**逆向提取**出的 SMB 文件共享功能，重构为独立可编译的 Android 工程。SMB 服务端 `smbd0` 是 SambaDroid 编译的 Samba 4.x (arm64) 产物，非本项目自行开发。

**核心认知：app 本身不实现 SMB。** 它只是一个 UI + 生命周期壳，通过 **root shell 驱动原生 `smbd0` 二进制**。整条链路是 `su` 命令编排，理解这一点比读任何业务逻辑都重要。

## 构建与运行

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew assembleRelease        # 构建 release APK（minify 开启，走 proguard-rules.pro）
./gradlew lint                   # 静态检查
adb install app/build/outputs/apk/debug/app-debug.apk
```

- JDK 17、AGP 8.2、Kotlin 1.9、`compileSdk`/`targetSdk` 34、`minSdk` 26。
- **无测试目录**（`app/src/test`、`app/src/androidTest` 均不存在），目前没有单测可跑。
- 仓库只含 unix wrapper `gradlew`（**无 `gradlew.bat`**）。Windows 本地构建请在 git bash / WSL 下用 `./gradlew`；CI 在 ubuntu 上运行。
- CI（`.github/workflows/build.yml`）：push/PR 触发 debug+release 构建；打 `v*` tag 时额外创建 GitHub Release 并上传 APK + `scripts/`。

## 架构（大局）

数据流（启动）：

```
MainActivity ──startForegroundService(intent+配置)──> SmbService (foreground)
                                                            │ serviceScope.launch
                                                            ▼
                                              SmbProcessManager.start()
                                                            │
                              ┌─────────────────────────────┼───────────────────────┐
                              ▼                             ▼                       ▼
                    SmbConfigGenerator            ShellExecutor(su)         检查/创建目录
                  (内联生成 smb.conf,            (killall 旧 smbd0,          (/data/zb/...)
                   heredoc 写到 /data/zb/        启动 smbd0 -D,
                   samba/smb.conf)               sleep 2 后 pidof 验证)
                                                            │
                                                            ▼
                                                   原生 smbd0 (UID 0)
                                                   监听 445/139，对外提供 SMB
```

关键组件职责：

- **`ShellExecutor`** — 所有 root 通信的唯一出口。`execute()` 跑 `su -c <cmd>`；`su` 不可用时**降级为 `sh`**（非 root 设备不崩溃，但功能受限）。所有挂起方法在 `Dispatchers.IO` 上。
- **`SmbProcessManager`** — smbd0 生命周期编排：检查 root → 检查二进制 → 清旧实例 → 建目录 → 写 smb.conf → 启动 smbd0 → `pidof` 验证。失败时 `getStatusDiagnosis()` 跑 testparm + netstat 输出诊断。
- **`SmbConfigGenerator`** — **路径与默认值的唯一事实源**（`companion object` 常量：`/data/zb/smbd0`、`/data/zb/lib`、`/data/zb/samba/smb.conf`、默认共享名 `rannki_smb` 等）。内联拼接 smb.conf，不用模板文件。
- **`SmbService`** — 前台服务，`foregroundServiceType="specialUse"`。`START_REDELIVER_INTENT`（非 STICKY）以便被杀后带配置重投。
- **`SmbAssetDownloader`** — **名不副实**：当前只从 APK assets 释放 `smb3.tgz`，不做任何网络下载（见下方「文档与代码分歧」）。
- **`MainActivity`** — 配置输入 + 启停 + 安装 + 日志展示。IP 选取用 `NetworkUtils`。

## 安装根目录布局

二进制释放到 `/data/zb/`（路径来自原 app，smbd0 的 RPATH 烧死为 `/data/zb:/data/zb/samba`，故无需 `LD_LIBRARY_PATH`）：

```
/data/zb/
├── smbd0                       ← 主程序（需 chmod 755）
├── ld-linux-aarch64.so.1       ← 动态链接器，由内核作 interpreter 加载，必须带 exec 位
├── lib/                        ← TMPDIR + Samba 运行时库
└── samba/smb.conf              ← 运行时生成
```

`busybox` 用于 `tar`/`killall`，按候选路径列表搜索（`/nitiFile/busybox` 在前——源自原 ROM 工具，其它设备通常没有，会 fallback 到 PATH 里的 `busybox`）。

## 关键陷阱（踩过的坑，代码注释里有）

1. **`checkRoot()` 必须判 `uid=0`，不能判 exitCode。** `su` 降级到 `sh` 后跑 `id` 同样返回 0，否则无 root 设备会被误判为有 root。
2. **`runWithShell` 必须并发读 stdout/stderr。** 顺序读会因管道缓冲写满而死锁。
3. **smbd0 启动必须 `</dev/null >/dev/null 2>&1`。** smbd0 `-D` 自身 daemon 化但仍继承 fd，否则 `ShellExecutor` 的 readLine 会一直等管道 EOF 而阻塞——这正是 commit `d9e30e1` 修的「服务起不来」。
4. **空输入要用 `takeIf { it.isNotBlank() }` 挡掉**（`?:` 只挡 null 挡不住空串），否则生成非法 `[]` 共享名导致 smbd 异常。
5. **前台服务 5s 内必须 `startForeground`**，否则 ANR；`onStartCommand` 的 null-intent 分支专门处理此路径。
6. **app 自身不需要存储权限**：文件访问全部由 root 身份的 smbd0 完成。Manifest 也无 `INTERNET` 权限（SMB 监听在 native 层）。

## 两种部署模式

1. **APK（推荐）**：安装后点「安装 SMB 依赖」释放内置 `smb3.tgz` → 配置 → 启动。
2. **独立 shell 脚本**（`scripts/`）：可在 root shell 直接跑，需先把 `smb3.tgz` 解压到 `/data/zb/`。`start_smb.sh` / `stop_smb.sh` 自带完整逻辑，与 Kotlin 侧 `SmbProcessManager` 是平行的两套实现。

注意：`scripts/smb.conf.template` 是**参考模板**（`{PLACEHOLDER}` 占位），**不被任何代码读取**——Kotlin 侧 `SmbConfigGenerator` 内联生成，shell 脚本也内联 heredoc 生成。改它不会影响运行。

## 文档与代码分歧（重要）

`docs/` 下的 README / ARCHITECTURE / REVERSE_NOTES 描述的是**早期设计**，与当前代码已不一致。**以代码为准**：

- docs 说从 123云盘下载 `smb3.tgz` + MD5 校验 → 实际已改为**内置 assets 释放**（commit `cbf03e7`），`SmbAssetDownloader` 不走网络，README 里提到的 `Md5Utils.kt` 已不存在。
- docs 说依赖 `dbus-daemon` → 实际已移除（commit `27ad118`），smbd0 独立运行。
- 逆向细节（XOR-16 字符串、Base64、JNI 方法映射、123云盘 URL）见 `docs/REVERSE_NOTES.md`，仍是准确的参考。

## 安全模型

- smbd0 以 **root** 运行（`force user = root`），**免密码 guest 访问**（`map to guest = Bad User`），仅 445/139 端口、SMB3_02 起步。
- 设计上仅用于**受信任局域网**。修改 smb.conf 加固方向（`interfaces=wlan0`、`map to guest=Never`、`smb encrypt=required`、`hosts allow`）见 `docs/ARCHITECTURE.md` 末尾与 `docs/SMB_CONFIG.md`。
