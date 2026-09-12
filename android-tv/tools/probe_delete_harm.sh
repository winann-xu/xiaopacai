#!/bin/bash
# 验证：settings delete 是否是导致「无障碍配不上」的元凶
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
SVC="$PKG/com.xiaopacai.tvos.service.TVAccessibilityService"
FULL="com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService:com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:$SVC"

on() { adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护"; }

echo "① 当前状态（刚 force-stop 重配过）: $(on)"

echo ""
echo "② 实验：用 settings put 覆盖成【只留小米服务】→ 看是否解绑（模拟被外部改写）"
adb -s $D shell settings put secure enabled_accessibility_services "com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService:com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService"
sleep 8
echo "   仅 put（不 delete）后: $(on)"

echo ""
echo "③ 实验：再用 settings put 直接写回完整列表（不 delete）"
adb -s $D shell settings put secure enabled_accessibility_services "$FULL"
sleep 8
echo "   再 put 写回后: $(on)"

echo ""
echo "④ 实验：这回用 delete + put（我原以为正确的姿势）"
adb -s $D shell settings delete secure enabled_accessibility_services
sleep 2
adb -s $D shell settings put secure accessibility_enabled 1
adb -s $D shell settings put secure enabled_accessibility_services "$FULL"
sleep 10
echo "   delete+put 后: $(on)"

echo ""
echo "⑤ app 日志最后 3 条:"
adb -s $D exec-out run-as $PKG cat files/logs/tv_applog.txt 2>/dev/null | grep -vE "守护自检" | tail -3 | sed 's/^/   /'
