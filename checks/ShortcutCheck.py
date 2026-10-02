#!/usr/bin/env python3
"""Run against an installed app after sim-use preflight: python3 checks/ShortcutCheck.py SERIAL."""
import re
import subprocess
import sys
import time

serial = sys.argv[1]
component = "info.plateaukao.transportation/.MainActivity"

def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout

def activity():
    state = run("adb", "-s", serial, "shell", "dumpsys", "activity", "activities")
    match = re.search(r"(?:topResumedActivity|mResumedActivity)[=:]\s*ActivityRecord\{(\w+)[^\n]*transportation/\.MainActivity", state)
    assert match, "Transportation activity is not resumed"
    return match.group(1)

def check(route, direction, title, label):
    run("adb", "-s", serial, "shell", "am", "start", "-W", "-n", component,
        "-a", "android.intent.action.VIEW", "-f", "0x34000000",
        "--ei", "shortcut_route", str(route), "--ei", "shortcut_direction", str(direction))
    time.sleep(0.5)
    outline = run("sim-use", "ui", "--device", serial)
    assert "PROCESS DISAPPEARED" not in outline and "CRASH DIALOG" not in outline, outline
    assert '"' + title + '"' in outline and "公車方向：" + label in outline, outline
    assert "預估到站：" in outline, "Stop list is missing"
    return activity()

# The initial launch may create an activity. Every later launch must reuse it.
first = check(200321, 0, "綠7", "往捷運大坪林站")
assert check(200321, 1, "綠7", "往黎明清境") == first, "Direction shortcut recreated the activity"
assert check(111811, 0, "0南", "往捷運東門站") == first, "Route shortcut recreated the activity"
print("PASS: warm shortcuts reuse the activity and restore route, direction and stop list")
