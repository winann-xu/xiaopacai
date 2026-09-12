#!/bin/bash
# 恢复：只 put（不 delete）写回完整列表
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
FULL="com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService:com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:$PKG/com.xiaopacai.tvos.service.TVAccessibilityService"
on() { adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护"; }

echo "恢复前: $(on)"
adb -s $D shell settings put secure accessibility_enabled 1
adb -s $D shell settings put secure enabled_accessibility_services "$FULL"
sleep 8
echo "第 1 次 put 后: $(on)"
sleep 8
echo "再等 8 秒: $(on)"
echo ""
echo "=== 服务侧确认 ==="
adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -c "TVAccessibilityService" | sed 's/^/  服务实例数: /'
echo "=== app 日志 ==="
adb -s $D exec-out run-as $PKG cat files/logs/tv_applog.txt 2>/dev/null | grep -vE "守护自检" | tail -4 | sed 's/^/  /'
