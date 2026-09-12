#!/bin/bash
# 小趴菜 TV 端：设备状态快照（重置前/后对比用）
# 用法: bash snapshot_tv.sh <device> <输出目录>
set -u
D="${1:-192.168.1.22:5555}"
OUT="${2:-./snapshot}"
ADB="adb -s $D"
PKG=com.xiaopacai.tvos.debug

mkdir -p "$OUT" || exit 1

echo "== 目标设备 $D -> $OUT =="

$ADB wait-for-device 2>/dev/null
$ADB get-state >"$OUT/state.txt" 2>&1 || true

# 设备身份与环境
$ADB shell getprop >"$OUT/getprop.txt" 2>&1
$ADB shell wm size >"$OUT/wm_size.txt" 2>&1
$ADB shell wm density >"$OUT/wm_density.txt" 2>&1
$ADB shell date >"$OUT/date.txt" 2>&1

# ★ DO 可行性四要素（决定能否拿 device owner）
$ADB shell pm list features >"$OUT/features.txt" 2>&1
$ADB shell "ls -la /system/etc/permissions/" >"$OUT/system_permissions.txt" 2>&1
$ADB shell "grep -l software.device_admin /system/etc/permissions/* 2>/dev/null" >"$OUT/device_admin_decl.txt" 2>&1
$ADB shell mount >"$OUT/mount.txt" 2>&1
$ADB shell "su -c id" >"$OUT/root_check.txt" 2>&1

# 应用与策略状态
$ADB shell pm list packages >"$OUT/packages.txt" 2>&1
$ADB shell dumpsys package "$PKG" >"$OUT/package.txt" 2>&1
$ADB shell dumpsys device_policy >"$OUT/device_policy.txt" 2>&1
$ADB shell dumpsys account >"$OUT/accounts.txt" 2>&1

# 应用私有数据（无需 root，走 run-as）
$ADB exec-out run-as "$PKG" cat files/datastore/xiaopacai_tv_settings.preferences_pb >"$OUT/prefs.pb" 2>/dev/null
$ADB exec-out run-as "$PKG" cat files/logs/tv_applog.txt >"$OUT/applog.txt" 2>/dev/null

# 运行态
$ADB shell dumpsys accessibility >"$OUT/accessibility.txt" 2>&1
$ADB shell dumpsys activity services "$PKG" >"$OUT/services.txt" 2>&1
$ADB shell dumpsys alarm >"$OUT/alarm.txt" 2>&1
$ADB shell settings list secure >"$OUT/settings_secure.txt" 2>&1
$ADB shell settings list global >"$OUT/settings_global.txt" 2>&1

echo "== 完成，共 $(ls -1 "$OUT" | wc -l | tr -d ' ') 个文件 =="
