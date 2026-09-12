#!/bin/bash
# 对照实验：验证 uiautomator dump 是否会破坏无障碍服务绑定
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
SVC="$PKG/com.xiaopacai.tvos.service.TVAccessibilityService"
KARAOKE="com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService"
VOICE="com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService"
LIST="$KARAOKE:$VOICE:$SVC"

check() {
  N=$(adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护")
  echo "   [$(date +%H:%M:%S)] 小趴菜服务在册=$N"
}

echo "### 实验 A：重配后【不 dump】，等 105 秒 ###"
adb -s $D shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
sleep 1
adb -s $D shell settings put secure accessibility_enabled 1
adb -s $D shell settings put secure enabled_accessibility_services "$LIST"
sleep 5;  check
sleep 50; check
sleep 50; check

echo ""
echo "### 实验 B：现在【做一次 uiautomator dump】，再看服务 ###"
check
adb -s $D shell rm -f /sdcard/probe.xml
adb -s $D shell uiautomator dump --compressed /sdcard/probe.xml >/dev/null 2>&1
sleep 5; check
sleep 20; check
