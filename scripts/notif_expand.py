#!/usr/bin/env python3
"""Expand the CPA gateway notification and dump its text."""
import re
import subprocess
import sys

ADB = ["/mnt/c/Users/28381/Documents/ds_home/tools/platform-tools/adb.exe",
       "-s", "BU8XQGONNBQGFUHQ"]


def sh(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True).stdout


sh("shell", "cmd", "statusbar", "expand-notifications")
import time
time.sleep(2)
sh("shell", "uiautomator", "dump", "/sdcard/n.xml")
xml = sh("exec-out", "cat", "/sdcard/n.xml")

for node in re.findall(r"<node[^>]*>", xml):
    text = re.search(r'text="([^"]*)"', node)
    desc = re.search(r'content-desc="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if not label:
        continue
    if "CPA" in label or "额度" in label or "Antigravity" in label:
        print(repr(label[:100]), bounds.group(0) if bounds else "")
