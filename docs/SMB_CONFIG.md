# SMB 配置详解

## smb.conf 参数说明

### [global] 全局配置

| 参数 | 值 | 说明 |
|------|-----|------|
| `disable netbios` | Yes | 禁用 NetBIOS 名称服务，减少广播流量。使用 IP 直连更快 |
| `enable core files` | no | 不生成崩溃转储文件，节省磁盘 |
| `unix charset` | UTF-8 | 文件名字符编码 |
| `server min protocol` | SMB3_02 | 最低协议版本。SMB3_02 对应 Windows 8.1+ / Samba 4.1+ |
| `timestamp logs` | no | 不在日志文件名中加时间戳 |
| `log level` | 0 | 日志级别: 0=静默, 1=仅错误, 2=警告, 3=信息 |
| `protocol` | SMB3 | 首选协议版本 (SMB3.x 支持加密、多通道等) |
| `interfaces` | * | 监听所有网络接口 |
| `workgroup` | WORKGROUP | 工作组名 (Windows 默认 WORKGROUP) |
| `server string` | 自定义 | 在网络浏览中显示的服务器描述 |
| `remote announce` | 255.255.255.255 | 广播 NetBIOS 宣告 (已被 disable netbios 禁用) |
| `load printers` | no | 不共享打印机 |
| `printcap name` | /dev/null | 打印机定义文件 |
| `disable spoolss` | yes | 禁用打印 spool 服务 |
| `deadtime` | 60 | 空闲连接超时 (分钟) |
| `delete readonly` | yes | 允许删除只读文件 |
| `nt acl support` | no | 禁用 NT ACL，简化权限 (Unix 模式) |
| `inherit permissions` | yes | 新文件继承父目录权限 |
| `local master` | no | 不竞选本地主浏览器 |
| `unix extensions` | yes | 启用 Unix 扩展 (支持 symlink、权限等) |
| `security` | user | 用户级安全。配合 `map to guest` 实现匿名访问 |
| `guest account` | nobody | Guest 映射到的 Unix 用户 |
| `map to guest` | Bad User | 任何未知用户自动映射为 guest |
| `socket options` | TCP_NODELAY IPTOS_LOWDELAY | 网络性能优化 |
| `read raw` | yes | 启用原始读取 (更好的大文件性能) |
| `write raw` | yes | 启用原始写入 |
| `max xmit` | 131072 | 最大传输单元 (128KB) |

### [share_name] 共享配置

| 参数 | 值 | 说明 |
|------|-----|------|
| `comment` | 自定义 | 共享描述 |
| `read only` | no | 允许写入 (设为 yes 则只读) |
| `force user` | root | 所有文件操作以该用户身份执行 |
| `path` | 自定义 | 共享的本地目录路径 |
| `map readonly` | permissions | 将文件系统只读权限映射到 SMB |

## 协议版本对比

| 版本 | Windows | 特性 |
|------|---------|------|
| CORE | Windows 3.1 | 最早版本 |
| LANMAN1 | Windows 95 | Long filename support |
| NT1 | Windows NT 4 | NT ACL, Unicode |
| SMB2 | Windows Vista | Pipelining, larger reads/writes |
| SMB2.1 | Windows 7 | Large MTU, Lease |
| SMB3 | Windows 8 | Encryption, Multichannel |
| SMB3.02 | Windows 8.1 | Improved performance |
| SMB3.1.1 | Windows 10 | Pre-authentication integrity |

当前配置: `server min protocol = SMB3_02` → 仅接受 Windows 8.1+ 客户端连接

## 自定义配置示例

### 场景一: 只读共享

```ini
[share_name]
read only = yes
force user = nobody
path = /sdcard/DCIM
```

### 场景二: 需要密码

```ini
[global]
security = user
map to guest = Never

[secure_share]
read only = no
valid users = myuser
path = /sdcard/private
```

### 场景三: 多共享

```ini
[photos]
comment = Photo Library
read only = yes
path = /sdcard/DCIM

[music]
comment = Music
read only = yes
path = /sdcard/Music

[downloads]
comment = Downloads
read only = no
path = /sdcard/Download
```

### 场景四: 仅监听 WiFi

```ini
[global]
interfaces = wlan0
bind interfaces only = yes
```

## 端口说明

| 端口 | 协议 | 用途 |
|------|------|------|
| 137 | UDP | NetBIOS Name Service (已禁用) |
| 138 | UDP | NetBIOS Datagram (已禁用) |
| 139 | TCP | NetBIOS Session (SMB over NetBIOS) |
| 445 | TCP | SMB over TCP (直接 SMB，主要端口) |

由于 `disable netbios = Yes`，仅使用 445 端口。