#!/usr/bin/env bash
# GitHub device-flow login without a browser on this machine.
#
# The token never gets typed by hand and never lands in the shell history: this script asks
# GitHub for a device code, prints the short user code, then polls until the user approves it
# in whatever browser they already have. It uses the public GitHub CLI OAuth client id, which
# is the documented client for this exact flow.
#
# "workflow" scope is required on top of "repo": without it GitHub rejects any push that
# touches .github/workflows/.
set -euo pipefail

CLIENT_ID="178c6fc778ccc68e1d6a"
SCOPE="repo workflow"

OUT=""
for attempt in 1 2 3 4 5; do
  OUT=$(curl -sS -m 25 -X POST https://github.com/login/device/code \
    -H 'Accept: application/json' \
    -d "client_id=$CLIENT_ID&scope=$(printf '%s' "$SCOPE" | sed 's/ /%20/g')" 2>/dev/null) || OUT=""
  [ -n "$OUT" ] && break
  echo "device/code attempt $attempt failed, retrying..." >&2
  sleep 3
done

DEVICE_CODE=$(printf '%s' "$OUT" | sed -n 's/.*"device_code":"\([^"]*\)".*/\1/p')
USER_CODE=$(printf '%s' "$OUT" | sed -n 's/.*"user_code":"\([^"]*\)".*/\1/p')
URI=$(printf '%s' "$OUT" | sed -n 's/.*"verification_uri":"\([^"]*\)".*/\1/p')
INTERVAL=$(printf '%s' "$OUT" | sed -n 's/.*"interval":[ ]*\([0-9]*\).*/\1/p')
INTERVAL=${INTERVAL:-5}

if [ -z "$DEVICE_CODE" ]; then
  echo "FAILED to obtain a device code:" >&2
  echo "$OUT" >&2
  exit 1
fi

cat <<BANNER

  在浏览器打开： $URI
  输入代码：     $USER_CODE

  等待授权中（最多 15 分钟）…
BANNER

DEADLINE=$(( $(date +%s) + 900 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  sleep "$INTERVAL"
  # A dropped poll is not fatal: the device code stays valid, the next tick retries.
  RESP=$(curl -sS -m 25 -X POST https://github.com/login/oauth/access_token \
    -H 'Accept: application/json' \
    -d "client_id=$CLIENT_ID&device_code=$DEVICE_CODE&grant_type=urn:ietf:params:oauth:grant-type:device_code" 2>/dev/null) || RESP=""
  if [ -z "$RESP" ]; then
    continue
  fi
  TOKEN=$(printf '%s' "$RESP" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  if [ -n "$TOKEN" ]; then
    printf '%s' "$TOKEN" > /root/.cpa-gh-token
    chmod 600 /root/.cpa-gh-token
    echo "AUTHORIZED"
    exit 0
  fi
  ERR=$(printf '%s' "$RESP" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')
  case "$ERR" in
    authorization_pending) ;;
    slow_down) INTERVAL=$((INTERVAL + 5)) ;;
    expired_token) echo "EXPIRED" >&2; exit 1 ;;
    access_denied) echo "DENIED" >&2; exit 1 ;;
    *) [ -n "$ERR" ] && echo "error: $ERR" >&2 ;;
  esac
done
echo "TIMEOUT" >&2
exit 1
