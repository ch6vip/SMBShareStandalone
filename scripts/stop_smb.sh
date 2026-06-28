#!/system/bin/sh
#
# SMB 服务停止脚本
#

SMB_DIR="/data/zb"

echo "========================================"
echo "  SMB Share Standalone — 停止脚本"
echo "========================================"

# 查找 busybox (候选列表与 start_smb.sh / BusyboxLocator.kt 保持一致)
BUSYBOX=""
for B in /nitiFile/busybox /data/assetsFairu/busybox /data/zb/busybox /data/local/tmp/busybox /system/xbin/busybox /system/bin/busybox; do
    if [ -f "$B" ]; then
        BUSYBOX="$B"
        break
    fi
done
if [ -z "$BUSYBOX" ]; then
    BUSYBOX="busybox"
fi

# 停止 smbd0
echo "[STEP] 停止 smbd0..."
$BUSYBOX killall -9 smbd0 2>/dev/null

# 清理配置文件
echo "[STEP] 清理配置文件..."
rm -f "$SMB_DIR/samba/smb.conf" 2>/dev/null

sleep 1

# 验证
if $BUSYBOX pidof smbd0 > /dev/null 2>&1; then
    echo "[WARN] smbd0 仍在运行"
else
    echo "[OK] SMB 服务已停止"
fi
