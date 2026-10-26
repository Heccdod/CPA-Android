#!/usr/bin/env python3
"""Dump the current screen and tap the centre of the element whose text/desc matches.

Hardcoded coordinates are the main source of mis-taps here: the home screen grows an
account list, the settings page scrolls, and a tap that lands on the wrong row can drop
the app to the launcher. Resolving the bounds from uiautomator right before tapping
keeps every step anchored to what is actually on screen.
"""
import re
import subprocess
import sys

ADB = ["/opt/android-sdk/platform-tools/adb", "-s", "BU8XQGONNBQGFUHQ"]
XML = "/sdcard/ui.xml"
NODE = re.compile(r'<node[^>]*>')


def sh(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True).stdout


def dump():
    sh("shell", "uiautomator", "dump", XML)
    return sh("exec-out", "cat", XML)


def attr(node, name):
    m = re.search(name + r'="([^"]*)"', node)
    return m.group(1) if m else ""


def centre(bounds):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(xml, needle, index=0):
    hits = []
    for node in NODE.findall(xml):
        text = attr(node, "text")
        desc = attr(node, "content-desc")
        if needle == text or needle == desc:
            c = centre(attr(node, "bounds"))
            if c:
                hits.append(c)
    if not hits:
        return None
    return hits[index] if index < len(hits) else None


def main():
    needle = sys.argv[1]
    index = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    xml = dump()
    hit = find(xml, needle, index)
    if not hit:
        print("MISS", needle)
        listing = []
        for node in NODE.findall(xml):
            t = attr(node, "text") or attr(node, "content-desc")
            if t:
                listing.append(t)
        print("ONSCREEN:", " | ".join(listing[:40]))
        return 1
    sh("shell", "input", "tap", str(hit[0]), str(hit[1]))
    print("TAP", needle, hit)
    return 0


if __name__ == "__main__":
    sys.exit(main())
