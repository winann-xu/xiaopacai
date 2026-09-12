#!/bin/bash
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
LOG="files/logs/tv_applog.txt"

echo "=== app 进程 ==="
adb -s $D shell "ps | grep xiaopacai" 2>/dev/null

echo "=== 启动次数 / 崩溃数 ==="
echo -n "  启动次数: "; adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -c "小趴菜电视端启动"
echo -n "  崩溃记录: "; adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -cE "CRASH|崩溃"

echo "=== 守护服务 ==="
adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -oE "com\.xiaopacai\.tvos\.service\.[A-Za-z]+" | sort -u

echo "=== 最近日志（去自检噪音）==="
adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -vE "守护自检" | tail -6

echo "=== 前台 ==="
adb -s $D shell dumpsys activity activities 2>/dev/null | grep mResumedActivity | sed 's/.*u0 //' | cut -d' ' -f1
