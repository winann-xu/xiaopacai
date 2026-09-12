#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
[TASK-TV-05] Lock Overlay E2E Test Script
Purpose: Verify that the LockOverlayService correctly covers the screen, 
         shows countdown, handles whitelist, and resists HOME key dismissal.
Verify via ADB screenshots and log analysis.
"""

import subprocess
import time
import os

class AdbTester:
    def __init__(self):
        self.device_id = None # Set device ID if multiple devices are connected

    def run(self, cmd):
        full_cmd = ["adb"]
        if self.device_id:
            full_cmd.extend(["-s", self.device_id])
        full_cmd.extend(cmd)
        try:
            result = subprocess.run(full_cmd, capture_output=True, text=True, check=True)
            return result.stdout.strip()
        except subprocess.CalledProcessError as e:
            print(f"Error running {' '.join(full_cmd)}: {e.stderr}")
            return None

    def screenshot(self, filename):
        path = f"screenshots/{filename}.png"
        os.makedirs("screenshots", exist_ok=True)
        self.run(["shell", "screencap", "-p", f"/sdcard/{filename}.png"])
        self.run(["pull", f"/sdcard/{filename}.png", path])
        print(f"[INFO] Screenshot saved to {path}")

    def input_key(self, keycode):
        # KEYCODE_DPAD_DOWN, KEYCODE_HOME, etc.
        self.run(["shell", "input", "keyevent", keycode])

def test_lock_overlay_appearance(tester):
    print("[STEP 1] Triggering timeout lock screen...")
    # Simulate timeout trigger via shell or app command if possible
    # For now, we assume the service is triggered by a simulated event
    tester.run(["shell", "am", "broadcast", "-a", "com.xiaopacai.tvos.ACTION_TRIGGER_TIMEOUT"])
    time.sleep(2) # Wait for overlay to appear
    
    tester.screenshot("01_lock_screen_appeared")

def test_home_key_resistance(tester):
    print("[STEP 2] Pressing HOME key while locked...")
    tester.input_key("KEYCODE_HOME")
    time.sleep(1)
    tester.screenshot("02_after_home_press")
    # In a real E2E, we'd parse screenshot or check window visibility here

def main():
    tester = AdbTester()
    print("--- Starting Lock Overlay E2E Tests [TASK-TV-05] ---")
    
    try:
        test_lock_overlay_appearance(tester)
        test_home_key_resistance(tester)
        print("--- E2E Test Skeleton Finished ---")
    except Exception as e:
        print(f"Test failed: {e}")

if __name__ == "__main__":
    main()
