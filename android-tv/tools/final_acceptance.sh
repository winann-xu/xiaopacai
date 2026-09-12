#!/bin/bash
# .22 重装后最终验收（对照 .21 基线）
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
LOG="files/logs/tv_applog.txt"

echo "=== 等待 app 检测周期（70s）==="
sleep 70

echo ""
echo "① 无障碍（三路判据）"
echo -n "   dumpsys 在册: "; adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护"
echo -n "   服务实例: ";     adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -c "TVAccessibilityService"
echo -n "   app 已连接日志: "; adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -c "无障碍守护已连接"

echo ""
echo "② 守护服务清单"
adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -oE "com\.xiaopacai\.tvos\.service\.[A-Za-z]+" | sort -u | sed 's/^/   /'

echo ""
echo "③ 看门狗闹钟"
adb -s $D shell dumpsys alarm 2>/dev/null | grep -A2 "xiaopacai" | grep -E "WATCHDOG_TICK|repeatInterval" | sed 's/^/   /'

echo ""
echo "④ 用量权限: $(adb -s $D shell appops get $PKG GET_USAGE_STATS 2>&1 | head -1)"

echo ""
echo "⑤ 崩溃记录: $(adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -cE 'CRASH|崩溃')"

echo ""
echo "⑥ 关键日志（最近 12 条，去掉自检噪音）"
adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -vE "守护自检" | tail -12 | sed 's/^/   /'
