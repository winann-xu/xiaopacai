#!/bin/bash
# 无障碍绑定修复：重启 app 进程 → 重配 → 以 app 回调为准验证
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
LOG="files/logs/tv_applog.txt"

echo "=== 1) 重启 app 进程（同时会清掉无障碍绑定）==="
adb -s $D shell am force-stop $PKG
sleep 3
adb -s $D shell am start -n $PKG/com.xiaopacai.tvos.ui.TvHomeActivity
sleep 15

echo "=== 2) 重配无障碍（先 enabled 后列表）==="
adb -s $D shell settings put secure accessibility_enabled 1
adb -s $D shell settings put secure enabled_accessibility_services \
  "com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService:com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:${PKG}/com.xiaopacai.tvos.service.TVAccessibilityService"
sleep 12

echo "=== 3) 验证（三路判据）==="
echo -n "   a) dumpsys 在册: "; adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护"
echo -n "   b) 服务进程在跑: "; adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -c "TVAccessibilityService"
echo -n "   c) app 侧已连接日志: "; adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -c "无障碍守护已连接"
echo "   d) 最后 6 条 app 日志:"
adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -vE "守护自检" | tail -6 | sed 's/^/      /'
