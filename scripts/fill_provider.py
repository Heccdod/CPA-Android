#!/usr/bin/env python3
"""Type into one EditText of the add-provider dialog, proving focus first.

The failures this replaces were all the same shape: a tap that did not take focus, followed
by 120 DEL keycodes that then emptied the *previous* field and typing that filled it with the
new value. The dialog looked filled and was wrong. So: hide the IME to stop the layout
shifting, tap, re-dump and require focused="true" on that exact box, and only then clear and
type. Every step is verified against the dump rather than assumed.

Usage: fill_provider.py <name> <base-url> <key> <model> [<model>...]
"""
import re
import subprocess
import sys
import time

ADB = ["/opt/android-sdk/platform-tools/adb", "-s", "BU8XQGONNBQGFUHQ"]
XML = "/sdcard/ui.xml"
NODE = re.compile(r"<node[^>]*>")
HINT_MARK = "每行一个"


def sh(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True).stdout


def attr(node, name):
    m = re.search(name + r'="([^"]*)"', node)
    return m.group(1) if m else ""


def centre(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def dump():
    sh("shell", "uiautomator", "dump", XML)
    return sh("exec-out", "cat", XML)


def boxes(xml):
    out = []
    for node in NODE.findall(xml):
        if attr(node, "class").endswith("EditText"):
            c = centre(attr(node, "bounds"))
            if c:
                out.append({"centre": c, "text": attr(node, "text"),
                            "focused": attr(node, "focused") == "true"})
    return out


def hide_ime():
    # Back closes the IME when it is up and does nothing to the dialog when it is not.
    if "mInputShown=true" in sh("shell", "dumpsys", "input_method"):
        sh("shell", "input", "keyevent", "4")
        time.sleep(0.6)


def focus(index):
    """Tap the index-th EditText until the dump says it holds focus."""
    for _ in range(4):
        hide_ime()
        found = boxes(dump())
        if index >= len(found):
            return None
        x, y = found[index]["centre"]
        sh("shell", "input", "tap", str(x), str(y))
        time.sleep(0.7)
        found = boxes(dump())
        if index < len(found) and found[index]["focused"]:
            return found
        print("  focus attempt missed on box", index)
    return None


def clear_focused():
    # Batched: 100 separate adb calls took seconds and the IME animation in between moved
    # the very box the next tap was aimed at.
    keys = ["KEYCODE_MOVE_END"] + ["KEYCODE_DEL"] * 100
    sh("shell", "input", "keyevent", *keys)


def fill(index, text, attempts=3):
    for attempt in range(1, attempts + 1):
        found = focus(index)
        if found is None:
            print("MISS box", index)
            return False
        clear_focused()
        if text:
            sh("shell", "input", "text", text.replace(" ", "%s"))
        time.sleep(0.6)
        got = boxes(dump())[index]["text"]
        print("FIELD", index, "attempt", attempt, "->", repr(got))
        if got == text:
            return True
        if not text and (got == "" or HINT_MARK in got):
            return True
    return False


def main():
    args = sys.argv[1:]
    name, base_url, key = args[0], args[1], args[2]
    models = args[3:]
    if not fill(0, name):
        return 1
    if not fill(1, base_url):
        return 1
    if not fill(2, key):
        return 1
    # The models box takes newlines, which `input text` cannot send; only the first line is
    # typed by fill() and the rest are appended below with an explicit ENTER.
    if not fill(3, models[0] if models else ""):
        return 1
    for extra in models[1:]:
        if focus(3) is None:
            return 1
        sh("shell", "input", "keyevent", "KEYCODE_ENTER")
        sh("shell", "input", "text", extra.replace(" ", "%s"))
        time.sleep(0.4)
    hide_ime()
    for i, b in enumerate(boxes(dump())):
        print("FINAL", i, repr(b["text"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
