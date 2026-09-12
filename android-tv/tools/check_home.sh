#!/bin/bash
cd /Users/winann/01-project/05-codex_project/projects/003-xiaopacai/android-tv/
D=192.168.1.22:5555
adb -s $D shell am start -n com.xiaopacai.tvos.debug/com.xiaopacai.tvos.ui.TvHomeActivity >/dev/null 2>&1
sleep 6
adb -s $D shell rm -f /sdcard/u.xml
adb -s $D shell uiautomator dump --compressed /sdcard/u.xml >/dev/null 2>&1
adb -s $D exec-out cat /sdcard/u.xml > /tmp/final2.xml 2>/dev/null

echo "=== 首页文本 ==="
python3 tools/dump_ui_text.py /tmp/final2.xml

N=`grep -c "无障碍拦截已失效" /tmp/final2.xml 2>/dev/null`
echo "=== 无障碍失效警示数: ${N:-0} ==="

echo "=== 前台 Activity ==="
adb -s $D shell dumpsys activity activities 2>/dev/null | grep mResumedActivity | sed 's/.*u0 //' | cut -d' ' -f1
