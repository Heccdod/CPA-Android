#!/usr/bin/env bash
# Device transport for this WSL2 setup.
#
# The phone is attached to Windows, not to WSL, so only adb.exe can see it. WSL's adb -- even
# with ADB_SERVER_SOCKET pointed at the host -- cannot reuse the Windows daemon (the protocol
# handshake is reset across the WSL/Windows boundary). Every device command therefore goes
# through adb.exe, and this wrapper keeps that detail in one place.
#
# Start the Windows daemon first if 5037 was stolen by a WSL adb:
#   pkill -f platform-tools/adb; adb.exe start-server
set -euo pipefail
ADB="/mnt/c/Users/28381/Documents/ds_home/tools/platform-tools/adb.exe"
exec "$ADB" -s BU8XQGONNBQGFUHQ "$@"
