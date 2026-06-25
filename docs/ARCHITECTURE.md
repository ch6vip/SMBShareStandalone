# SMB Share Standalone — 架构设计

## 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android 系统层                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   SMBShareStandalone APK                   │  │
│  │                                                           │  │
│  │  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐ │  │
│  │  │ MainActivity │───>│  SmbService   │───>│ SmbProcess   │ │  │
│  │  │   (UI)       │    │ (Foreground)  │    │  Manager     │ │  │
│  │  └─────────────┘    └──────────────┘    └──────┬───────┘ │  │
│  │                                                  │         │  │
│  │  ┌─────────────┐    ┌──────────────┐            │         │  │
│  │  │SmbConfigGen │    │SmbDownloader │            │         │  │
│  │  └─────────────┘    └──────────────┘            │         │  │
│  │                                                  │         │  │
│  └──────────────────────────────────────────────────┼─────────┘  │
│                                                     │            │
│  ┌──────────────────────────────────────────────────┼─────────┐  │
│  │                  ShellExecutor (su)              │         │  │
│  └──────────────────────────────────────────────────┼─────────┘  │
│                                                     │            │
│                                                     ▼            │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    Root Shell (UID 0)                       │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │ │
│  │  │  dbus-daemon │  │    smbd0     │  │   smb.conf       │  │ │
│  │  │  (system bus)│  │  (Samba 4.x) │  │   (config file)  │  │ │
│  │  └──────┬───────┘  └──────┬───────┘  └──────────────────┘  │ │
│  │         │                 │                                 │ │
│  │         └────────┬────────┘                                 │ │
│  │                  │                                          │ │
│  └──────────────────┼──────────────────────────────────────────┘ │
│                     │                                            │
│          Port 445 (SMB) / Port 139 (NetBIOS)                     │
└─────────────────────┼────────────────────────────────────────────┘
                      │
              ┌───────┴───────┐
              │  LAN Clients  │
              │  (PC/Mac/etc) │
              └───────────────┘
```

## 组件说明

### 1. ShellExecutor
- **职责**: 封装所有 Root Shell 通信
- **关键方法**: `execute()`, `checkRoot()`, `isProcessRunning()`
- **线程**: 始终在 `Dispatchers.IO` 上运行

### 2. SmbConfigGenerator
- **职责**: 生成 Samba 配置文件 (smb.conf)
- **关键配置项**:
  - `server min protocol = SMB3_02` — 最低协议版本
  - `security = user` + `map to guest = Bad User` — 匿名 guest 访问
  - `force user = root` — 文件访问以 root 身份进行
  - `socket options = TCP_NODELAY IPTOS_LOWDELAY` — 性能优化
- **输出**: 写入 `/data/zb/samba/smb.conf`

### 3. SmbAssetDownloader
- **职责**: 从远程下载 SMB 二进制包
- **下载源**: `vip.123pan.cn/1815589153/app/assets/smb/smb3.tgz`
- **校验**: MD5 校验下载完整性
- **解压**: 使用 busybox tar -xzf 解压

### 4. SmbProcessManager
- **职责**: Samba 服务进程生命周期管理
- **流程**:
  1. 检查 root 权限
  2. 检查二进制文件存在
  3. 停止旧实例 (killall smbd0 / dbus-daemon)
  4. 创建工作目录
  5. 生成 smb.conf
  6. 启动 dbus-daemon → 等待 1s → 启动 smbd0
  7. 验证 smbd0 是否在运行

### 5. SmbService (Foreground Service)
- **职责**: Android 前台服务，在通知栏显示状态
- **生命周期**: `START_STICKY` (系统可自动重启)
- **通知**: 显示运行状态，提供停止按钮

### 6. MainActivity
- **职责**: 用户交互界面
- **功能**: 配置、启停、安装、日志查看

## 数据流

### 启动流程

```
用户点击 "启动SMB"
  │
  ▼
MainActivity.startSmbService()
  │
  ▼
SmbService.startSmb()  [Foreground Service]
  │
  ▼
SmbProcessManager.start()
  │
  ├─ ShellExecutor.checkRoot()
  ├─ 检查 /data/zb/smbd0 是否存在
  ├─ stopExistingInstances()  [清理旧进程]
  ├─ 创建目录结构
  ├─ SmbConfigGenerator.writeConfigFile()
  │    └─ 生成 /data/zb/samba/smb.conf
  ├─ 启动 dbus-daemon --system &
  ├─ sleep 1
  ├─ export TMPDIR=/data/zb/lib
  ├─ smbd0 -D -s /data/zb/samba/smb.conf
  ├─ sleep 2
  └─ pidof smbd0 → 验证成功
```

### 停止流程

```
用户点击 "停止SMB"
  │
  ▼
MainActivity.stopSmbService()
  │
  ▼
SmbService.stopSmb()
  │
  ▼
SmbProcessManager.stop()
  │
  ├─ killall -9 smbd0
  ├─ killall -9 dbus-daemon
  └─ rm -f /data/zb/samba/smb.conf
```

## SMB 协议版本

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `server min protocol` | SMB3_02 | 最低 SMB 3.0.2 |
| `protocol` | SMB3 | 首选 SMB 3.x |
| 端口 | 445 | SMB over TCP |
| NetBIOS | 禁用 | 仅用 TCP |

## 目录布局

```
/data/zb/                    ← SMB 根目录
├── smbd0                     ← Samba 服务端守护进程 (arm64)
├── dbus-daemon               ← D-Bus 消息总线守护进程
├── lib/                      ← TMPDIR (Samba runtime temp)
│   └── (内部库文件)
└── samba/
    └── smb.conf              ← SMB 配置文件
```

## 安全模型

### 原始 App 的设计

```
安全模型: security = user, guest account = nobody, map to guest = Bad User
文件访问: force user = root (所有文件操作以 root 身份)
权限策略: 免密码 guest 访问, 读写模式
```

### 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 免密码 guest 访问 | 🔴 高 | 仅局域网使用 |
| root 身份运行 | 🔴 高 | 只能限制共享路径 |
| 不加密 SMB 流量 | 🟡 中 | 局域网内风险较低 |
| 广播 announce | 🟡 中 | 配置中已启用 255.255.255.255 |

### 改进建议

```ini
# 更安全的 smb.conf
[global]
# 仅监听 WiFi 接口
interfaces = wlan0

# 禁用 guest 访问 (需要用户名密码)
security = user
map to guest = Never

# 或者使用 SMB 加密
smb encrypt = required

# 限制访问 IP
hosts allow = 192.168.1.0/24
```