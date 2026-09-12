#!/bin/bash
# 无障碍绑定：以 app 侧 onServiceConnected 日志为准（dumpsys 在 MIUI 上可能不可信）
D=192.168.1.22:5555
PKG=com.xiaopacai.tvos.debug
LOG="files/logs/tv_applog.txt"

echo "### 重配无障碍 ###"
adb -s $D shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
sleep 1
adb -s $D shell settings put secure accessibility_enabled 1
adb -s $D shell settings put secure enabled_accessibility_services \
  "com.xiaomi.mitv.karaoke.service/com.loostone.puremic.voice.service.accessibility.PuremicKeyService:com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService:${PKG}/com.xiaopacai.tvos.service.TVAccessibilityService"

for i in 1 2 3 4 5 6; do
  sleep 20
  CL=$(adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep -c "无障碍守护已连接")
  EN=$(adb -s $D shell dumpsys accessibility 2>/dev/null | grep -c "小趴菜电视守护")
  RUN=$(adb -s $D shell dumpsys activity services $PKG 2>/dev/null | grep -c "TVAccessibilityService")
  LAST=$(adb -s $D exec-out run-as $PKG cat $LOG 2>/dev/null | grep "无障碍" | tail -1 | sed 's/.*I\///')
  echo "[$(date +%H:%M:%S)] 连接日志累计=$CL  dumpsys在册=$EN  服务进程在跑=$RUN"
  echo "        最后一条: ${LAST:0:60}"
done
