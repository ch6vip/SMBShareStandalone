# SMB Share Standalone

从 `com.example.ourom` (v2.88) Android 应用中逆向提取的 **SMB 文件共享功能**，重构为可独立编译运行的 Android 项目。

## 背景

`com.example.ourom` 是一个 Android ROM 刷写/管理工具，内置了基于 **SambaDroid** 框架的 SMB 文件共享功能。该功能将 Android 设备变成局域网内的 SMB 文件服务器，允许其他设备通过 SMB 协议访问设备上的文件。

本项目的 SMB 组件完全从原始 app 的加密 native library (`libourom.so`) 中逆向提取。

## 功能

- ✅ 启动/停止 SMB 服务 (基于 Samba 4.x smbd0)
- ✅ 动态生成 smb.conf 配置文件
- ✅ 支持自定义共享名称、路径、工作组
- ✅ Foreground Service 后台运行 + 通知栏控制
- ✅ 一键下载安装 SMB 二进制依赖
- ✅ MD5 校验下载完整性
- ✅ 独立 Shell 脚本 (可不依赖 APK 直接在 root shell 运行)
- ✅ SMB3 协议支持 (向后兼容 SMB2)

## 前置条件

1. **Root 权限** (Magisk / SuperSU / KernelSU)
2. **Android 8.0+** (API 26+)
3. **Samba 二进制文件** (smbd0, dbus-daemon) 放置在 `/data/zb/`

## 编译

```bash
# 1. 用 Android Studio 打开项目目录
# 2. Sync Gradle
# 3. Build > Build Bundle(s) / APK(s) > Build APK(s)
# 或者命令行:
./gradlew assembleDebug
```

## 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用方式

### 方式一: APK (推荐)

1. 打开 app
2. 点击 **"安装 SMB 依赖"** 下载 smb3.tgz
3. 配置共享名称、路径、工作组
4. 点击 **"启动 SMB"**
5. 在电脑或其它设备访问显示的 SMB 地址

### 方式二: 独立 Shell 脚本

```bash
# 将 smb3.tgz 解压到 /data/zb/
# 然后:
adb shell su -c sh /data/local/tmp/start_smb.sh
# 停止:
adb shell su -c sh /data/local/tmp/stop_smb.sh
```

## 项目结构

```
SMBShareStandalone/
├── app/
│   └── src/main/java/com/smbshare/
│       ├── SmbShareApp.kt              # Application 类
│       ├── SmbService.kt               # Foreground Service
│       ├── SmbConfigGenerator.kt       # smb.conf 生成器
│       ├── SmbAssetDownloader.kt       # 二进制下载器
│       ├── SmbProcessManager.kt        # smbd 进程管理
│       ├── ShellExecutor.kt            # Root shell 执行器
│       ├── ui/MainActivity.kt          # 主界面
│       └── utils/
│           ├── Md5Utils.kt             # MD5 工具
│           └── NetworkUtils.kt         # 网络工具
├── scripts/
│   ├── start_smb.sh                    # 独立启动脚本
│   ├── stop_smb.sh                     # 独立停止脚本
│   └── smb.conf.template               # 配置文件模板
└── docs/
    ├── README.md
    ├── ARCHITECTURE.md
    └── REVERSE_NOTES.md
```

## 从电脑访问

### Windows
```
在文件管理器地址栏输入:
\\192.168.x.x\rannki_smb
```

### macOS
```
Finder > Go > Connect to Server:
smb://192.168.x.x/rannki_smb
```

### Linux
```bash
# 列出共享
smbclient -L //192.168.x.x -U %
# 挂载
sudo mount -t cifs //192.168.x.x/rannki_smb /mnt/smb -o username=guest
```

## 技术细节

- **SMB 服务**: SambaDroid 编译的 Samba 4.x smbd0 (arm64)
- **D-Bus**: dbus-daemon (system bus, Samba 依赖)
- **配置**: 动态生成 smb.conf，guest 访问免密码
- **协议**: SMB3 (server min protocol SMB3_02)
- **端口**: 445 (SMB over TCP), 139 (NetBIOS over TCP)

## 安全注意

- ⚠️ SMB 服务以 **root 用户** 运行 (force user = root)
- ⚠️ 默认开放 **免密码 guest 访问**
- ⚠️ 仅在**受信任的局域网**中使用
- ⚠️ 请勿在公共 WiFi 中启用此服务
- 建议通过 smb.conf 限制 interfaces 到具体网络接口

## 许可证

本项目仅供逆向工程学习和研究使用。Samba 二进制文件 (smbd0) 的版权归 Samba 项目所有。

## 逆向来源

- App: `com.example.ourom` v2.88 (288) build 289507
- 加固: 360 加固 (天御 TDP)
- Native lib: `libourom.so` (arm64-v8a)
- 所有 SMB 相关配置字符串通过 Base64 解码从 native library 提取